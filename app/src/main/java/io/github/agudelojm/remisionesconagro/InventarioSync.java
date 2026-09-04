package io.github.agudelojm.remisionesconagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web sync for the two data files that previously arrived only by email.
 *
 * This is an ADDED input route: whatever comes off the wire is shaped into the same file the
 * email path delivers and handed to the EXISTING parser
 * ({@link ActualizarDatos#setFileInputStream}), so parsing, validation, the two-stage
 * temp/definitive flow and everything downstream are unchanged. The email route also remains
 * fully functional.
 *
 * Responsibilities:
 *   R1 — fetch the bodega inventory CSV over HTTP and feed it to the CSV parser. Runs at
 *        login (MenuActivity), because the bodega IS the técnico's número.
 *   R3 — derive the 2-digit bodega code from the técnico's número (persisted).
 *   R4 — once-per-day guard keyed on the local calendar date, one date per file; per-bodega
 *        archive/restore of definitive inventories so a número change never mixes two
 *        bodegas' data.
 *   R5 — the four Datos App tables (tecnicos, productos, clientes, tarifas) over HTTP,
 *        reassembled into the "Datos App.xlsx" workbook the parser already knows. Runs at app
 *        open (MainActivity), BEFORE login, so credentials are checked against server-fresh
 *        técnicos.
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

    /**
     * The four tables the Datos App endpoint serves ({@code ?tabla=...}) paired with the sheet
     * name each one must land on. The sheet names are matched exactly by
     * {@link ActualizarDatos} and {@link Methods#initializeData}, so do not "tidy" them —
     * note that the "tarifas" table is the "Tarifas Mantenimiento" sheet.
     */
    private static final String[][] DATOS_APP_TABLES = {
            {"tecnicos", "Tecnicos"},
            {"productos", "Productos"},
            {"clientes", "Clientes"},
            {"tarifas", "Tarifas Mantenimiento"},
    };

    private static class FetchResult {
        final FetchStatus status;
        final byte[] body;
        FetchResult(FetchStatus status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Outcome of the multi-request Datos App sync: the assembled workbook, or why not. */
    private static class DatosAppResult {
        final FetchStatus status;
        final byte[] workbook;
        DatosAppResult(FetchStatus status, byte[] workbook) {
            this.status = status;
            this.workbook = workbook;
        }
    }

    private InventarioSync() { }

    // ---------------------------------------------------------------------------------------
    // Entry points — the database at app open (MainActivity), the bodega at login (MenuActivity)
    // ---------------------------------------------------------------------------------------

    /**
     * Refreshes the "Datos App.xlsx" database BEFORE the login screen becomes usable, so a
     * técnico who was just added on the server can log in on a phone whose local file predates
     * them. Called from MainActivity.onCreate; unlike the bodega report it needs no número,
     * which is exactly why it can run this early.
     *
     * Two shapes, decided by whether a database already exists on the phone:
     * <ul>
     *   <li><b>It exists</b> — {@code onReady} runs immediately so the screen is not held up;
     *       the fetch continues in the background and {@link Methods#reloadDatosApp} swaps the
     *       fresh sheets in when it lands, which in practice is well before the técnico has
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
        final boolean hasLocalDatabase = new File(appContext.getFilesDir(), "Datos App.xlsx").exists();

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
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during database sync", e);
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
                    String message = handleBodegaResult(activity, appContext, bodegaResult);
                    if (message != null) showAlert(activity, message);
                });

            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during inventory sync", e);
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
     * Downloads the four database tables and reassembles them into a single workbook shaped
     * exactly like the emailed "Datos App.xlsx", so the unchanged {@link ActualizarDatos}
     * email code path validates and stores it.
     *
     * The endpoint serves ONE table per request as CSV ({@code ?tabla=...}), not a ready-made
     * workbook, hence the assembly here. It is all-or-nothing: if any table fails or comes
     * back without data rows we return the failure and never touch the stored database — a
     * half-written file (an empty "Tecnicos" above all) would lock every técnico out of login.
     *
     * @return the assembled workbook and status, or null when the endpoint is not configured.
     */
    private static DatosAppResult fetchDatosAppOrNull() {
        String base = BuildConfig.DATOS_APP_URL;
        if (base == null || base.trim().isEmpty()) {
            Log.i(TAG, "Datos App endpoint not configured; skipping database sync.");
            return null;
        }

        XSSFWorkbook wb = new XSSFWorkbook();
        try {
            for (String[] table : DATOS_APP_TABLES) {
                String url = base + (base.contains("?") ? "&" : "?") + "tabla=" + table[0];
                if (BuildConfig.DATOS_APP_KEY != null && !BuildConfig.DATOS_APP_KEY.isEmpty()) {
                    url += "&key=" + BuildConfig.DATOS_APP_KEY;
                }

                FetchResult result = httpGet(url);
                if (result.status != FetchStatus.OK) {
                    Log.w(TAG, "Table " + table[0] + " failed (" + result.status
                            + "); aborting database sync without touching the stored file.");
                    return new DatosAppResult(result.status, null);
                }
                if (!appendCsvAsSheet(wb, table[1], result.body)) {
                    return new DatosAppResult(FetchStatus.NO_DATA, null);
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return new DatosAppResult(FetchStatus.OK, bos.toByteArray());

        } catch (Exception e) {
            Log.e(TAG, "Error assembling the Datos App workbook", e);
            return new DatosAppResult(FetchStatus.NETWORK_ERROR, null);
        } finally {
            try {
                wb.close();
            } catch (IOException ignored) {
                // Nothing useful to do here; the bytes are already out.
            }
        }
    }

    /**
     * Converts one table's CSV payload into a sheet named exactly as {@link ActualizarDatos}
     * and {@link Methods#initializeData} expect. Reuses the parser's own quote-aware CSV
     * reader rather than keeping a second implementation.
     *
     * @return false if the payload carries no header or no data rows, which aborts the sync.
     */
    private static boolean appendCsvAsSheet(XSSFWorkbook wb, String sheetName, byte[] body) {
        String text = stripBom(new String(body, StandardCharsets.UTF_8));
        Sheet sheet = wb.createSheet(sheetName);

        int rowCount = 0;
        char delimiter = ',';
        boolean delimiterDetected = false;
        for (String line : text.split("\r\n|\n|\r")) {
            if (line.isEmpty()) continue; // trailing newline / blank separator
            if (!delimiterDetected) {
                delimiter = ActualizarDatos.detectCsvDelimiter(line);
                delimiterDetected = true;
            }
            List<String> columns = ActualizarDatos.parseCsvLine(line, delimiter);
            Row row = sheet.createRow(rowCount++);
            for (int i = 0; i < columns.size(); i++) {
                row.createCell(i).setCellValue(columns.get(i));
            }
        }

        if (rowCount < 2) {
            Log.w(TAG, "Sheet " + sheetName + " came back with " + rowCount
                    + " row(s); refusing to overwrite the local database.");
            return false;
        }
        return true;
    }

    /**
     * Removes a leading UTF-8 BOM, which this endpoint puts on every file.
     *
     * Not cosmetic: {@link Methods} resolves every column by header name and compares with
     * String.trim(), which does NOT strip U+FEFF. A BOM'd first header (BOM + finca_id,
     * BOM + referencia) would resolve to index -1 and the finca and producto lookups would
     * silently return empty lists.
     */
    private static String stripBom(String text) {
        return (!text.isEmpty() && text.charAt(0) == '\uFEFF') ? text.substring(1) : text;
    }

    /**
     * Runs on the UI thread. Applies the outcome and returns the message to show the técnico,
     * or null when the outcome needs no dialog.
     */
    private static String handleDatosAppResult(Activity activity, Context appContext, DatosAppResult result) {
        switch (result.status) {
            case OK:
                if (ingestDatosApp(activity, result.workbook)) {
                    Methods.setLastDatosAppSyncDate(appContext, todayString());
                }
                // A rejected workbook already surfaced the parser's own message and leaves the
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
     * Hands the assembled workbook to the existing parser, which detects the "Tecnicos" sheet,
     * validates every required sheet and column, and stores it as "Datos App.xlsx" — identical
     * to opening the file from email. Runs on the UI thread with an Activity context so the
     * parser's dialogs behave correctly.
     *
     * @return true once the parser has accepted and saved the file.
     */
    private static boolean ingestDatosApp(Activity activity, byte[] workbook) {
        try {
            File cacheFile = new File(activity.getCacheDir(), "DatosApp_sync.xlsx");
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(workbook);
            }
            String result = ActualizarDatos.setFileInputStream(activity, Uri.fromFile(cacheFile));
            // processAndValidateWorkbook returns "Base de Datos" once the file has been saved.
            if (!"Base de Datos".equals(result)) {
                Log.w(TAG, "Parser did not accept the assembled database workbook: " + result);
                return false;
            }
            // Methods.initializeData() ran before login, so the sheets in memory are still the
            // previous file's: refresh them in place or the técnico works against stale
            // clientes/productos/tarifas for the rest of the session.
            Methods.reloadDatosApp(activity);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error ingesting the Datos App workbook", e);
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
