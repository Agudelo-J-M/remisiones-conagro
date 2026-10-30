package io.github.agudelojm.remisionesconagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Utility class containing common methods for data handling, Excel operations, 
 * and UI interactions across the application.
 * Now adjusted to handle a single "Datos App.xlsx" workbook with multiple sheets.
 */
public class Methods {

    private static final String TAG = "Methods";
    private static WeakReference<Activity> activityRef;
    private static AlertDialog alertDialog = null;
    private static boolean isInitializing = false;
    private static final DataFormatter dataFormatter = new DataFormatter();

    // The four Datos App tables, held as plain strings rather than as a POI workbook. See Tabla
    // for why: the workbook form cost ~100 MB of heap and over a second of UI-thread parsing per
    // copy, and the sync briefly needed two copies at once.
    //
    // volatile because the readers (validateCredentials, buscarItem, buscarCliente,
    // getProductoDetails) are not synchronized while adoptDatosApp is. Today every read and write
    // happens on the main thread — the sync parses on a background thread but posts the adopt back
    // — so this is belt-and-braces; it is here so that moving the ingest off the UI thread, which
    // is now cheap enough to be tempting, cannot turn into a silent visibility bug. Each field is
    // an immutable Tabla, so publishing the reference is all that has to be safe.
    private static volatile Tabla tecnicosTabla = null;
    private static volatile Tabla productosTabla = null;
    private static volatile Tabla clientesTabla = null;
    private static volatile Tabla tarifasMantenimientoTabla = null;

    // Derived autocomplete lists. Rebuilt only when the tables are replaced (see adoptDatosApp),
    // never per screen open: three activities used to rebuild the 4 800-entry producto list in
    // onCreate, on the UI thread, every single time.
    private static List<String> cacheListaProductos = null;
    private static List<String> cacheListaFincas = null;
    private static List<String> cacheListaMantenimientos = null;
    private static List<Map<String, String>> cacheListaClientes = null;

    // Independent workbook for temporary inventory operations. Still POI: one bodega's inventory
    // is a few hundred rows, and the two-stage temp/definitive flow is built around the workbook.
    private static XSSFWorkbook inventarioWb = null;

    /**
     * Updates the current activity reference to manage dialogs and context-specific actions.
     */
    public static void updateActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }

    /**
     * Initializes the application data by loading the "Datos App.xlsx" workbook and extracting sheets.
     */
    public static void initializeData(Context context) {
        if (isInitializing) {
            Log.d(TAG, "Initialization already in progress, skipping.");
            return;
        }
        isInitializing = true;
        
        // Show loading using the current context
        alertDialog = showLoading(context, "Leyendo Exceles...");
        alertDialog.show();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            // CRITICAL: Always use the latest activity reference to avoid crashes on recreation
            Activity currentActivity = (activityRef != null) ? activityRef.get() : null;
            if (currentActivity == null || currentActivity.isFinishing() || currentActivity.isDestroyed()) {
                Log.w(TAG, "Activity context is no longer valid, aborting initialization.");
                isInitializing = false;
                return;
            }

            try {
                // Reading the database belongs here, not at app open: this runs behind the
                // "Leyendo Exceles" dialog and after the first frame, which is what keeps the
                // legacy migration (~1.4 s on a desktop, several seconds on a phone) from freezing
                // MainActivity.onCreate on the launch after an update.
                //
                // Skipped only when tables are already live — an ingest from the correo or the web
                // sync adopts as it saves, so disk never holds a version newer than memory and
                // re-parsing would just repeat the work.
                if (tecnicosTabla == null && !loadDatosApp(currentActivity)) {
                    throw new Exception("Base de datos no encontrada");
                }

                // Load Inventory (Template for validations)
                try {
                    inventarioWb = loadWorkbookFromFile(currentActivity, "Inventario.xlsx");
                } catch (Exception e) {
                    Log.w(TAG, "Inventario.xlsx could not be loaded on startup: " + e.getMessage());
                }

                if (alertDialog != null && alertDialog.isShowing()) {
                    alertDialog.dismiss();
                }
                
                // Only show final success if we aren't in a silent refresh
                if (!(currentActivity instanceof MainActivity)) {
                    alertDialog = showAlert(currentActivity, "Éxito leyendo Exceles");
                    if (inventarioWb == null) {
                        alertDialog.setMessage("Éxito leyendo Base de Datos. Nota: No se encontró 'Inventario.xlsx', las validaciones de stock estarán deshabilitadas.");
                    }
                    alertDialog.show();
                }
                
                // Perform cleanup of old referrals
                cleanOldRemisiones(currentActivity);

            } catch (Exception e) {
                Log.e(TAG, "Error initializing data: " + e.getMessage());
                if (alertDialog != null && alertDialog.isShowing()) {
                    alertDialog.dismiss();
                }
                
                String errorMsg = "Error: " + e.getMessage() + ". Por favor, abra el archivo desde su correo usando esta aplicación.";
                showFatalError(currentActivity, errorMsg);
            } finally {
                isInitializing = false;
            }
        }, 1250);
    }

    /**
     * Deletes referral PDFs and JSON data older than 14 days.
     * Does NOT delete the temporary inventory here as it causes loading failures.
     */
    public static synchronized void cleanOldRemisiones(Context context) {
        File dir = context.getFilesDir();
        
        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith("Remision_") && (name.endsWith(".pdf") || name.endsWith(".json")));
        if (files == null) return;

        long thresholdMillis = 14L * 24 * 60 * 60 * 1000;
        long now = System.currentTimeMillis();

        for (File file : files) {
            if (now - file.lastModified() > thresholdMillis) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old remission file: " + file.getName());
                }
            }
        }
    }

    /**
     * Helper to load a workbook from a specific filename in internal storage.
     */
    public static XSSFWorkbook loadWorkbookFromFile(Context context, String fileName) throws Exception {
        File file = new File(context.getFilesDir(), fileName);
        if (!file.exists()) return null;
        
        try (FileInputStream fis = new FileInputStream(file)) {
            return loadWorkbook(context, fis);
        }
    }

    /**
     * Like {@link #loadWorkbookFromFile} but silent: it logs instead of raising the app-closing
     * "Error del excel" dialog.
     *
     * Used where a workbook is read before the UI is ready to interrupt the técnico — the legacy
     * migration runs at app open, where a dialog would land on top of the sync's own
     * "Descargando base de datos" and where the caller may only hold an application context. The
     * caller decides what a null means; {@link #initializeData} still owns the user-facing failure.
     */
    static XSSFWorkbook loadWorkbookQuietly(Context context, String fileName) {
        File file = new File(context.getFilesDir(), fileName);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            return new XSSFWorkbook(OPCPackage.open(fis));
        } catch (Exception e) {
            Log.e(TAG, "Error loading " + fileName + " quietly: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates an XSSFWorkbook from a FileInputStream.
     */
    public static XSSFWorkbook loadWorkbook(Context context, FileInputStream fileInputStream) {
        try {
            if (fileInputStream != null) {
                OPCPackage pkg = OPCPackage.open(fileInputStream);
                return new XSSFWorkbook(pkg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading workbook", e);
            showFatalError(context, "Error del excel. Solicite revisión a un Administrador.");
        }
        return null;
    }

    /**
     * Saves a workbook to internal storage.
     */
    public static synchronized void saveWorkbook(Context context, XSSFWorkbook wb, String fileName) {
        if (wb == null) return;
        try {
            File file = new File(context.getFilesDir(), fileName);
            try (OutputStream out = new FileOutputStream(file)) {
                wb.write(out);
                Log.d(TAG, "Workbook saved: " + fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception saving workbook: " + fileName, e);
            showFatalError(context, "Error guardando excel, comuniquese con un administrador");
        }
    }

    /**
     * Displays a basic alert dialog with an "OK" button.
     */
    public static AlertDialog showAlert(Context context, String message) {
        return new AlertDialog.Builder(context)
                .setTitle("Alerta")
                .setMessage(message)
                .setNeutralButton("OK", (dialog, which) -> dialog.dismiss())
                .create();
    }

    /**
     * Displays a loading dialog without buttons that cannot be dismissed by the user.
     */
    public static AlertDialog showLoading(Context context, String message) {
        return new AlertDialog.Builder(context)
                .setTitle("Cargando")
                .setMessage(message)
                .setCancelable(false)
                .create();
    }

    /**
     * Shows a fatal error dialog that closes the app upon dismissal.
     */
    public static void showFatalError(Context context, String message) {
        AlertDialog ad = showAlert(context, message);
        setCloseAppOnDismiss(ad);
        ad.show();
    }

    /**
     * Shows an error dialog that finishes the current activity upon dismissal.
     */
    public static void showErrorAndFinishActivity(Context context, String message) {
        AlertDialog ad = showAlert(context, message);
        setFinishActivityOnDismiss(ad);
        ad.show();
    }

    /**
     * Configures the dialog to close the application when dismissed.
     */
    public static void setCloseAppOnDismiss(AlertDialog alertDialog) {
        alertDialog.setOnDismissListener(dialogInterface -> {
            if (activityRef != null && activityRef.get() != null) {
                activityRef.get().finishAndRemoveTask();
            }
        });
    }

    /**
     * Configures the dialog to finish the current activity when dismissed.
     */
    public static void setFinishActivityOnDismiss(AlertDialog alertDialog) {
        alertDialog.setOnDismissListener(dialogInterface -> {
            if (activityRef != null && activityRef.get() != null) {
                activityRef.get().finish();
            }
        });
    }

    /**
     * Validates user credentials against the Tecnicos Sheet.
     * CORREO is used as username and CEDULA as password.
     */
    public static HashMap<String, String> validateCredentials(String correoIngresado, String cedulaIngresada) {
        HashMap<String, String> data = new HashMap<>();
        
        if (validateEmptyStringArray(new String[]{correoIngresado, cedulaIngresada})) {
            data.put("code", "2");
            data.put("message", "Error, no puede haber campos vacíos");
            return data;
        }

        try {
            Tabla tecnicos = tecnicosTabla;
            if (tecnicos == null) throw new Exception("Tabla Tecnicos no cargada");

            int idxCorreo = tecnicos.columnIndex("CORREO");
            int idxCedula = tecnicos.columnIndex("CEDULA");
            int idxNombre = tecnicos.columnIndex("NOMBRES");
            int idxAp1 = tecnicos.columnIndex("1 APELLIDO");
            int idxAp2 = tecnicos.columnIndex("2 APELLIDO");
            int idxNumero = tecnicos.columnIndex("NUMERO");
            // Office address the remisiones must be sent to. Deliberately NOT part of the
            // required-column check below: a phone still holding a pre-"CORREO OFICINA"
            // database must keep logging in, it just gets "" and the manual reminder instead.
            int idxCorreoOficina = tecnicos.columnIndex("CORREO OFICINA");
            // The server's consecutivo for this técnico, used as a floor (never as the authority)
            // by getConsecutivoFloor. Superset column for the same reason as CORREO OFICINA: a
            // server rollback or an older stored database must still log everyone in, falling
            // back to purely local numbering (R8 §4).
            int idxConsecutivo = tecnicos.columnIndex("CONSECUTIVO");

            if (idxCorreo == -1 || idxCedula == -1 || idxNombre == -1 || idxNumero == -1) {
                throw new Exception("Error de formato: faltan columnas requeridas en hoja Tecnicos");
            }

            if (idxCorreoOficina == -1) {
                // Without this line a server-side rename of the header would be invisible: every
                // técnico silently loses the office recipient and the only symptom is the manual
                // reminder reappearing, which looks exactly like the intended fallback.
                Log.w(TAG, "Columna 'CORREO OFICINA' ausente en hoja Tecnicos; "
                        + "el correo de la oficina no se completará automáticamente.");
            }

            if (idxConsecutivo == -1) {
                // Same reasoning: without the log, losing the column is invisible. The symptom
                // would be the counter quietly going local-only, which looks identical to normal
                // operation right up until a reinstall mints a duplicate.
                Log.w(TAG, "Columna 'CONSECUTIVO' ausente en hoja Tecnicos; "
                        + "el consecutivo del servidor no se usará como piso.");
            }

            // Scan the whole table instead of returning on the first hit. Two rows sharing BOTH
            // correo and cédula are indistinguishable identities, and quietly taking the first
            // would hand that técnico another row's NUMERO — which is the bodega code, the
            // consecutivo prefix and the office address at once. Rows sharing only the correo are
            // fine and already happen on the server; the cédula separates them.
            int match = -1;
            boolean duplicado = false;
            for (int r = 0; r < tecnicos.rowCount(); r++) {
                if (!correoIngresado.equalsIgnoreCase(tecnicos.value(r, idxCorreo))) continue;
                if (!cedulaIngresada.equals(tecnicos.value(r, idxCedula))) continue;
                if (match == -1) {
                    match = r;
                } else {
                    duplicado = true;
                    break;
                }
            }

            if (duplicado) {
                Log.w(TAG, "Dos filas de Tecnicos comparten CORREO y CEDULA; login ambiguo rechazado.");
                data.put("code", "0");
                data.put("message", "Hay dos técnicos registrados con el mismo correo y la misma "
                        + "cédula. Notifique a la oficina para corregir la base de datos.");
                return data;
            }

            if (match != -1) {
                // 1/2 APELLIDO are not in the required-column check above, so read them
                // defensively: a database missing one must shorten the name, not fail the login
                // with a format error (row.getCell(-1) used to throw into the catch below).
                String ap1 = (idxAp1 == -1) ? "" : tecnicos.value(match, idxAp1);
                String ap2 = (idxAp2 == -1) ? "" : tecnicos.value(match, idxAp2);

                data.put("code", "1");
                data.put("message", "Inicio de sesión exitoso.");
                data.put("nombre", tecnicos.value(match, idxNombre));
                data.put("apellidos", (ap1 + " " + ap2).trim());
                data.put("numero", tecnicos.value(match, idxNumero));
                data.put("cedula", tecnicos.value(match, idxCedula));
                data.put("correoOficina", (idxCorreoOficina == -1) ? ""
                        : tecnicos.value(match, idxCorreoOficina).trim());
                // Blank is a supported state ("never claimed"), exactly like CORREO OFICINA.
                data.put("consecutivoServidor", (idxConsecutivo == -1) ? ""
                        : tecnicos.value(match, idxConsecutivo).trim());
                return data;
            }
        } catch (Exception e) {
            Log.e(TAG, "Credential validation error", e);
            data.put("code", "0");
            data.put("message", "Error de formato de excel, solicite revisión de integridad de datos.");
            return data;
        }
        
        data.put("code", "2");
        data.put("message", "Credenciales incorrectas, intente nuevamente.");
        return data;
    }

    /**
     * The office address this técnico's remisiones must be sent to, read from the
     * "CORREO OFICINA" column of the Tecnicos sheet at login.
     *
     * Returns "" when the column is absent (a database saved before the column existed) or
     * the técnico's cell is blank on the server. Callers must treat "" as "ask the técnico to
     * type it", which is what the app did for everyone before this column existed.
     */
    public static String getCorreoOficina(Map<String, String> userData) {
        if (userData == null) return "";
        String correo = userData.get("correoOficina");
        return correo == null ? "" : correo.trim();
    }

    /**
     * Builds an Intent.EXTRA_EMAIL array from the given addresses, dropping blanks and
     * splitting any argument that carries several addresses in one string.
     *
     * Blanks are dropped because a missing office address (or a cliente with no email on file)
     * must leave that recipient out entirely for the técnico to fill in by hand, never
     * contribute a stray "" that some mail clients render as an invalid recipient chip.
     *
     * The split matters because "CORREO OFICINA" is a human-maintained spreadsheet column: the
     * natural way for the office to add a second address is a comma inside the cell. Passed
     * whole, "a@x.co, b@x.co" becomes ONE invalid recipient that strict clients silently
     * refuse — and the técnico would still confirm "Sí, enviado", which commits the consecutivo
     * and deducts stock, on a mail that never reached the office.
     */
    public static String[] buildEmailRecipients(String... addresses) {
        List<String> recipients = new ArrayList<>();
        if (addresses != null) {
            for (String address : addresses) {
                if (address == null) continue;
                for (String part : address.split("[;,]")) {
                    if (!part.trim().isEmpty()) {
                        recipients.add(part.trim());
                    }
                }
            }
        }
        return recipients.toArray(new String[0]);
    }

    /**
     * Searches for a product info by its reference or name in the Productos sheet.
     */
    public static Map<String, String> getProductoDetails(Context context, String query) {
        try {
            Tabla productos = productosTabla;
            if (productos == null) throw new Exception("Tabla Productos no cargada");
            int idxRef = productos.columnIndex("referencia");
            int idxNombre = productos.columnIndex("nombre");

            if (idxRef == -1 || idxNombre == -1) return null;

            // Indexed, not a scan: this used to walk all 4 800 producto rows on every lookup.
            // Referencia is tried first — the only caller passes one — where the old scan would
            // have taken whichever of the two matched in the earlier row.
            int row = productos.findRow("referencia", query);
            if (row == -1) row = productos.findRow("nombre", query);
            if (row == -1) return null;

            Map<String, String> map = new HashMap<>();
            map.put("referencia", productos.value(row, idxRef));
            map.put("nombre", productos.value(row, idxNombre));
            return map;
        } catch (Exception e) {
            Log.e(TAG, "Error fetching product details", e);
        }
        return null;
    }

    /**
     * Searches for a product name by its reference ID in the Productos sheet.
     * Kept for backward compatibility.
     */
    public static String getProductoName(Context context, String referencia) {
        Map<String, String> details = getProductoDetails(context, referencia);
        return details != null ? details.get("nombre") : null;
    }

    /**
     * Loads the Inventario workbook from storage and sets the global reference.
     */
    public static XSSFWorkbook loadInventarioWb(Context context) {
        try {
            // NOT closed before replacing, unlike the Datos App tables: InventarioBodegaActivity
            // holds the reference it got from getInventarioWb(), so closing here could pull the
            // package out from under a live screen. One bodega is a few hundred rows, so the cost
            // of letting GC handle it is small.
            inventarioWb = loadWorkbookFromFile(context, "Inventario.xlsx");
            return inventarioWb;
        } catch (Exception e) {
            Log.e(TAG, "Error loading Inventario.xlsx", e);
            return null;
        }
    }

    /**
     * Returns the globally loaded Inventory Workbook.
     */
    public static XSSFWorkbook getInventarioWb() {
        return inventarioWb;
    }

    public static synchronized String getLastInventoryFileName(Context context) {
        try {
            File file = new File(context.getFilesDir(), "app_config.json");
            if (!file.exists()) return "Inventario.xlsx";
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                JSONObject json = new JSONObject(new String(data, "UTF-8"));
                return json.optString("lastInventoryName", "Inventario.xlsx");
            }
        } catch (Exception e) {
            return "Inventario.xlsx";
        }
    }

    public static synchronized void setLastInventoryFileName(Context context, String name) {
        try {
            JSONObject json = new JSONObject();
            File file = new File(context.getFilesDir(), "app_config.json");
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] data = new byte[(int) file.length()];
                    fis.read(data);
                    json = new JSONObject(new String(data, "UTF-8"));
                }
            }
            json.put("lastInventoryName", name);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving config", e);
        }
    }

    /**
     * Sets the global Inventory Workbook reference to null.
     */
    public static void setInventarioWbToNull() {
        inventarioWb = null;
    }

    /**
     * Makes these four tables the app's live data and drops the derived autocomplete caches.
     *
     * The ONLY way the tables are replaced, whichever route they arrived by. Replacement is a
     * handful of reference assignments, which is what lets the web sync swap fresh data in without
     * a spinner and without the old "Éxito leyendo Exceles" alert.
     */
    static synchronized void adoptDatosApp(Map<String, Tabla> tablas) {
        if (tablas == null) return;
        Tabla tecnicos = tablas.get("Tecnicos");
        Tabla productos = tablas.get("Productos");
        Tabla clientes = tablas.get("Clientes");
        Tabla tarifas = tablas.get("Tarifas Mantenimiento");
        if (tecnicos == null || productos == null || clientes == null || tarifas == null) {
            // All four or none: a half-swapped database (a fresh Tecnicos against stale Productos)
            // is worse than stale data, and would be invisible to the técnico.
            Log.w(TAG, "Refusing to adopt an incomplete database; keeping current data.");
            return;
        }
        tecnicosTabla = tecnicos;
        productosTabla = productos;
        clientesTabla = clientes;
        tarifasMantenimientoTabla = tarifas;
        cacheListaProductos = null;
        cacheListaFincas = null;
        cacheListaMantenimientos = null;
        cacheListaClientes = null;
        Log.d(TAG, "Datos App adopted: " + tecnicos.rowCount() + " técnicos, "
                + productos.rowCount() + " productos, " + clientes.rowCount() + " clientes, "
                + tarifas.rowCount() + " tarifas.");
    }

    /**
     * Loads the database from the CSV store, migrating a legacy "Datos App.xlsx" the one time it
     * is still the only copy on the phone.
     *
     * The migration exists because older builds stored the workbook and nothing else: a phone
     * updating into this version must not be told to go open the file from the correo again. It
     * parses the workbook once, converts, saves the store and closes the workbook, so the heavy
     * POI path is paid exactly once per phone instead of at every launch.
     *
     * @return true if the tables are now live.
     */
    static synchronized boolean loadDatosApp(Context context) {
        Map<String, Tabla> tablas = DatosAppStore.load(context);
        if (tablas != null && ActualizarDatos.datosAppTablesAreUsable(tablas)) {
            adoptDatosApp(tablas);
            return tecnicosTabla != null;
        }
        if (tablas != null) {
            // Present but unusable — an empty or truncated CSV. Adopting it would leave every
            // login failing with "error de formato" and no way out, so throw the store away: that
            // puts the phone back on the paths that CAN recover (the bootstrap download, which
            // ignores the daily guard, or the legacy workbook below, or the "abra el archivo desde
            // su correo" dialog).
            Log.w(TAG, "Stored database is unusable; discarding it so the next sync can rebuild.");
            DatosAppStore.discard(context);
        }

        File legacy = new File(context.getFilesDir(), DatosAppStore.LEGACY_WORKBOOK);
        if (!legacy.exists()) return false;

        Log.i(TAG, "No store yet; migrating the legacy '" + DatosAppStore.LEGACY_WORKBOOK + "'.");
        XSSFWorkbook wb = null;
        try {
            // Quietly: this runs at app open now, where the dialog would collide with the sync's
            // own "Descargando base de datos". Returning false lands the caller on the download,
            // and initializeData still shows the fatal message if there is nothing to load at all.
            wb = loadWorkbookQuietly(context, DatosAppStore.LEGACY_WORKBOOK);
            if (wb == null) return false;
            Map<String, Tabla> migradas = DatosAppStore.fromWorkbook(wb);
            if (migradas == null) return false;
            closeQuietly(wb);
            wb = null;
            // Held to the same bar as the store: a legacy workbook that is itself empty or
            // mis-shaped must reach the fatal "abra el archivo desde su correo" dialog rather than
            // be adopted into a session where nobody can log in.
            if (!ActualizarDatos.datosAppTablesAreUsable(migradas)) {
                Log.w(TAG, "Legacy workbook is unusable; not adopting it.");
                return false;
            }
            if (!DatosAppStore.save(context, migradas)) {
                // Still usable this session even if the store could not be written; the next
                // launch simply migrates again.
                Log.w(TAG, "Migration could not be persisted; using the data for this session only.");
            }
            adoptDatosApp(migradas);
            return tecnicosTabla != null;
        } catch (Exception e) {
            Log.e(TAG, "Error migrating the legacy workbook", e);
            return false;
        } finally {
            closeQuietly(wb);
        }
    }

    /** Closes a workbook, releasing its OPC package and temp files. Never throws. */
    static void closeQuietly(XSSFWorkbook wb) {
        if (wb == null) return;
        try {
            wb.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing workbook: " + e.getMessage());
        }
    }

    /**
     * Returns the global DataFormatter for consistent cell reading.
     */
    public static DataFormatter getDataFormatter() {
        return dataFormatter;
    }

    /**
     * Resolves the real filename of a Uri.
     */
    public static String getFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result != null ? result : "Desconocido";
    }

    /**
     * Searches for an item in the Inventory Workbook and returns its stock (saldo).
     * Returns -1.0 if the item is not found or sheet is invalid.
     */
    public static synchronized double getInventarioSaldo(String idOrName) {
        if (inventarioWb == null) return -1.0;
        try {
            Sheet sheet = inventarioWb.getSheet("Inventario");
            if (sheet == null) sheet = inventarioWb.getSheetAt(0);
            
            int idxRef = getColumnIndex(sheet, "referencia");
            int idxNombre = getColumnIndex(sheet, "nombre");
            int idxSaldo = getColumnIndex(sheet, "saldo");

            if (idxRef == -1 || idxNombre == -1 || idxSaldo == -1) return -1.0;

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String ref = dataFormatter.formatCellValue(row.getCell(idxRef));
                String nom = dataFormatter.formatCellValue(row.getCell(idxNombre));
                
                if (idOrName.equalsIgnoreCase(ref) || idOrName.equalsIgnoreCase(nom)) {
                    String saldoStr = dataFormatter.formatCellValue(row.getCell(idxSaldo));
                    try {
                        // Handle potential comma as decimal separator in Spanish formats
                        return Double.parseDouble(saldoStr.replace(",", "."));
                    } catch (Exception e) {
                        return 0.0;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching stock from inventarioWb", e);
        }
        return -1.0;
    }

    /**
     * Checks if any string in the array is null or empty.
     */
    public static boolean validateEmptyStringArray(String[] array) {
        for (String s : array) {
            if (s == null || s.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Returns a list of maintenance service descriptions.
     * Uses a LinkedHashSet to remove duplicates while preserving order.
     */
    public static synchronized List<String> getListaMantenimientos() {
        if (cacheListaMantenimientos == null) {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            Tabla tarifas = tarifasMantenimientoTabla;
            int idxDesc = (tarifas == null) ? -1 : tarifas.columnIndex("descripcion");
            if (idxDesc != -1) {
                for (int r = 0; r < tarifas.rowCount(); r++) {
                    String desc = tarifas.value(r, idxDesc);
                    if (!desc.isEmpty()) set.add(desc);
                }
            }
            cacheListaMantenimientos = new ArrayList<>(set);
        }
        // A copy, because callers hand this straight to an ArrayAdapter, which may add to it.
        return new ArrayList<>(cacheListaMantenimientos);
    }

    /**
     * Consolidated search for products and maintenance services.
     * Branches search logic: Products search by Ref, Services search by Description.
     */
    public static Map<String, String> buscarItem(String query, boolean isService) {
        Tabla tabla = isService ? tarifasMantenimientoTabla : productosTabla;
        if (tabla == null || query == null) return null;

        String colNombre = isService ? "descripcion" : "nombre";
        int idxRef = tabla.columnIndex("referencia");
        int idxNom = tabla.columnIndex(colNombre);
        int idxPrecio = tabla.columnIndex("precio_base");

        if (idxRef == -1 || idxNom == -1) return null;

        // For products, handle "Ref - Name" format from autocomplete
        String cleanRef = query.split(" - ")[0].trim();

        // Indexed lookup on the same columns the scan used: a producto by reference, a
        // mantenimiento by its full description.
        int row = isService ? tabla.findRow(colNombre, query) : tabla.findRow("referencia", cleanRef);
        if (row == -1) return null;

        Map<String, String> map = new HashMap<>();
        map.put("id", tabla.value(row, idxRef));
        map.put("nombre", tabla.value(row, idxNom));
        map.put("precio", idxPrecio != -1 ? tabla.value(row, idxPrecio) : "0");
        return map;
    }

    /**
     * Searches for a product by its query string (ID or Name).
     */
    public static Map<String, String> buscarProducto(String query) {
        return buscarItem(query, false);
    }

    /**
     * Searches for a maintenance service by description.
     * Returns unified keys: "id", "nombre", "precio" to match products.
     */
    public static Map<String, String> buscarMantenimiento(String desc) {
        return buscarItem(desc, true);
    }

    /**
     * Returns a list of all Finca IDs formatted as "ID - Name".
     * Uses a LinkedHashSet internally to prevent duplicates.
     */
    public static synchronized List<String> getListaFincas() {
        if (cacheListaFincas == null) {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            Tabla clientes = clientesTabla;
            int idxId = (clientes == null) ? -1 : clientes.columnIndex("finca_id");
            int idxNom = (clientes == null) ? -1 : clientes.columnIndex("finca_nombre");
            if (idxId != -1) {
                for (int r = 0; r < clientes.rowCount(); r++) {
                    String id = clientes.value(r, idxId);
                    String nom = (idxNom != -1) ? clientes.value(r, idxNom) : "";
                    if (!id.isEmpty()) set.add(id + (nom.isEmpty() ? "" : " - " + nom));
                }
            }
            cacheListaFincas = new ArrayList<>(set);
        }
        return new ArrayList<>(cacheListaFincas);
    }

    /**
     * Returns a list of all Product References formatted as "Ref - Name".
     * Uses a LinkedHashSet internally to prevent duplicates.
     */
    public static synchronized List<String> getListaProductos() {
        if (cacheListaProductos == null) {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            Tabla productos = productosTabla;
            int idxRef = (productos == null) ? -1 : productos.columnIndex("referencia");
            int idxNom = (productos == null) ? -1 : productos.columnIndex("nombre");
            if (idxRef != -1) {
                for (int r = 0; r < productos.rowCount(); r++) {
                    String ref = productos.value(r, idxRef);
                    String nom = (idxNom != -1) ? productos.value(r, idxNom) : "";
                    if (!ref.isEmpty()) set.add(ref + (nom.isEmpty() ? "" : " - " + nom));
                }
            }
            cacheListaProductos = new ArrayList<>(set);
        }
        return new ArrayList<>(cacheListaProductos);
    }

    /**
     * Searches for a Finca/Client by its query string (ID or Name).
     */
    public static Map<String, String> buscarCliente(String query) {
        Tabla clientes = clientesTabla;
        if (clientes == null || query == null) return null;
        if (clientes.columnIndex("finca_id") == -1) return null;

        // Clean query if it came from autocomplete "ID - Name"
        String fincaId = query.split(" - ")[0].trim();

        int row = clientes.findRow("finca_id", fincaId);
        if (row == -1) return null;
        // Every column, because the PDF reads finca_nombre, direccion, persona_* and more.
        return clientes.rowAsMap(row);
    }

    // ------------------------------------------------------------------
    // Client search by persona_doc / persona_nombre / finca_nombre.
    // persona_doc is NOT unique (one persona may own several fincas), so the técnico picks the
    // exact finca from the suggestion list; the app always resolves to the unique finca_id.
    // ------------------------------------------------------------------

    /** Case- and accent-insensitive normalization used for client matching. */
    public static String normalizar(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns every client row as a full column map (finca_id, finca_nombre, persona_doc, ...).
     * Backs the client autocomplete; rows without a finca_id or entirely empty are skipped.
     */
    public static synchronized List<Map<String, String>> getListaClientes() {
        if (cacheListaClientes == null) {
            List<Map<String, String>> lista = new ArrayList<>();
            Tabla clientes = clientesTabla;
            if (clientes != null && clientes.columnCount() > 0) {
                for (int r = 0; r < clientes.rowCount(); r++) {
                    Map<String, String> map = clientes.rowAsMap(r);
                    boolean anyValue = false;
                    for (String val : map.values()) {
                        if (!val.isEmpty()) {
                            anyValue = true;
                            break;
                        }
                    }
                    if (anyValue && !map.getOrDefault("finca_id", "").isEmpty()) {
                        lista.add(map);
                    }
                }
            }
            cacheListaClientes = lista;
        }
        // Shallow copy: the row maps themselves are shared, but nothing mutates them.
        return new ArrayList<>(cacheListaClientes);
    }

    /** Exact, unique client resolution by finca_id (the authoritative lookup used at commit). */
    public static Map<String, String> buscarClientePorFincaId(String fincaId) {
        if (fincaId == null || fincaId.trim().isEmpty()) return null;
        return buscarCliente(fincaId.trim());
    }

    // ------------------------------------------------------------------
    // Consecutivo (sequential referral numbering)
    // Format: <técnico número, min 2 digits><6-digit sequence>, e.g. 03000001.
    // Counter is stored per técnico número in app_config.json under "consecutivos".
    // See docs/feature-consecutivo-remision.md.
    // ------------------------------------------------------------------

    /** Zero-pads the técnico prefix (2 digits min) and the 6-digit sequence: (3, 1) -> "03000001". */
    /**
     * Formats a técnico/bodega número to a 2-digit prefix (3 -> "03"). Falls back to the
     * trimmed raw value when it isn't an integer. Single source of truth shared by the
     * consecutivo prefix and the bodega code used by the inventory web sync (R3).
     */
    public static String format2Digitos(String numero) {
        try {
            return String.format(Locale.getDefault(), "%02d", Integer.parseInt(numero.trim()));
        } catch (Exception e) {
            return numero != null ? numero.trim() : "";
        }
    }

    public static String formatConsecutivo(String tecnicoNumero, int seq) {
        return format2Digitos(tecnicoNumero) + String.format(Locale.getDefault(), "%06d", seq);
    }

    /**
     * The format a human sees: "3-000045" — unpadded número, hyphen, 6-digit sequence. This is
     * the office's definitive consecutivo and the shape the server's CONSECUTIVO column holds.
     * <p>
     * Deliberately NOT the same string as {@link #formatConsecutivo}, which stays the storage
     * key: that one names {@code Remision_*.json} and {@code firma_*_*.png}, and
     * {@link #reconcileConsecutivo} rebuilds the local floor by parsing those filenames as
     * {@code <2-digit prefix><exactly 6 digits>}. Reformatting the shared string would silently
     * break the 14-day self-heal on every phone that already has saved remisiones (R8 §6.0).
     */
    public static String formatConsecutivoVisible(String tecnicoNumero, int seq) {
        String numero = tecnicoNumero != null ? tecnicoNumero.trim() : "";
        // Normalize the prefix the same way visibleDeConsecutivo does, which parses it as an int.
        // Without this, a Tecnicos cell reading "03" would print "03-000045" here (the Cambiar
        // Consecutivo dialogs and the office email) while the PDF, the email subject and Reportes
        // printed "3-000045" — two shapes for one number, in the feature whose whole premise is
        // that the office has a single format. Non-numeric números are left exactly as they are.
        try {
            numero = String.valueOf(Integer.parseInt(numero));
        } catch (Exception ignore) {
            // not an integer: keep the trimmed raw value, same as format2Digitos does
        }
        // Locale.US, not getDefault(): this string is a PDF filename, an email subject and the
        // value the office types into the server column, so it has to be ASCII digits on every
        // device. A locale with non-ASCII decimal digits would produce an attachment name and an
        // outbound identifier nobody can read back — seqDe included.
        //
        // formatConsecutivo keeps getDefault() deliberately: its output names the stored
        // Remision_*.json and firma_*.png files, and reconcileConsecutivo rebuilds the floor by
        // parsing those names. Changing its locale would stop it recognising files an affected
        // device had already written, dropping the floor — which is the duplicate case. The
        // pre-existing exposure there is left alone on purpose.
        return numero + "-" + String.format(Locale.US, "%06d", seq);
    }

    /**
     * Reads the sequence back out of either shape — "03000045" and "3-000045" both give 45 — so
     * an internal id and a visible consecutivo are interchangeable as input. The rule is "the
     * last 6 digits", which is also how the server's column is parsed. -1 when unparseable.
     */
    public static int seqDe(String consecutivo) {
        if (consecutivo == null) return -1;
        consecutivo = consecutivo.trim();
        if (consecutivo.length() < 6) return -1;
        try {
            return Integer.parseInt(consecutivo.substring(consecutivo.length() - 6));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Converts a consecutivo to the visible format, taking the número from the id itself: the
     * internal id is {@code <%02d prefix><%06d sequence>}, so the prefix is already in the string
     * and no session is needed. That is what lets screens holding only a stored remisión — Reportes,
     * Modificar — format one.
     * <p>
     * Falls back to the input unchanged whenever it cannot do better: a malformed value, a bare
     * 6-digit id with no prefix, or a non-numeric prefix (which {@link #format2Digitos} produces
     * when a técnico número is not an integer). A display path must never fail or print an empty
     * consecutivo — the worst case is the old shape.
     * <p>
     * The rule across the app: **files and keys keep the internal id, everything a human reads
     * goes through here** (R8 §6.0).
     */
    public static String visibleDeConsecutivo(String consecutivo) {
        int seq = seqDe(consecutivo);
        if (seq < 0) return consecutivo;
        String prefijo = consecutivo.trim();
        prefijo = prefijo.substring(0, prefijo.length() - 6).replace("-", "").trim();
        if (prefijo.isEmpty()) return consecutivo;
        try {
            return formatConsecutivoVisible(String.valueOf(Integer.parseInt(prefijo)), seq);
        } catch (Exception e) {
            return consecutivo;
        }
    }

    /** Reads app_config.json into a JSONObject (empty object if missing/unreadable). */
    private static JSONObject readAppConfig(Context context) {
        try {
            File file = new File(context.getFilesDir(), "app_config.json");
            if (!file.exists()) return new JSONObject();
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                return new JSONObject(new String(data, "UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading app_config.json", e);
            return new JSONObject();
        }
    }

    /** Persists a JSONObject back to app_config.json. */
    private static void writeAppConfig(Context context, JSONObject json) {
        try {
            File file = new File(context.getFilesDir(), "app_config.json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing app_config.json", e);
        }
    }

    /** The 2-digit bodega code the inventory was last synced for (R3). "" if never synced. */
    public static synchronized String getBodegaCodigo(Context context) {
        return readAppConfig(context).optString("bodegaCodigo", "");
    }

    public static synchronized void setBodegaCodigo(Context context, String codigo) {
        JSONObject json = readAppConfig(context);
        try {
            json.put("bodegaCodigo", codigo);
        } catch (Exception e) {
            Log.e(TAG, "Error saving bodegaCodigo", e);
        }
        writeAppConfig(context, json);
    }

    /** Local date (yyyy-MM-dd) of the last completed daily sync (R4). "" if never. */
    public static synchronized String getLastSyncDate(Context context) {
        return readAppConfig(context).optString("lastSyncDate", "");
    }

    public static synchronized void setLastSyncDate(Context context, String date) {
        JSONObject json = readAppConfig(context);
        try {
            json.put("lastSyncDate", date);
        } catch (Exception e) {
            Log.e(TAG, "Error saving lastSyncDate", e);
        }
        writeAppConfig(context, json);
    }

    /**
     * Local date (yyyy-MM-dd) of the last completed "Datos App.xlsx" web sync (R5). "" if never.
     * Deliberately independent from {@link #getLastSyncDate}: the bodega CSV and the database
     * are different files on different endpoints, so a failure of one must not consume the
     * other's daily attempt.
     */
    public static synchronized String getLastDatosAppSyncDate(Context context) {
        return readAppConfig(context).optString("lastDatosAppSyncDate", "");
    }

    public static synchronized void setLastDatosAppSyncDate(Context context, String date) {
        JSONObject json = readAppConfig(context);
        try {
            json.put("lastDatosAppSyncDate", date);
        } catch (Exception e) {
            Log.e(TAG, "Error saving lastDatosAppSyncDate", e);
        }
        writeAppConfig(context, json);
    }

    /** Returns the last-used sequence stored for this técnico (0 if none set). */
    public static synchronized int getStoredConsecutivo(Context context, String tecnicoNumero) {
        try {
            File file = new File(context.getFilesDir(), "app_config.json");
            if (!file.exists()) return 0;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                JSONObject json = new JSONObject(new String(data, "UTF-8"));
                JSONObject cons = json.optJSONObject("consecutivos");
                if (cons == null) return 0;
                return cons.optInt(tecnicoNumero, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading consecutivo counter", e);
            return 0;
        }
    }

    /** Persists the last-used sequence for this técnico. */
    public static synchronized void setStoredConsecutivo(Context context, String tecnicoNumero, int lastUsedSeq) {
        try {
            File file = new File(context.getFilesDir(), "app_config.json");
            JSONObject json = new JSONObject();
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] data = new byte[(int) file.length()];
                    fis.read(data);
                    json = new JSONObject(new String(data, "UTF-8"));
                }
            }
            JSONObject cons = json.optJSONObject("consecutivos");
            if (cons == null) cons = new JSONObject();
            cons.put(tecnicoNumero, lastUsedSeq);
            json.put("consecutivos", cons);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving consecutivo counter", e);
        }
    }

    /**
     * The highest sequence the server has acknowledged for this técnico — the whole outbox, in
     * one integer. That is what the idempotent {@code max} on the server buys us: there is no
     * queue of pending numbers to keep, only "highest used" vs "highest confirmed" (R8 §3).
     * <p>
     * Lives in {@code app_config.json} and NOT in a {@code Remision_*} file, because
     * {@link #cleanOldRemisiones} deletes those after 14 days and would eat the state.
     */
    public static synchronized int getSeqConfirmado(Context context, String tecnicoNumero) {
        if (tecnicoNumero == null) return 0;
        JSONObject conf = readAppConfig(context).optJSONObject("consecutivosConfirmados");
        return conf == null ? 0 : conf.optInt(tecnicoNumero, 0);
    }

    /** Records what the server answered. Only ever raises, like everything else in R8. */
    public static synchronized void setSeqConfirmado(Context context, String tecnicoNumero, int seq) {
        if (tecnicoNumero == null) return;
        JSONObject json = readAppConfig(context);
        try {
            JSONObject conf = json.optJSONObject("consecutivosConfirmados");
            if (conf == null) conf = new JSONObject();
            if (seq > conf.optInt(tecnicoNumero, 0)) {
                conf.put(tecnicoNumero, seq);
                json.put("consecutivosConfirmados", conf);
                writeAppConfig(context, json);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving confirmed consecutivo", e);
        }
    }

    /**
     * Raises the stored counter to {@code seq} if it is higher, comparing and writing inside ONE
     * critical section. {@code getStoredConsecutivo} and {@code setStoredConsecutivo} are each
     * synchronized separately, so a caller that reads, decides, then writes can be interleaved by
     * the UI thread's own `commitConsecutivo` and end up *lowering* the counter with a value that
     * was already stale when it was read. The background claim is exactly such a caller.
     *
     * @return the counter's value afterwards.
     */
    public static synchronized int subirStoredConsecutivo(Context context, String tecnicoNumero, int seq) {
        int actual = getStoredConsecutivo(context, tecnicoNumero);
        if (seq > actual) {
            setStoredConsecutivo(context, tecnicoNumero, seq);
            return seq;
        }
        return actual;
    }

    /** True when this técnico has consumed a number the server has not acknowledged yet. */
    public static synchronized boolean hayConsecutivoPendiente(Context context, String tecnicoNumero) {
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) return false;
        return getStoredConsecutivo(context, tecnicoNumero) > getSeqConfirmado(context, tecnicoNumero);
    }

    /**
     * The reinstall margin (R8 §8). A device with no local history cannot know what it consumed
     * offline before being wiped, so it does not ask for {@code server + 1} — it jumps to the
     * next multiple of this, deliberately burning numbers to turn a possible duplicate into a
     * visible gap. A jump to a round hundred is unmistakably a replaced phone; a jump of 7 would
     * be indistinguishable from 7 lost remisiones, which is the thing the series exists to show.
     * <p>
     * Policy constant: the owner can set it to 20 (next multiple of 20) without touching logic.
     * See the locked decision in the plan's §11 before lowering it.
     */
    public static final int MARGEN_REINSTALACION = 100;

    /**
     * {@code floor} -> the sequence a fresh claim should ask for, or <b>-1 when the 6-digit series
     * is exhausted</b> and there is no number left to hand out.
     * <p>
     * The result is always strictly greater than the floor. That is the property the whole margin
     * rests on: a value equal to the floor would re-mint a number the office already holds, which
     * is the one unrecoverable outcome in this feature. So when the next margin block would
     * overflow 6 digits this does NOT clamp to 999999 — it falls back to {@code floor + 1} while
     * that still fits, and only then gives up. Unreachable for decades (the busiest técnico is in
     * the low thousands), but a clamp here would have been a silent duplicate.
     */
    public static int seqConMargen(int floor) {
        if (floor < 0) floor = 0;
        if (floor >= 999999) {
            Log.e(TAG, "Serie de consecutivos agotada: el piso ya es " + floor);
            return -1;
        }
        int seq = (MARGEN_REINSTALACION <= 1)
                ? floor + 1
                : (floor / MARGEN_REINSTALACION + 1) * MARGEN_REINSTALACION;
        if (seq > 999999) {
            // No room for a whole margin block; take the smallest number that is still safe.
            Log.w(TAG, "Sin espacio para el margen (" + seq + "); se usa el siguiente consecutivo.");
            seq = floor + 1;
        }
        return seq;
    }

    /**
     * A stable per-install id, sent as {@code device=} on a claim so the office can see two
     * devices claiming for one técnico — the §9 phone-swap hazard, otherwise invisible to
     * everyone. Eight hex characters: enough to tell installs apart, short enough for a URL,
     * and not derived from anything identifying the phone or the person.
     */
    public static synchronized String getInstallId(Context context) {
        JSONObject json = readAppConfig(context);
        String id = json.optString("installId", "");
        if (!id.isEmpty()) return id;
        id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try {
            json.put("installId", id);
            writeAppConfig(context, json);
        } catch (Exception e) {
            Log.e(TAG, "Error saving installId", e);
        }
        return id;
    }

    /**
     * The sequence the server holds for this técnico, from the CONSECUTIVO column captured at
     * login. -1 when there is nothing usable, which is a supported state, never an error:
     * a blank cell ("never claimed"), an absent column (older database or a server rollback),
     * or a malformed value.
     * <p>
     * The prefix is checked against this técnico's número before the value is accepted, so
     * "3-000045" and "03000045" both work for número 3 while another técnico's row can never be
     * inherited. A bare "000045" with no prefix is rejected for the same reason — ownership
     * cannot be verified (R8 §6).
     */
    public static int getSeqServidor(Map<String, String> userData) {
        if (userData == null) return -1;
        String raw = userData.get("consecutivoServidor");
        String numero = userData.get("numero");
        if (raw == null || numero == null) return -1;
        raw = raw.trim();
        if (raw.length() <= 6 || numero.trim().isEmpty()) return -1;

        int seq = seqDe(raw);
        if (seq < 0) return -1;

        String prefijo = raw.substring(0, raw.length() - 6).replace("-", "").trim();
        try {
            if (Integer.parseInt(prefijo) != Integer.parseInt(numero.trim())) {
                Log.w(TAG, "CONSECUTIVO del servidor ('" + raw + "') no corresponde al técnico "
                        + numero.trim() + "; se ignora.");
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
        return seq;
    }

    /**
     * The §4 floor: {@code max(local stored, local files, server)}. The single place the three
     * inputs meet, and the only one that should ever decide what the next number is built on.
     * <p>
     * It only ever raises, so a stale server read — the value can be a day old, and badly stale
     * after a wipe — can never pull the counter backwards and mint a duplicate. The result is
     * written back through {@link #setStoredConsecutivo} immediately, so the next launch keeps it
     * even with no network.
     */
    public static synchronized int getConsecutivoFloor(Context context, Map<String, String> userData) {
        String numero = (userData == null) ? null : userData.get("numero");
        if (numero == null || numero.trim().isEmpty()) return 0;

        int local = getStoredConsecutivo(context, numero);
        int archivos = reconcileConsecutivo(context, numero);
        int servidor = getSeqServidor(userData);

        int floor = Math.max(local, Math.max(archivos, servidor));
        if (floor < 0) floor = 0;
        // Raise an EXISTING counter; never create one. setStoredConsecutivo creates the
        // consecutivos.<numero> key and isConsecutivoInicializado tests key presence, so
        // persisting here on a device with no counter would silently satisfy the fresh-install
        // gate — after which the phone mints from the office's seed + 1 and duplicates the
        // técnico's whole history. Reading the floor must never be what establishes a counter.
        if (floor > local && tieneContadorLocal(context, numero)) {
            setStoredConsecutivo(context, numero, floor);
        }
        return floor;
    }

    /**
     * Local evidence that this device already has a counter: the stored key, or surviving
     * {@code Remision_*.json} files. Deliberately separate from
     * {@link #isConsecutivoInicializado}, which Phase 2 will widen to accept a server value —
     * {@link #getConsecutivoFloor} must keep asking this narrower question, or it would again
     * become the thing that establishes the counter it is only supposed to raise.
     */
    private static synchronized boolean tieneContadorLocal(Context context, String tecnicoNumero) {
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) return false;
        JSONObject cons = readAppConfig(context).optJSONObject("consecutivos");
        if (cons != null && cons.has(tecnicoNumero)) return true;
        return reconcileConsecutivo(context, tecnicoNumero) > 0;
    }

    /**
     * True once a counter has been established for this técnico. Uses key presence (not value),
     * so a técnico who seeds "never sent" (sequence 0) still counts as configured.
     * <p>
     * <b>Local evidence only — and it stays that way.</b> R8 §6's table had a server value also
     * counting as "initialized", to retire the manual re-seed flow. Phase 2 retired that flow a
     * safer way: the gate in {@code IngresarRemisionActivity} now answers "no local counter" with
     * a blocking claim that applies the §8 margin, so nobody is sent to the manual screen anyway.
     * <p>
     * Do <b>not</b> add {@code getSeqServidor(userData) >= 0} here. It would make this return true
     * on a wiped phone, skipping that claim, and the phone would take the server's value + 1 —
     * re-minting every number consumed offline before the wipe. Having no local history is exactly
     * the state where those numbers are unknowable, which is why the margin exists.
     */
    public static synchronized boolean isConsecutivoInicializado(Context context, Map<String, String> userData) {
        String tecnicoNumero = (userData == null) ? null : userData.get("numero");
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) return false;
        // Key presence (not value) plus the self-heal from surviving remisión files.
        return tieneContadorLocal(context, tecnicoNumero);
    }

    /**
     * Self-healing floor: the highest sequence among this técnico's local Remisión files
     * (protects against a lost/rolled-back counter while the referral files survive).
     */
    public static synchronized int reconcileConsecutivo(Context context, String tecnicoNumero) {
        String prefix;
        try {
            prefix = String.format(Locale.getDefault(), "%02d", Integer.parseInt(tecnicoNumero.trim()));
        } catch (Exception e) {
            prefix = tecnicoNumero != null ? tecnicoNumero.trim() : "";
        }
        final String fPrefix = prefix;
        int max = 0;
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith("Remision_" + fPrefix) && name.endsWith(".json"));
        if (files == null) return 0;
        for (File f : files) {
            String id = f.getName().replace("Remision_", "").replace(".json", "");
            // Only new-format ids: <prefix><exactly 6 digits>
            if (id.length() == fPrefix.length() + 6 && id.startsWith(fPrefix)) {
                String suffix = id.substring(fPrefix.length());
                try {
                    int s = Integer.parseInt(suffix);
                    if (s > max) max = s;
                } catch (Exception ignore) {}
            }
        }
        return max;
    }

    /**
     * The sequences this técnico has actually committed above {@code desde}, read from the saved
     * {@code Remision_*.json} files — i.e. real evidence of numbers issued, in ascending order.
     * <p>
     * This exists because the claim's optional {@code usados=} list must not be inferred from the
     * counter: {@link #getConsecutivoFloor} can raise the stored value without a single remisión
     * being sent (an office override typed into the server column, or another install's claim), so
     * enumerating the integer gap would report numbers this phone never issued into the audit log
     * that is supposed to prove what it did. Bounded by the 14-day sweep, which is fine — a short
     * list is accurate, and the list is an enrichment, never the counter itself.
     */
    public static synchronized List<Integer> getConsecutivosUsados(Context context,
                                                                   String tecnicoNumero, int desde) {
        List<Integer> usados = new ArrayList<>();
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) return usados;
        final String prefix = format2Digitos(tecnicoNumero);
        File[] files = context.getFilesDir().listFiles((d, name) ->
                name != null && name.startsWith("Remision_" + prefix) && name.endsWith(".json"));
        if (files == null) return usados;
        for (File f : files) {
            String id = f.getName().replace("Remision_", "").replace(".json", "");
            if (id.length() != prefix.length() + 6 || !id.startsWith(prefix)) continue;
            int seq = seqDe(id);
            if (seq > desde) usados.add(seq);
        }
        java.util.Collections.sort(usados);
        return usados;
    }

    /**
     * Returns the next consecutivo for this técnico WITHOUT consuming it.
     * A missing técnico número is a fatal, unrecoverable configuration error.
     */
    public static String peekProximoConsecutivo(Context context, Map<String, String> userData) {
        String tecnicoNumero = (userData == null) ? null : userData.get("numero");
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) {
            showFatalError(context, "El técnico no tiene un número asignado en 'Datos App.xlsx'. "
                    + "Notifique a la oficina. La aplicación se cerrará.");
            return null;
        }
        // Takes the shared §4 floor rather than a local-only max, so the server's value can raise
        // it. Takes userData, not just the número, deliberately: there is no overload that can
        // bypass the floor by accident.
        return formatConsecutivo(tecnicoNumero, getConsecutivoFloor(context, userData) + 1);
    }

    /**
     * Consumes the given consecutivo by persisting its sequence as the last used for this técnico.
     * Call only after a remisión has been successfully sent.
     */
    public static synchronized void commitConsecutivo(Context context, String tecnicoNumero, String consecutivo) {
        if (tecnicoNumero == null || consecutivo == null) return;
        try {
            int seq = seqDe(consecutivo);
            if (seq < 0) {
                Log.e(TAG, "Error committing consecutivo: unparseable '" + consecutivo + "'");
                return;
            }
            if (seq > getStoredConsecutivo(context, tecnicoNumero)) {
                setStoredConsecutivo(context, tecnicoNumero, seq);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error committing consecutivo", e);
        }
    }

    /**
     * Loads the car inventory from a JSON file in internal storage.
     */
    public static synchronized HashMap<String, Integer> getCarInventory(Context context) {
        HashMap<String, Integer> inventory = new HashMap<>();
        File file = new File(context.getFilesDir(), "InventarioCarro.json");
        if (!file.exists()) return inventory;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            String jsonStr = new String(data, "UTF-8");
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                inventory.put(key, json.getInt(key));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading car inventory", e);
        }
        return inventory;
    }

    /**
     * Saves the car inventory to a JSON file in internal storage.
     */
    public static synchronized void saveCarInventory(Context context, HashMap<String, Integer> inventory) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            File file = new File(context.getFilesDir(), "InventarioCarro.json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving car inventory", e);
        }
    }

    /**
     * Saves remission metadata and items to a JSON file.
     */
    public static synchronized void saveRemisionData(Context context, String consecutivo, Map<String, String> fincaData, List<Map<String, String>> items, Map<String, String> tecnicoData, boolean restored) {
        saveRemisionData(context, consecutivo, fincaData, items, tecnicoData, restored, null);
    }

    /**
     * As above, also recording the observaciones the técnico typed.
     *
     * The observaciones used to live only in the generated PDF, so Editar Remisión had nothing to
     * show and silently replaced them with whatever was retyped (or with "Sin observaciones" if the
     * field was left alone). Persisting them lets the edit screen pre-fill the original text.
     *
     * @param observaciones may be null for a caller that has none; the key is then omitted and
     *                      readers fall back to "", which is the pre-existing behavior.
     */
    public static synchronized void saveRemisionData(Context context, String consecutivo, Map<String, String> fincaData, List<Map<String, String>> items, Map<String, String> tecnicoData, boolean restored, String observaciones) {
        try {
            JSONObject json = new JSONObject();
            json.put("consecutivo", consecutivo);
            json.put("fecha", System.currentTimeMillis());
            json.put("restored", restored);
            if (observaciones != null) {
                json.put("observaciones", observaciones);
            }

            if (fincaData != null) {
                JSONObject fincaJson = new JSONObject(fincaData);
                json.put("fincaData", fincaJson);
            }
            
            org.json.JSONArray itemsArray = new org.json.JSONArray();
            if (items != null) {
                for (Map<String, String> item : items) {
                    itemsArray.put(new JSONObject(item));
                }
            }
            json.put("items", itemsArray);
            
            if (tecnicoData != null) {
                JSONObject tecnicoJson = new JSONObject(tecnicoData);
                json.put("tecnicoData", tecnicoJson);
            }
            
            File file = new File(context.getFilesDir(), "Remision_" + consecutivo + ".json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving remission JSON", e);
        }
    }


    /**
     * Retrieves remission data from JSON.
     */
    public static JSONObject loadRemisionData(Context context, String consecutivo) {
        File file = new File(context.getFilesDir(), "Remision_" + consecutivo + ".json");
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new JSONObject(new String(data, "UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "Error loading remission JSON", e);
            return null;
        }
    }

    public static List<Map<String, String>> getListaRemisiones(Context context) {
        List<Map<String, String>> lista = new ArrayList<>();
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith("Remision_") && name.endsWith(".json"));
        if (files == null) return lista;

        for (File file : files) {
            try {
                String consecutivo = file.getName().replace("Remision_", "").replace(".json", "");
                JSONObject json = loadRemisionData(context, consecutivo);
                if (json != null) {
                    Map<String, String> map = new HashMap<>();
                    map.put("consecutivo", consecutivo);
                    JSONObject finca = json.getJSONObject("fincaData");
                    map.put("finca_nombre", finca.optString("finca_nombre", finca.optString("direccion", "N/A")));
                    map.put("fecha", String.valueOf(json.getLong("fecha")));
                    lista.add(map);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing remission for list", e);
            }
        }
        return lista;
    }

    public static List<JSONObject> getAllRemisionesData(Context context) {
        List<JSONObject> lista = new ArrayList<>();
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.startsWith("Remision_") && name.endsWith(".json"));
        if (files == null) return lista;

        for (File file : files) {
            try {
                String consecutivo = file.getName().replace("Remision_", "").replace(".json", "");
                JSONObject json = loadRemisionData(context, consecutivo);
                if (json != null) {
                    lista.add(json);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading remission for report", e);
            }
        }
        return lista;
    }

    /**
     * Adds items back to stock (used during modification).
     * Now supports adding even if item doesn't exist in warehouse (creates a new row).
     */
    public static synchronized void restaurarStock(Context context, List<Map<String, String>> items) {
        /* Reenable later if strictly needed
        // 1. Restore to Car Inventory
        HashMap<String, Integer> carInv = getCarInventory(context);
        for (Map<String, String> item : items) {
            // SKIP maintenance items
            if ("mantenimiento".equals(item.get("tipo"))) continue;

            String ref = item.get("id");
            if (ref == null || ref.isEmpty()) continue;
            
            String qtyStr = item.get("cantidad");
            if (qtyStr == null) continue;
            
            int qty = Integer.parseInt(qtyStr);
            Integer current = carInv.get(ref);
            carInv.put(ref, (current != null ? current : 0) + qty);
        }
        saveCarInventory(context, carInv);
        */

        // 2. Restore to Warehouse Inventory (Inventario.xlsx)
        if (inventarioWb == null) loadInventarioWb(context);
        
        if (inventarioWb != null) {
            try {
                Sheet sheet = inventarioWb.getSheet("Inventario");
                if (sheet == null) sheet = inventarioWb.getSheetAt(0);

                int idxRef = getColumnIndex(sheet, "referencia");
                int idxSaldo = getColumnIndex(sheet, "saldo");
                int idxNombre = getColumnIndex(sheet, "nombre");
                if (idxRef == -1 || idxSaldo == -1) return;

                boolean modified = false;
                for (Map<String, String> item : items) {
                    // SKIP maintenance items
                    if ("mantenimiento".equals(item.get("tipo"))) continue;

                    String refToRestore = item.get("id");
                    String qtyStr = item.get("cantidad");
                    String nameToRestore = item.get("nombre");
                    if (refToRestore == null || qtyStr == null) continue;
                    
                    int qtyToRestore = Integer.parseInt(qtyStr);

                    boolean found = false;
                    for (Row row : sheet) {
                        if (row.getRowNum() == 0) continue;
                        String refFile = dataFormatter.formatCellValue(row.getCell(idxRef));
                        if (refToRestore.equalsIgnoreCase(refFile)) {
                            Cell saldoCell = row.getCell(idxSaldo);
                            double currentSaldo = 0;
                            try {
                                String val = dataFormatter.formatCellValue(saldoCell).replace(",", ".");
                                if (!val.isEmpty()) currentSaldo = Double.parseDouble(val);
                            } catch (Exception e) {}
                            
                            double nuevoSaldo = currentSaldo + qtyToRestore;
                            if (nuevoSaldo == 0) {
                                int rowNum = row.getRowNum();
                                sheet.removeRow(row);
                                if (rowNum < sheet.getLastRowNum()) {
                                    sheet.shiftRows(rowNum + 1, sheet.getLastRowNum(), -1);
                                }
                            } else {
                                saldoCell.setCellValue(nuevoSaldo);
                            }
                            found = true;
                            modified = true;
                            break;
                        }
                    }

                    if (!found) {
                        // Create new row for previously unknown item
                        int lastRowNum = sheet.getLastRowNum();
                        Row newRow = sheet.createRow(lastRowNum + 1);
                        newRow.createCell(idxRef).setCellValue(refToRestore);
                        if (idxNombre != -1) newRow.createCell(idxNombre).setCellValue(nameToRestore);
                        newRow.createCell(idxSaldo).setCellValue(qtyToRestore);
                        modified = true;
                    }
                }
                if (modified) saveWorkbook(context, inventarioWb, "Inventario.xlsx");
            } catch (Exception e) {
                Log.e(TAG, "Error restoring warehouse stock", e);
            }
        }
    }

    /**
     * Deducts quantities from the warehouse inventory (Inventario.xlsx).
     * Now supports negative stock if item doesn't exist or balance is insufficient.
     */
    public static synchronized void updateWarehouseStock(Context context, List<Map<String, String>> items) {
        if (inventarioWb == null) loadInventarioWb(context);
        if (inventarioWb == null) return;
        try {
            Sheet sheet = inventarioWb.getSheet("Inventario");
            if (sheet == null) sheet = inventarioWb.getSheetAt(0);

            int idxRef = getColumnIndex(sheet, "referencia");
            int idxSaldo = getColumnIndex(sheet, "saldo");
            int idxNombre = getColumnIndex(sheet, "nombre");

            if (idxRef == -1 || idxSaldo == -1) return;

            boolean modified = false;
            for (Map<String, String> item : items) {
                // SKIP maintenance items
                if ("mantenimiento".equals(item.get("tipo"))) continue;

                String refToDeduct = item.get("id");
                String nameToDeduct = item.get("nombre");
                if (refToDeduct == null || refToDeduct.isEmpty()) continue;
                
                int qtyToDeduct;
                try {
                    qtyToDeduct = Integer.parseInt(item.get("cantidad"));
                } catch (Exception e) { continue; }

                boolean found = false;
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) continue;
                    String refFile = dataFormatter.formatCellValue(row.getCell(idxRef));
                    if (refToDeduct.equalsIgnoreCase(refFile)) {
                        Cell saldoCell = row.getCell(idxSaldo);
                        double currentSaldo = 0;
                        try {
                            String val = dataFormatter.formatCellValue(saldoCell).replace(",", ".");
                            if (!val.isEmpty()) currentSaldo = Double.parseDouble(val);
                        } catch (Exception e) { }
                        
                        double nuevoSaldo = currentSaldo - qtyToDeduct;
                        if (nuevoSaldo == 0) {
                            int rowNum = row.getRowNum();
                            sheet.removeRow(row);
                            if (rowNum < sheet.getLastRowNum()) {
                                sheet.shiftRows(rowNum + 1, sheet.getLastRowNum(), -1);
                            }
                        } else {
                            saldoCell.setCellValue(nuevoSaldo);
                        }
                        found = true;
                        modified = true;
                        break;
                    }
                }

                if (!found) {
                    // Create new row with negative stock for previously unknown item
                    int lastRowNum = sheet.getLastRowNum();
                    Row newRow = sheet.createRow(lastRowNum + 1);
                    newRow.createCell(idxRef).setCellValue(refToDeduct);
                    if (idxNombre != -1) newRow.createCell(idxNombre).setCellValue(nameToDeduct);
                    newRow.createCell(idxSaldo).setCellValue(-qtyToDeduct);
                    modified = true;
                }
            }

            if (modified) {
                saveWorkbook(context, inventarioWb, "Inventario.xlsx");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating warehouse stock", e);
        }
    }

    /**
     * Deducts quantities from the car inventory (InventarioCarro.json).
     */
    public static synchronized void updateCarStock(Context context, List<Map<String, String>> items) {
        /* Reenable later if strictly needed
        HashMap<String, Integer> carInv = getCarInventory(context);
        boolean modified = false;
        for (Map<String, String> item : items) {
            // SKIP maintenance items
            if ("mantenimiento".equals(item.get("tipo"))) continue;

            String ref = item.get("id");
            if (ref == null) continue;
            
            if (carInv.containsKey(ref)) {
                int qtyToDeduct;
                try {
                    qtyToDeduct = Integer.parseInt(item.get("cantidad"));
                } catch (Exception e) { continue; }
                
                Integer currentQty = carInv.get(ref);
                int newQty = Math.max(0, (currentQty != null ? currentQty : 0) - qtyToDeduct);
                if (newQty == 0) {
                    carInv.remove(ref);
                } else {
                    carInv.put(ref, newQty);
                }
                modified = true;
            }
        }
        if (modified) {
            saveCarInventory(context, carInv);
        }
        */
    }

    private static int getColumnIndex(Sheet sheet, String colName) {
        if (sheet == null) return -1;
        Row header = sheet.getRow(0);
        if (header == null) return -1;
        for (Cell cell : header) {
            if (cell.toString().trim().equalsIgnoreCase(colName)) return cell.getColumnIndex();
        }
        return -1;
    }

    /**
     * Inserts an image into a specific cell of the workbook.
     */
    public static void insertImageToWorkbook(Workbook wb, byte[] imageBytes, int sheetIndex, int row, int col) {
        try {
            int pictureIdx = wb.addPicture(imageBytes, Workbook.PICTURE_TYPE_PNG);
            CreationHelper helper = wb.getCreationHelper();
            Sheet sheet = wb.getSheetAt(sheetIndex);
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(col);
            anchor.setRow1(row);
            
            Picture pict = drawing.createPicture(anchor, pictureIdx);
            // pict.resize(0.5); // REMOVED: This causes ClassNotFoundException for java.awt.Dimension on Android
            Log.d(TAG, "Signature image inserted successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error inserting image to workbook", e);
        }
    }

    /**
     * Returns a copy of the bitmap with near-neutral light pixels (the off-white/gray JPEG
     * background and interior negative space) made transparent, so the logo blends into whatever
     * white surface it's drawn on (PDF page or screen) instead of showing a mismatched box.
     */
    public static Bitmap makeBackgroundTransparent(Bitmap src) {
        if (src == null) return null;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth(), h = out.getHeight();
        int[] pixels = new int[w * h];
        out.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int mn = Math.min(r, Math.min(g, b));
            int mx = Math.max(r, Math.max(g, b));
            if (mn >= 230 && (mx - mn) <= 18) { // near-neutral light -> treat as background
                pixels[i] = 0x00000000;
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    public static synchronized File generarPDFRemision(Context context, String consecutivo, Map<String, String> fincaData, List<Map<String, String>> items, Map<String, String> tecnicoData, Bitmap firmaTecnico, Bitmap firmaCliente, String observaciones) {
        // Everything a human reads carries the office's format ("3-000045"); `consecutivo` itself
        // stays the internal id because it is the storage key (R8 §6.0). The PDF is named with the
        // visible form on purpose: it becomes the attachment name the office receives, nothing
        // ever looks this file up by name, and cleanOldRemisiones still sweeps it by prefix.
        String consecutivoVisible = visibleDeConsecutivo(consecutivo);
        PdfDocument document = new PdfDocument();
        int itemsPerPage = 10;
        int totalItems = items.size();

        // Layout anchors for the footer-overlap guard (Feature 4)
        final float FOOTER_TOP = 750f;
        final float FIRST_ITEM_Y = 267f;   // baseline of the first item row (after logo band + company + finca + table header)
        final float ROW_HEIGHT = 20f;
        final float CLUSTER_HEIGHT = 275f; // height of the totals + signatures + observaciones block

        int contentPages = (int) Math.ceil((double) totalItems / itemsPerPage);
        if (contentPages == 0) contentPages = 1;

        // Items rendered on the last content page
        int itemsOnLastPage = totalItems - (contentPages - 1) * itemsPerPage;
        float yAfterLastItems = FIRST_ITEM_Y + itemsOnLastPage * ROW_HEIGHT;

        // Guard: if the bottom cluster would collide with the footer, push it to its own final page
        boolean clusterOnOwnPage = (yAfterLastItems + CLUSTER_HEIGHT) > FOOTER_TOP;
        int totalPages = contentPages + (clusterOnOwnPage ? 1 : 0);

        Paint paint = new Paint();
        Paint titlePaint = new Paint();
        titlePaint.setTextSize(18f);
        titlePaint.setFakeBoldText(true);

        // Header logos (decoded once, drawn on every page): Conagro left, Delaval right.
        Bitmap logoConagro = null, logoDelaval = null;
        try {
            logoConagro = makeBackgroundTransparent(BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_conagro));
            logoDelaval = makeBackgroundTransparent(BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_delaval));
        } catch (Exception e) {
            Log.e(TAG, "Error loading PDF header logos", e);
        }

        for (int p = 0; p < totalPages; p++) {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, p + 1).create(); // A4
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            float x = 50, y = 90; // header text starts below the logo band

        // Header logos (top band, drawn on every page): Conagro anchored left, Delaval right.
        if (logoConagro != null) {
            float maxW = 110, maxH = 55;
            float scale = Math.min(maxW / logoConagro.getWidth(), maxH / logoConagro.getHeight());
            float w = logoConagro.getWidth() * scale, h = logoConagro.getHeight() * scale;
            canvas.drawBitmap(logoConagro, null, new android.graphics.RectF(40, 20, 40 + w, 20 + h), paint);
        }
        if (logoDelaval != null) {
            float maxW = 110, maxH = 55;
            float scale = Math.min(maxW / logoDelaval.getWidth(), maxH / logoDelaval.getHeight());
            float w = logoDelaval.getWidth() * scale, h = logoDelaval.getHeight() * scale;
            float right = 555;
            canvas.drawBitmap(logoDelaval, null, new android.graphics.RectF(right - w, 20, right, 20 + h), paint);
        }

        // Header (on every page)
        canvas.drawText("CONAGRO S.A.", x, y, titlePaint);
        y += 20;
        paint.setTextSize(9f);
        canvas.drawText("EQUIPOS DE ORDEÑO - INSEMINACIÓN - ACCESORIOS", x, y, paint);
        y += 15;
        canvas.drawText("NIT. 890.922.928 - 5. Direccion: Cra. 43A No.27ASur - 86 Local 301 - 4. Centro empresarial la Casona.", x, y, paint);
        y += 12;
        canvas.drawText("PBX: 557 55 28 cel: 311 876 15 39.", x, y, paint);

        y += 20;
        canvas.drawText("FECHA: " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), x, y, paint);
        canvas.drawText("REMISIÓN: " + consecutivoVisible, 400, y, titlePaint);
        canvas.drawText("PÁGINA " + (p + 1) + " DE " + totalPages, 400, y + 20, paint);

        y += 30;
        canvas.drawText("DOCUMENTO: " + fincaData.getOrDefault("persona_doc", ""), x, y, paint);
        canvas.drawText("FINCA: " + fincaData.getOrDefault("finca_nombre", ""), 200, y, paint); // Corrected mapping
        y += 15;
        canvas.drawText("CIUDAD: " + fincaData.getOrDefault("ciu", ""), x, y, paint); // Added Ciudad
        canvas.drawText("DIRECCIÓN: " + fincaData.getOrDefault("direccion", ""), 200, y, paint); // Added Dirección
        y += 15;
        canvas.drawText("PERSONA: " + fincaData.getOrDefault("persona_nombre", ""), x, y, paint);
        canvas.drawText("TEL: " + fincaData.getOrDefault("persona_telefono", ""), 350, y, paint);

            boolean isContentPage = p < contentPages;

            if (isContentPage) {
            y += 30;
            // Table Header
            paint.setFakeBoldText(true);
            canvas.drawText("REF", x, y, paint);
            canvas.drawText("CONCEPTO", x + 60, y, paint);
            canvas.drawText("CANT", x + 300, y, paint);
            canvas.drawText("UNITARIO", x + 360, y, paint);
            canvas.drawText("TOTAL", x + 440, y, paint);
            paint.setFakeBoldText(false);
            y += 5;
            canvas.drawLine(x, y, 550, y, paint);
            y += 15;

            int startIdx = p * itemsPerPage;
            int endIdx = Math.min(startIdx + itemsPerPage, totalItems);

            for (int i = startIdx; i < endIdx; i++) {
                Map<String, String> item = items.get(i);
                canvas.drawText(item.getOrDefault("id", ""), x, y, paint);
                canvas.drawText(item.getOrDefault("nombre", ""), x + 60, y, paint);
                
                // Qty: Strict Whole Number
                String qtyStr = item.getOrDefault("cantidad", "1");
                try {
                    qtyStr = String.valueOf((int) Double.parseDouble(qtyStr.replace(",", ".")));
                } catch (Exception e) {}
                canvas.drawText(qtyStr, x + 300, y, paint);

                // Money: Keep .00 Decimals
                double unit = Double.parseDouble(item.getOrDefault("precio", "0").replace(",", ""));
                int cant = Integer.parseInt(qtyStr);
                double total = unit * cant;

                canvas.drawText(String.format(Locale.getDefault(), "%,.2f", unit), x + 360, y, paint);
                canvas.drawText(String.format(Locale.getDefault(), "%,.2f", total), x + 440, y, paint);
                y += 20;
            }
            } // end isContentPage

            // --- FOOTER (on every page) ---
            float footerY = FOOTER_TOP;
            paint.setTextSize(7f);
            paint.setFakeBoldText(true);
            canvas.drawText("PUNTO DE VENTA SAN PEDRO", x, footerY, paint);
            paint.setFakeBoldText(false);
            canvas.drawText("Calle 45E No. 51AA - 72 Tel: 888 7708 Cel: 310 469 1338", x, footerY + 10, paint);
            
            paint.setFakeBoldText(true);
            canvas.drawText("PUNTO DE VENTA ENTRERRIOS", x, footerY + 25, paint);
            paint.setFakeBoldText(false);
            canvas.drawText("Cra. 12 No. 13-73 Tel: 867 0014 Cel: 321 759 4689", x, footerY + 35, paint);
            
            paint.setFakeBoldText(true);
            canvas.drawText("PUNTO DE VENTA SANTA ROSA DE OSOS", x, footerY + 50, paint);
            paint.setFakeBoldText(false);
            canvas.drawText("Av. 38 No. 28-100 Interior 233 Tel 8607211 Cel: 310 483 7545", x, footerY + 60, paint);
            // ------------------------------

            // Only on the LAST page: Totals and Signatures
            if (p == totalPages - 1) {
                double granTotal = 0;
                for (Map<String, String> item : items) {
                    double unit = Double.parseDouble(item.getOrDefault("precio", "0").replace(",", ""));
                    int cant = Integer.parseInt(item.getOrDefault("cantidad", "1"));
                    granTotal += (unit * cant);
                }

                y += 10;
                canvas.drawLine(x, y, 550, y, paint);
                y += 15;
                paint.setFakeBoldText(true);
                canvas.drawText("TOTAL: ", x + 360, y, paint);
                canvas.drawText(String.format(Locale.getDefault(), "%,.2f", granTotal), x + 440, y, paint);
                paint.setFakeBoldText(false);

                y += 40;
                canvas.drawText("TECNICO: " + tecnicoData.getOrDefault("nombre", "") + " " + tecnicoData.getOrDefault("apellidos", ""), x, y, paint);

                y += 30;
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(x, y, x + 200, y + 80, paint);
                canvas.drawRect(350, y, 550, y + 80, paint);
                paint.setStyle(Paint.Style.FILL);

                // Enable anti-aliasing and filtering for smooth signature rendering
                paint.setAntiAlias(true);
                paint.setFilterBitmap(true);
                paint.setDither(true);

                if (firmaTecnico != null) {
                    android.graphics.Rect src = new android.graphics.Rect(0, 0, firmaTecnico.getWidth(), firmaTecnico.getHeight());
                    android.graphics.RectF dst = new android.graphics.RectF(x + 5, y + 5, x + 195, y + 75);
                    canvas.drawBitmap(firmaTecnico, src, dst, paint);
                }
                if (firmaCliente != null) {
                    android.graphics.Rect src = new android.graphics.Rect(0, 0, firmaCliente.getWidth(), firmaCliente.getHeight());
                    android.graphics.RectF dst = new android.graphics.RectF(355, y + 5, 545, y + 75);
                    canvas.drawBitmap(firmaCliente, src, dst, paint);
                }

                y += 95;
                canvas.drawText("FIRMA TECNICO", x, y, paint);
                canvas.drawText("FIRMA CLIENTE", 350, y, paint);

                // --- OBSERVACIONES Field ---
                y += 20;
                paint.setFakeBoldText(true);
                canvas.drawText("OBSERVACIONES:", x, y, paint);
                paint.setFakeBoldText(false);
                
                y += 5;
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(x, y, 550, y + 60, paint); // Increased box height
                paint.setStyle(Paint.Style.FILL);
                
                String obsText = (observaciones != null && !observaciones.isEmpty()) ? observaciones : "Sin observaciones";
                
                // Optimized Word-Aware Wrapping
                List<String> wrappedLines = new ArrayList<>();
                String[] rawParagraphs = obsText.split("\n");
                Paint textPaint = new Paint(paint);
                textPaint.setTextSize(7f); // Ensure consistent size for calculation
                float maxWidth = 530; // Box width (550 - margins)

                for (String para : rawParagraphs) {
                    if (para.isEmpty()) {
                        wrappedLines.add("");
                        continue;
                    }
                    String[] words = para.split(" ");
                    StringBuilder currentLine = new StringBuilder();
                    for (String word : words) {
                        String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
                        if (textPaint.measureText(testLine) <= maxWidth) {
                            currentLine.append(currentLine.length() == 0 ? "" : " ").append(word);
                        } else {
                            wrappedLines.add(currentLine.toString());
                            currentLine = new StringBuilder(word);
                        }
                    }
                    if (currentLine.length() > 0) wrappedLines.add(currentLine.toString());
                }

                float currentY = y + 15;
                for (String line : wrappedLines) {
                    if (currentY > y + 55) break; // Increased height limit
                    canvas.drawText(line, x + 5, currentY, paint);
                    currentY += 12;
                }
                // ---------------------------
            }

            document.finishPage(page);
        }

        File file = new File(context.getFilesDir(), "Remision_" + consecutivoVisible + ".pdf");
        try {
            document.writeTo(new FileOutputStream(file));
            Log.d(TAG, "PDF saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error writing PDF", e);
            file = null;
        }
        document.close();
        return file;
    }
}
