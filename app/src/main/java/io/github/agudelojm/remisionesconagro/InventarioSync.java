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
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web sync for the two inventory files that previously arrived only by email.
 *
 * This is an ADDED input route: the fetched bytes are handed to the EXISTING parser
 * ({@link ActualizarDatos#setFileInputStream}) exactly as an emailed file would be, so
 * parsing, validation, the two-stage temp/definitive flow and everything downstream are
 * unchanged. The email route also remains fully functional.
 *
 * Responsibilities:
 *   R1 — fetch the bodega inventory CSV over HTTP and feed it to the CSV parser.
 *   R3 — derive the 2-digit bodega code from the técnico's número (persisted).
 *   R4 — once-per-day guard keyed on the local calendar date; per-bodega archive/restore
 *        of definitive inventories so a número change never mixes two bodegas' data.
 *   R5 — Datos App.xlsx over HTTP behind an isolated, not-yet-configured seam.
 */
public class InventarioSync {

    private static final String TAG = "InventarioSync";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private enum FetchStatus { OK, NO_DATA, AUTH_ERROR, NETWORK_ERROR }

    private static class FetchResult {
        final FetchStatus status;
        final byte[] body;
        FetchResult(FetchStatus status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    private InventarioSync() { }

    // ---------------------------------------------------------------------------------------
    // Entry point — call once at login (see MenuActivity).
    // ---------------------------------------------------------------------------------------

    /**
     * Runs the daily inventory sync if the day-boundary guard allows it, or unconditionally
     * when the bodega (número) has changed since the last sync. Network and file work happen
     * on a background thread; parsing and any user-facing dialog are posted back to the UI
     * thread so the existing parser behaves exactly as on the email path.
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
                } else {
                    // Same bodega: obey the once-per-day guard.
                    if (todayString().equals(Methods.getLastSyncDate(appContext))) {
                        Log.d(TAG, "Inventory already synced today for bodega " + bodegaCode + "; skipping.");
                        return;
                    }
                }

                final FetchResult bodegaResult = fetchBodega(bodegaCode);
                final FetchResult datosResult = fetchDatosAppOrNull();

                if (activity.isFinishing() || activity.isDestroyed()) {
                    Log.w(TAG, "Activity gone before ingest; will retry next login.");
                    return;
                }
                activity.runOnUiThread(() -> {
                    handleBodegaResult(activity, appContext, bodegaResult);
                    if (datosResult != null && datosResult.status == FetchStatus.OK) {
                        ingestDatosApp(activity, datosResult.body);
                    }
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

    /** Runs on the UI thread. Applies the B/F outcome matrix. */
    private static void handleBodegaResult(Activity activity, Context appContext, FetchResult result) {
        switch (result.status) {
            case OK:
                if (ingestBodegaCsv(activity, result.body)) {
                    Methods.setLastSyncDate(appContext, todayString());
                    Methods.setInventarioWbToNull(); // force reload from the new temp
                }
                // If the parser rejected the file it already surfaced its own message; we do
                // not mark the day done, so the next login retries.
                break;
            case NO_DATA: // 404 — expected "no report for this bodega today"
                Methods.setLastSyncDate(appContext, todayString());
                showAlert(activity, "No hay inventario de bodega para sincronizar hoy.");
                break;
            case AUTH_ERROR: // 401 — bad/missing key. Non-fatal: the técnico can keep working.
                showAlert(activity, "No se pudo sincronizar el inventario por un error de "
                        + "configuración. Puede continuar trabajando; contacte al administrador.");
                break;
            case NETWORK_ERROR:
            default: // no internet / timeout / other. Not marked done, so next login retries.
                showAlert(activity, "No se pudo sincronizar el inventario de bodega. "
                        + "Si es posible, conéctese a internet y reinicie la app.");
                break;
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
    // Datos App.xlsx (R5) — isolated seam, endpoint not built yet.
    // ---------------------------------------------------------------------------------------

    /**
     * TODO(endpoint): the Datos App.xlsx endpoint does not exist yet. When the client provides
     * the URL (and key/auth if any), set DATOS_APP_URL / DATOS_APP_KEY in build.gradle.kts
     * (backed by local.properties) and this method starts working with no other change. While
     * the URL is blank it is a no-op, so R1–R4 ship and function without it.
     *
     * @return the fetch result, or null when the endpoint is not configured.
     */
    private static FetchResult fetchDatosAppOrNull() {
        String base = BuildConfig.DATOS_APP_URL;
        if (base == null || base.trim().isEmpty()) {
            Log.i(TAG, "Datos App endpoint not configured yet (TODO endpoint); skipping.");
            return null;
        }
        String url = base;
        if (BuildConfig.DATOS_APP_KEY != null && !BuildConfig.DATOS_APP_KEY.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "key=" + BuildConfig.DATOS_APP_KEY;
        }
        return httpGet(url);
    }

    /**
     * Feeds the fetched workbook to the existing parser, which detects the "Tecnicos" sheet and
     * stores it as "Datos App.xlsx" — identical to opening the file from email. Runs on the UI
     * thread with an Activity context so the parser's dialogs behave correctly.
     */
    private static void ingestDatosApp(Activity activity, byte[] body) {
        try {
            File cacheFile = new File(activity.getCacheDir(), "DatosApp_sync.xlsx");
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(body);
            }
            ActualizarDatos.setFileInputStream(activity, Uri.fromFile(cacheFile));
            Methods.setInventarioWbToNull();
        } catch (Exception e) {
            Log.e(TAG, "Error ingesting Datos App workbook", e);
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
