package io.github.agudelojm.remisionesconagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web sync for the two data files that previously arrived only by email.
 *
 * This is an ADDED input route, and the email route remains fully functional. The bodega CSV is
 * still handed to the EXISTING parser ({@link ActualizarDatos#setFileInputStream}), so the
 * two-stage temp/definitive inventory flow is unchanged. The database tables go to
 * {@link ActualizarDatos#ingestDatosAppTables}, which applies the same structure validation a
 * workbook from the correo gets — both routes converge there, on the same data model.
 *
 * Responsibilities:
 *   R1 — fetch the bodega inventory CSV over HTTP and feed it to the CSV parser. Runs at
 *        login (MenuActivity), because the bodega IS the técnico's número.
 *   R3 — derive the 2-digit bodega code from the técnico's número (persisted).
 *   R4 — once-per-day guard keyed on the local calendar date, one date per file; per-bodega
 *        archive/restore of definitive inventories so a número change never mixes two
 *        bodegas' data.
 *   R5 — the four Datos App tables (tecnicos, productos, clientes, tarifas) over HTTP,
 *        parsed straight into the four string tables the app keeps (no workbook is built
 *        any more). Runs at app open (MainActivity), BEFORE login, so credentials are checked
 *        against server-fresh técnicos.
 */
public class InventarioSync {

    private static final String TAG = "InventarioSync";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * True while a database sync is running. MainActivity.onCreate fires again on rotation and
     * on activity recreation, and without this the four downloads (and their alert) would run
     * twice. Only the database needs it: the bodega sync runs once per login by construction.
     */
    private static final AtomicBoolean datosAppSyncInFlight = new AtomicBoolean(false);

    private enum FetchStatus { OK, NO_DATA, AUTH_ERROR, NETWORK_ERROR }

    private static class FetchResult {
        final FetchStatus status;
        final byte[] body;
        FetchResult(FetchStatus status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Outcome of the multi-request Datos App sync: the four tables, or why not. */
    private static class DatosAppResult {
        final FetchStatus status;
        final Map<String, Tabla> tablas;
        DatosAppResult(FetchStatus status, Map<String, Tabla> tablas) {
            this.status = status;
            this.tablas = tablas;
        }
    }

    private InventarioSync() { }

    // ---------------------------------------------------------------------------------------
    // Entry points — the database at app open (MainActivity), the bodega at login (MenuActivity)
    // ---------------------------------------------------------------------------------------

    /**
     * Refreshes the database BEFORE the login screen becomes usable, so a
     * técnico who was just added on the server can log in on a phone whose local file predates
     * them. Called from MainActivity.onCreate; unlike the bodega report it needs no número,
     * which is exactly why it can run this early.
     *
     * Two shapes, decided by whether a database already exists on the phone:
     * <ul>
     *   <li><b>It exists</b> — {@code onReady} runs immediately so the screen is not held up;
     *       the fetch continues in the background and {@link Methods#adoptDatosApp} swaps the
     *       fresh tables in when it lands, which in practice is well before the técnico has
     *       finished typing correo + cédula.</li>
     *   <li><b>It does not exist</b> (fresh install) — we MUST wait, because
     *       {@link Methods#initializeData} would otherwise reach its "abra el archivo desde su
     *       correo" fatal dialog 1.25 s from now and close the app before the download landed.
     *       A "Descargando base de datos" dialog covers the wait.</li>
     * </ul>
     *
     * @param onReady what the caller does once the app may load its data — in practice
     *                {@code Methods.initializeData(activity)}. Runs on the UI thread, exactly
     *                once, whether the sync succeeded, failed or was skipped. The one exception
     *                is the activity being gone by the time a bootstrap download returns: there
     *                is nothing left to load into, and initializeData would abort anyway.
     */
    public static void runDatosAppSyncIfNeeded(final Activity activity, final Runnable onReady) {
        final Context appContext = activity.getApplicationContext();
        // Load the stored database HERE, before deciding whether to block, rather than letting
        // initializeData discover 1.25 s from now that it is unusable: by then this method has
        // already chosen not to bootstrap, so a corrupt store cost the técnico a failed launch
        // before the next one could recover. loadDatosApp adopts a healthy store, migrates a
        // legacy workbook once, and discards anything unusable — so false here means there is
        // genuinely nothing to work with and the download below must block. It costs ~100 ms of
        // work initializeData would otherwise repeat, and it is silent either way.
        final boolean hasLocalDatabase = Methods.loadDatosApp(activity);

        if (!shouldSyncDatosApp(appContext, hasLocalDatabase)) {
            onReady.run();
            return;
        }

        // Only the bootstrap case blocks; otherwise the login screen is usable straight away.
        final AlertDialog waitDialog = hasLocalDatabase
                ? null
                : Methods.showLoading(activity, "Descargando base de datos...");
        if (waitDialog != null) {
            waitDialog.show();
        } else {
            onReady.run();
        }

        datosAppSyncInFlight.set(true);
        executor.execute(() -> {
            DatosAppResult fetched = null;
            try {
                fetched = fetchDatosAppOrNull();
            } catch (Throwable t) {
                // Throwable, not Exception: an OutOfMemoryError while holding the payloads must
                // leave the técnico on a working login screen, not take the process down.
                Log.e(TAG, "Unexpected error during database sync", t);
            }
            final DatosAppResult result = fetched;

            if (activity.isFinishing() || activity.isDestroyed()) {
                Log.w(TAG, "Activity gone before database ingest; will retry on the next start.");
                datosAppSyncInFlight.set(false);
                return;
            }
            activity.runOnUiThread(() -> {
                try {
                    if (waitDialog != null && waitDialog.isShowing()) waitDialog.dismiss();
                    String message = (result == null)
                            ? null
                            : handleDatosAppResult(activity, appContext, result);
                    if (!hasLocalDatabase) onReady.run(); // the app can finally load its data
                    if (message != null) showAlert(activity, message);
                } finally {
                    datosAppSyncInFlight.set(false);
                }
            });
        });
    }

    /**
     * @param hasLocalDatabase when false we are bootstrapping: the daily guard is ignored,
     *                         because there is nothing to fall back on if we skip.
     */
    private static boolean shouldSyncDatosApp(Context appContext, boolean hasLocalDatabase) {
        if (BuildConfig.DATOS_APP_URL == null || BuildConfig.DATOS_APP_URL.trim().isEmpty()) {
            Log.i(TAG, "Datos App endpoint not configured; skipping database sync.");
            return false;
        }
        if (datosAppSyncInFlight.get()) {
            // A rotation or an activity recreation re-runs onCreate; without this guard the
            // same four downloads (and their alert) would fire a second time.
            Log.d(TAG, "Database sync already in flight; skipping this run.");
            return false;
        }
        if (hasLocalDatabase && todayString().equals(Methods.getLastDatosAppSyncDate(appContext))) {
            Log.d(TAG, "Database already synced today; skipping.");
            return false;
        }
        return true;
    }

    /**
     * Runs the daily BODEGA inventory sync if the day-boundary guard allows it, or
     * unconditionally when the bodega (número) has changed since the last sync. This one runs
     * at login (see MenuActivity) because it needs the técnico's número. Network and file work
     * happen on a background thread; parsing and any user-facing dialog are posted back to the
     * UI thread so the existing parser behaves exactly as on the email path.
     */
    public static void runDailySyncIfNeeded(final Activity activity, final String numero) {
        if (numero == null || numero.trim().isEmpty()) {
            Log.w(TAG, "No número available at login; skipping inventory sync.");
            return;
        }
        final Context appContext = activity.getApplicationContext();
        final String bodegaCode = Methods.format2Digitos(numero);

        executor.execute(() -> {
            try {
                final String storedCode = Methods.getBodegaCodigo(appContext);
                final boolean numeroChanged = !bodegaCode.equals(storedCode);

                if (numeroChanged) {
                    // A different bodega is logging in: park the outgoing bodega's definitive,
                    // restore this bodega's if we've seen it before, and force a resync now
                    // (R3/R4). The first time we ever track a code (storedCode empty) we adopt
                    // any existing definitive as this bodega's baseline instead of wiping it.
                    if (storedCode != null && !storedCode.isEmpty()) {
                        swapBodega(appContext, storedCode, bodegaCode);
                    }
                    Methods.setBodegaCodigo(appContext, bodegaCode);
                }

                // The database keeps its own date (lastDatosAppSyncDate) and its own trigger at
                // app open, so a failure on one endpoint never consumes the other's daily try.
                if (!numeroChanged && todayString().equals(Methods.getLastSyncDate(appContext))) {
                    Log.d(TAG, "Bodega " + bodegaCode + " already synced today; skipping.");
                    return;
                }

                final FetchResult bodegaResult = fetchBodega(bodegaCode);

                if (activity.isFinishing() || activity.isDestroyed()) {
                    Log.w(TAG, "Activity gone before ingest; will retry next login.");
                    return;
                }
                activity.runOnUiThread(() -> {
                    try {
                        String message = handleBodegaResult(activity, appContext, bodegaResult);
                        if (message != null) showAlert(activity, message);
                    } catch (Throwable t) {
                        // The bodega workbook is small, but the same rule applies: a failed sync
                        // must never take down a session the técnico is already working in.
                        Log.e(TAG, "Error ingesting the bodega inventory", t);
                    }
                });

            } catch (Throwable t) {
                Log.e(TAG, "Unexpected error during inventory sync", t);
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Bodega inventory (R1)
    // ---------------------------------------------------------------------------------------

    private static FetchResult fetchBodega(String bodegaCode) {
        String url = BuildConfig.INVENTARIO_BASE_URL
                + "?bodega=" + bodegaCode
                + "&key=" + BuildConfig.INVENTARIO_API_KEY;
        return httpGet(url);
    }

    /**
     * Runs on the UI thread. Applies the B/F outcome matrix and returns the message to show
     * the técnico, or null when the outcome needs no dialog.
     */
    private static String handleBodegaResult(Activity activity, Context appContext, FetchResult result) {
        switch (result.status) {
            case OK:
                if (ingestBodegaCsv(activity, result.body)) {
                    Methods.setLastSyncDate(appContext, todayString());
                    Methods.setInventarioWbToNull(); // force reload from the new temp
                }
                // If the parser rejected the file it already surfaced its own message; we do
                // not mark the day done, so the next login retries.
                return null;
            case NO_DATA: // 404 — expected "no report for this bodega today"
                Methods.setLastSyncDate(appContext, todayString());
                return "No hay inventario de bodega para sincronizar hoy.";
            case AUTH_ERROR: // 401 — bad/missing key. Non-fatal: the técnico can keep working.
                return "No se pudo sincronizar el inventario por un error de "
                        + "configuración. Puede continuar trabajando; contacte al administrador.";
            case NETWORK_ERROR:
            default: // no internet / timeout / other. Not marked done, so next login retries.
                return "No se pudo sincronizar el inventario de bodega. "
                        + "Si es posible, conéctese a internet y reinicie la app.";
        }
    }

    /**
     * Writes the fetched CSV to a temp file whose name starts with "INV" (required by the
     * parser) and feeds it to the existing {@link ActualizarDatos#setFileInputStream}, which
     * converts it and stores it as the pending "Inventario_Temp.xlsx". Returns true on a
     * successful ingest. Uses an Activity context so the parser's dialogs work correctly.
     */
    private static boolean ingestBodegaCsv(Activity activity, byte[] body) {
        try {
            File cacheFile = new File(activity.getCacheDir(), "INV_bodega_sync.csv");
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(body);
            }
            String result = ActualizarDatos.setFileInputStream(activity, Uri.fromFile(cacheFile));
            // processCsvInventory returns "Inventario (CSV)" on success.
            return result != null && result.startsWith("Inventario");
        } catch (Exception e) {
            Log.e(TAG, "Error ingesting bodega CSV", e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bodega switch: per-bodega archive / flush / restore (R4)
    // ---------------------------------------------------------------------------------------

    /**
     * Parks the outgoing bodega's definitive inventory under its own número
     * (Inventario_&lt;oldCode&gt;.xlsx), flushes the live slot so the incoming bodega's sync
     * cannot diff against stale rows, and restores the incoming bodega's definitive if one was
     * archived earlier. Any pending un-accepted "Inventario_Temp.xlsx" for the old bodega is
     * discarded. The técnico's accepted stock changes live inside the archived definitive, so
     * they persist across switches.
     */
    private static void swapBodega(Context context, String oldCode, String newCode) {
        File dir = context.getFilesDir();
        File definitive = new File(dir, "Inventario.xlsx");
        File pendingTemp = new File(dir, "Inventario_Temp.xlsx");

        // 1. Park the outgoing definitive.
        if (definitive.exists()) {
            copyFile(definitive, new File(dir, "Inventario_" + oldCode + ".xlsx"));
        }

        // 2. Flush the live slot (definitive + any pending proposal for the old bodega).
        if (definitive.exists() && !definitive.delete()) {
            Log.w(TAG, "Could not delete live Inventario.xlsx during bodega switch");
        }
        if (pendingTemp.exists() && !pendingTemp.delete()) {
            Log.w(TAG, "Could not delete pending Inventario_Temp.xlsx during bodega switch");
        }

        // 3. Restore the incoming bodega's definitive if we've seen it before.
        File incomingArchive = new File(dir, "Inventario_" + newCode + ".xlsx");
        if (incomingArchive.exists()) {
            copyFile(incomingArchive, definitive);
        }

        Methods.setInventarioWbToNull(); // force in-memory reload from the swapped files
        Log.d(TAG, "Bodega switch " + oldCode + " -> " + newCode + " complete.");
    }

    private static void copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying " + src.getName() + " -> " + dst.getName(), e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Datos App database (R5)
    // ---------------------------------------------------------------------------------------

    /**
     * Downloads the four database tables and turns each one into a {@link Tabla}.
     *
     * The endpoint serves ONE table per request as CSV ({@code ?tabla=...}), and CSV is also what
     * the app stores, so nothing here goes through Apache POI: the old version assembled an
     * .xlsx workbook purely to hand the parser a file, which cost ~108 MB of heap and several
     * seconds for data that is 544 KB on disk.
     *
     * It is all-or-nothing: if any table fails or comes back without data rows we return the
     * failure and never touch the stored database — a half-written database (an empty "Tecnicos"
     * above all) would lock every técnico out of login.
     *
     * @return the four tables and status, or null when the endpoint is not configured.
     */
    private static DatosAppResult fetchDatosAppOrNull() {
        String base = BuildConfig.DATOS_APP_URL;
        if (base == null || base.trim().isEmpty()) {
            Log.i(TAG, "Datos App endpoint not configured; skipping database sync.");
            return null;
        }

        Map<String, Tabla> tablas = new LinkedHashMap<>();
        for (String[] table : DatosAppStore.TABLES) {
            String url = base + (base.contains("?") ? "&" : "?") + "tabla=" + table[0];
            if (BuildConfig.DATOS_APP_KEY != null && !BuildConfig.DATOS_APP_KEY.isEmpty()) {
                url += "&key=" + BuildConfig.DATOS_APP_KEY;
            }

            FetchResult result = httpGet(url);
            if (result.status != FetchStatus.OK) {
                Log.w(TAG, "Table " + table[0] + " failed (" + result.status
                        + "); aborting database sync without touching the stored data.");
                return new DatosAppResult(result.status, null);
            }

            Tabla tabla = Tabla.fromCsv(table[1], new String(result.body, StandardCharsets.UTF_8));
            if (tabla.rowCount() < 1 || tabla.columnCount() < 1) {
                Log.w(TAG, "Table " + table[1] + " came back with " + tabla.rowCount()
                        + " data row(s); refusing to overwrite the local database.");
                return new DatosAppResult(FetchStatus.NO_DATA, null);
            }
            tablas.put(table[1], tabla);
        }

        return new DatosAppResult(FetchStatus.OK, tablas);
    }
    /**
     * Runs on the UI thread. Applies the outcome and returns the message to show the técnico,
     * or null when the outcome needs no dialog.
     */
    private static String handleDatosAppResult(Activity activity, Context appContext, DatosAppResult result) {
        switch (result.status) {
            case OK:
                if (ingestDatosApp(activity, result.tablas)) {
                    Methods.setLastDatosAppSyncDate(appContext, todayString());
                }
                // Rejected data already surfaced the validation message and leaves the
                // day unmarked, so the next login retries. Silent on success.
                return null;
            case AUTH_ERROR: // 401 — bad/missing key. Non-fatal: the técnico keeps working.
                return "No se pudo actualizar la base de datos por un error de configuración. "
                        + "Puede continuar trabajando; contacte al administrador.";
            case NO_DATA:
                // Unlike the bodega report, an empty database is never a normal daily state —
                // these tables always have rows — so this is a server problem and must NOT
                // mark the day done. Same message as a network failure.
            case NETWORK_ERROR:
            default:
                return "No se pudo actualizar la base de datos (técnicos, clientes, productos "
                        + "y tarifas). Si es posible, conéctese a internet y reinicie la app.";
        }
    }

    /**
     * Hands the downloaded tables to the shared validate-persist-adopt path, which applies exactly
     * the same structure checks as a workbook opened from the correo and then makes the data live
     * for this session (so the técnico is not working against the previous database until a
     * restart). Runs on the UI thread with an Activity context so the validation dialogs behave
     * correctly.
     *
     * Catches {@link Throwable}, not Exception: an OutOfMemoryError here used to escape to the main
     * looper and crash the app on the login screen, and since the stored database had already been
     * replaced while the daily guard had not advanced, every subsequent launch retried and crashed
     * again. A failed ingest must always degrade to "could not update", never to a crash loop.
     *
     * @return true once the tables are validated, persisted and live.
     */
    private static boolean ingestDatosApp(Activity activity, Map<String, Tabla> tablas) {
        try {
            return ActualizarDatos.ingestDatosAppTables(activity, tablas);
        } catch (Throwable t) {
            Log.e(TAG, "Error ingesting the Datos App tables", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP + helpers
    // ---------------------------------------------------------------------------------------

    private static FetchResult httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            switch (code) {
                case HttpURLConnection.HTTP_OK: // 200
                    return new FetchResult(FetchStatus.OK, readAll(conn.getInputStream()));
                case HttpURLConnection.HTTP_NOT_FOUND: // 404 -> no data today (normal state)
                    return new FetchResult(FetchStatus.NO_DATA, null);
                case HttpURLConnection.HTTP_UNAUTHORIZED: // 401 -> config error
                    return new FetchResult(FetchStatus.AUTH_ERROR, null);
                default:
                    Log.w(TAG, "Unexpected HTTP status " + code + " from " + safeHost(urlStr));
                    return new FetchResult(FetchStatus.NETWORK_ERROR, null);
            }
        } catch (IOException e) {
            // No connection resolves here (UnknownHostException, timeouts, ...) — this is NOT a
            // 404; it means we never reached the server, so we retry on the next login.
            Log.w(TAG, "Network error contacting " + safeHost(urlStr) + ": " + e.getMessage());
            return new FetchResult(FetchStatus.NETWORK_ERROR, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** Host only, so a full URL with the API key never lands in the logs. */
    private static String safeHost(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Exception e) {
            return "server";
        }
    }

    /** Local calendar date as yyyy-MM-dd; rolls over at local midnight (R4). */
    private static String todayString() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static void showAlert(Activity activity, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Sincronización de Inventario")
                .setMessage(message)
                .setNeutralButton("OK", (d, w) -> d.dismiss())
                .create()
                .show();
    }
}
