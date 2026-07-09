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

    // The main data workbook containing multiple sheets
    private static XSSFWorkbook datosAppWb = null;
    
    // Individual sheets from datosAppWb
    private static Sheet tecnicosSheet = null;
    private static Sheet productosSheet = null;
    private static Sheet clientesSheet = null;
    private static Sheet tarifasMantenimientoSheet = null;

    // Independent workbook for temporary inventory operations
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
                datosAppWb = loadWorkbookFromFile(currentActivity, "Datos App.xlsx");
                if (datosAppWb == null) throw new Exception("Archivo 'Datos App.xlsx' no encontrado");

                // Map sheets by name as per new requirement
                tecnicosSheet = datosAppWb.getSheet("Tecnicos");
                productosSheet = datosAppWb.getSheet("Productos");
                clientesSheet = datosAppWb.getSheet("Clientes");
                tarifasMantenimientoSheet = datosAppWb.getSheet("Tarifas Mantenimiento");

                if (tecnicosSheet == null || productosSheet == null || clientesSheet == null || tarifasMantenimientoSheet == null) {
                    throw new Exception("Error de formato: faltan hojas en 'Datos App.xlsx'");
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
            if (tecnicosSheet == null) throw new Exception("Tecnicos sheet not loaded");
            
            int idxCorreo = getColumnIndex(tecnicosSheet, "CORREO");
            int idxCedula = getColumnIndex(tecnicosSheet, "CEDULA");
            int idxNombre = getColumnIndex(tecnicosSheet, "NOMBRES");
            int idxAp1 = getColumnIndex(tecnicosSheet, "1 APELLIDO");
            int idxAp2 = getColumnIndex(tecnicosSheet, "2 APELLIDO");
            int idxNumero = getColumnIndex(tecnicosSheet, "NUMERO");

            if (idxCorreo == -1 || idxCedula == -1 || idxNombre == -1 || idxNumero == -1) {
                throw new Exception("Error de formato: faltan columnas requeridas en hoja Tecnicos");
            }

            for (Row row : tecnicosSheet) {
                if (row.getRowNum() == 0) continue;
                
                String correoFile = dataFormatter.formatCellValue(row.getCell(idxCorreo));
                if (correoIngresado.equalsIgnoreCase(correoFile)) {
                    String cedulaFile = dataFormatter.formatCellValue(row.getCell(idxCedula));
                    if (cedulaIngresada.equals(cedulaFile)) {
                        data.put("code", "1");
                        data.put("message", "Inicio de sesión exitoso.");
                        data.put("nombre", dataFormatter.formatCellValue(row.getCell(idxNombre)));
                        data.put("apellidos", dataFormatter.formatCellValue(row.getCell(idxAp1)) + " " + dataFormatter.formatCellValue(row.getCell(idxAp2)));
                        data.put("numero", dataFormatter.formatCellValue(row.getCell(idxNumero)));
                        data.put("cedula", cedulaFile);
                        return data;
                    }
                }
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
     * Searches for a product info by its reference or name in the Productos sheet.
     */
    public static Map<String, String> getProductoDetails(Context context, String query) {
        try {
            if (productosSheet == null) throw new Exception("Productos sheet not initialized");
            int idxRef = getColumnIndex(productosSheet, "referencia");
            int idxNombre = getColumnIndex(productosSheet, "nombre");

            if (idxRef == -1 || idxNombre == -1) return null;

            for (Row row : productosSheet) {
                if (row.getRowNum() == 0) continue;
                String ref = dataFormatter.formatCellValue(row.getCell(idxRef));
                String name = dataFormatter.formatCellValue(row.getCell(idxNombre));
                
                if (query.equalsIgnoreCase(ref) || query.equalsIgnoreCase(name)) {
                    Map<String, String> map = new HashMap<>();
                    map.put("referencia", ref);
                    map.put("nombre", name);
                    return map;
                }
            }
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
    public static List<String> getListaMantenimientos() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (tarifasMantenimientoSheet == null) return new ArrayList<>();
        int idxDesc = getColumnIndex(tarifasMantenimientoSheet, "descripcion");
        if (idxDesc == -1) return new ArrayList<>();

        for (Row row : tarifasMantenimientoSheet) {
            if (row.getRowNum() == 0) continue;
            String desc = dataFormatter.formatCellValue(row.getCell(idxDesc));
            if (!desc.isEmpty()) {
                set.add(desc);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Consolidated search for products and maintenance services.
     * Branches search logic: Products search by Ref, Services search by Description.
     */
    public static Map<String, String> buscarItem(String query, boolean isService) {
        Sheet sheet = isService ? tarifasMantenimientoSheet : productosSheet;
        if (sheet == null) return null;

        int idxRef = getColumnIndex(sheet, "referencia");
        int idxNom = getColumnIndex(sheet, isService ? "descripcion" : "nombre");
        int idxPrecio = getColumnIndex(sheet, "precio_base");

        if (idxRef == -1 || idxNom == -1) return null;

        // For products, handle "Ref - Name" format from autocomplete
        String cleanRef = query.split(" - ")[0].trim();

        for (Row row : sheet) {
            if (row.getRowNum() == 0) continue;
            
            String fileRef = dataFormatter.formatCellValue(row.getCell(idxRef));
            String fileNom = dataFormatter.formatCellValue(row.getCell(idxNom));

            boolean isMatch;
            if (isService) {
                // Maintenance: Must match by FULL Description (Nombre)
                isMatch = query.equalsIgnoreCase(fileNom);
            } else {
                // Product: Must match by Reference ID
                isMatch = cleanRef.equalsIgnoreCase(fileRef);
            }

            if (isMatch) {
                Map<String, String> map = new HashMap<>();
                map.put("id", fileRef);
                map.put("nombre", fileNom);
                map.put("precio", idxPrecio != -1 ? dataFormatter.formatCellValue(row.getCell(idxPrecio)) : "0");
                return map;
            }
        }
        return null;
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
    public static List<String> getListaFincas() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (clientesSheet == null) return new ArrayList<>();
        int idxId = getColumnIndex(clientesSheet, "finca_id");
        int idxNom = getColumnIndex(clientesSheet, "finca_nombre"); // Corrected to finca_nombre
        if (idxId == -1) return new ArrayList<>();

        for (Row row : clientesSheet) {
            if (row.getRowNum() == 0) continue;
            String id = dataFormatter.formatCellValue(row.getCell(idxId));
            String nom = (idxNom != -1) ? dataFormatter.formatCellValue(row.getCell(idxNom)) : "";
            if (!id.isEmpty()) {
                set.add(id + (nom.isEmpty() ? "" : " - " + nom));
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Returns a list of all Product References formatted as "Ref - Name".
     * Uses a LinkedHashSet internally to prevent duplicates.
     */
    public static List<String> getListaProductos() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (productosSheet == null) return new ArrayList<>();
        int idxRef = getColumnIndex(productosSheet, "referencia");
        int idxNom = getColumnIndex(productosSheet, "nombre");
        if (idxRef == -1) return new ArrayList<>();

        for (Row row : productosSheet) {
            if (row.getRowNum() == 0) continue;
            String ref = dataFormatter.formatCellValue(row.getCell(idxRef));
            String nom = (idxNom != -1) ? dataFormatter.formatCellValue(row.getCell(idxNom)) : "";
            if (!ref.isEmpty()) {
                set.add(ref + (nom.isEmpty() ? "" : " - " + nom));
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * Searches for a Finca/Client by its query string (ID or Name).
     */
    public static Map<String, String> buscarCliente(String query) {
        if (clientesSheet == null) return null;
        int idxId = getColumnIndex(clientesSheet, "finca_id");
        if (idxId == -1) return null;

        // Clean query if it came from autocomplete "ID - Name"
        String fincaId = query.split(" - ")[0].trim();

        for (Row row : clientesSheet) {
            if (row.getRowNum() == 0) continue;
            if (fincaId.equalsIgnoreCase(dataFormatter.formatCellValue(row.getCell(idxId)))) {
                Map<String, String> map = new HashMap<>();
                // Extract all column values for the PDF
                Row header = clientesSheet.getRow(0);
                for (Cell cell : header) {
                    String key = dataFormatter.formatCellValue(cell);
                    String val = dataFormatter.formatCellValue(row.getCell(cell.getColumnIndex()));
                    map.put(key, val);
                }
                return map;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Consecutivo (sequential referral numbering)
    // Format: <técnico número, min 2 digits><6-digit sequence>, e.g. 03000001.
    // Counter is stored per técnico número in app_config.json under "consecutivos".
    // See docs/feature-consecutivo-remision.md.
    // ------------------------------------------------------------------

    /** Zero-pads the técnico prefix (2 digits min) and the 6-digit sequence: (3, 1) -> "03000001". */
    public static String formatConsecutivo(String tecnicoNumero, int seq) {
        String prefix;
        try {
            prefix = String.format(Locale.getDefault(), "%02d", Integer.parseInt(tecnicoNumero.trim()));
        } catch (Exception e) {
            prefix = tecnicoNumero != null ? tecnicoNumero.trim() : "";
        }
        return prefix + String.format(Locale.getDefault(), "%06d", seq);
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
     * True once a counter has been established for this técnico. Uses key presence (not value),
     * so a técnico who seeds "never sent" (sequence 0) still counts as configured.
     */
    public static synchronized boolean isConsecutivoInicializado(Context context, String tecnicoNumero) {
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) return false;
        try {
            File file = new File(context.getFilesDir(), "app_config.json");
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] data = new byte[(int) file.length()];
                    fis.read(data);
                    JSONObject json = new JSONObject(new String(data, "UTF-8"));
                    JSONObject cons = json.optJSONObject("consecutivos");
                    if (cons != null && cons.has(tecnicoNumero)) return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking consecutivo init", e);
        }
        // Self-heal: if referral files survive, treat as configured.
        return reconcileConsecutivo(context, tecnicoNumero) > 0;
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
     * Returns the next consecutivo for this técnico WITHOUT consuming it.
     * A missing técnico número is a fatal, unrecoverable configuration error.
     */
    public static String peekProximoConsecutivo(Context context, String tecnicoNumero) {
        if (tecnicoNumero == null || tecnicoNumero.trim().isEmpty()) {
            showFatalError(context, "El técnico no tiene un número asignado en 'Datos App.xlsx'. "
                    + "Notifique a la oficina. La aplicación se cerrará.");
            return null;
        }
        int base = Math.max(getStoredConsecutivo(context, tecnicoNumero), reconcileConsecutivo(context, tecnicoNumero));
        return formatConsecutivo(tecnicoNumero, base + 1);
    }

    /**
     * Consumes the given consecutivo by persisting its sequence as the last used for this técnico.
     * Call only after a remisión has been successfully sent.
     */
    public static synchronized void commitConsecutivo(Context context, String tecnicoNumero, String consecutivo) {
        if (tecnicoNumero == null || consecutivo == null || consecutivo.length() < 6) return;
        try {
            int seq = Integer.parseInt(consecutivo.substring(consecutivo.length() - 6));
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
        try {
            JSONObject json = new JSONObject();
            json.put("consecutivo", consecutivo);
            json.put("fecha", System.currentTimeMillis());
            json.put("restored", restored);
            
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
        canvas.drawText("REMISIÓN: " + consecutivo, 400, y, titlePaint);
        canvas.drawText("PÁGINA " + (p + 1) + " DE " + totalPages, 400, y + 20, paint);

        y += 30;
        canvas.drawText("ID FINCA: " + fincaData.getOrDefault("finca_id", ""), x, y, paint);
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

        File file = new File(context.getFilesDir(), "Remision_" + consecutivo + ".pdf");
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
