package io.github.agudelojm.remisionesconagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.List;
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
        alertDialog = showAlert(context, "Leyendo Exceles...");
        alertDialog.show();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try {
                datosAppWb = loadWorkbookFromFile(context, "Datos App.xlsx");
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
                    inventarioWb = loadWorkbookFromFile(context, "Inventario.xlsx");
                } catch (Exception e) {
                    Log.w(TAG, "Inventario.xlsx could not be loaded on startup: " + e.getMessage());
                }

                alertDialog.dismiss();
                alertDialog = showAlert(context, "Éxito leyendo Exceles");
                if (inventarioWb == null) {
                    alertDialog.setMessage("Éxito leyendo Base de Datos. Nota: No se encontró 'Inventario.xlsx', las validaciones de stock estarán deshabilitadas.");
                }
                alertDialog.show();
                
                // Perform cleanup of old referrals
                cleanOldRemisiones(context);

            } catch (Exception e) {
                Log.e(TAG, "Error initializing data: " + e.getMessage());
                if (alertDialog != null) alertDialog.dismiss();
                
                String errorMsg = "Error: " + e.getMessage() + ". Por favor, abra el archivo desde su correo usando esta aplicación.";
                showFatalError(context, errorMsg);
            }
        }, 1250);
    }

    /**
     * Deletes referral PDFs and JSON data older than 14 days.
     */
    public static void cleanOldRemisiones(Context context) {
        File dir = context.getFilesDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith("Remision_") && (name.endsWith(".pdf") || name.endsWith(".json")));
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
    public static void saveWorkbook(Context context, XSSFWorkbook wb, String fileName) {
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

    public static XSSFWorkbook getInventarioWb() { return inventarioWb; }
    
    public static void setInventarioWbToNull() { inventarioWb = null; }

    public static DataFormatter getDataFormatter() { return dataFormatter; }

    /**
     * Resolves the filename from a given Uri using ContentResolver.
     */
    public static String getFileName(Context context, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result != null ? result : "Desconocido";
    }

    /**
     * Searches for an item in the Inventory Workbook and returns its stock (saldo).
     * Returns -1.0 if the item is not found or sheet is invalid.
     */
    public static double getInventarioSaldo(String idOrName) {
        if (inventarioWb == null) return -1.0;
        try {
            Sheet sheet = inventarioWb.getSheet("Inventario");
            if (sheet == null) sheet = inventarioWb.getSheetAt(0);
            
            int idxRef = -1, idxNombre = -1, idxSaldo = -1;
            Row header = sheet.getRow(0);
            for (Cell cell : header) {
                String val = cell.toString().toLowerCase().trim();
                if (val.contains("referencia")) idxRef = cell.getColumnIndex();
                else if (val.contains("nombre")) idxNombre = cell.getColumnIndex();
                else if (val.contains("saldo")) idxSaldo = cell.getColumnIndex();
            }

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

    public static boolean validateEmptyStringArray(String[] fields) {
        for (String s : fields) if (s == null || s.isEmpty()) return true;
        return false;
    }

    // --- Logic Layer for Ingresar Remisión ---

    public static List<String> getListaMantenimientos() {
        List<String> mantenimientos = new ArrayList<>();
        if (tarifasMantenimientoSheet != null) {
            int idxDesc = getColumnIndex(tarifasMantenimientoSheet, "descripcion");
            if (idxDesc != -1) {
                for (Row row : tarifasMantenimientoSheet) {
                    if (row.getRowNum() == 0) continue;
                    String desc = dataFormatter.formatCellValue(row.getCell(idxDesc));
                    if (desc != null && !desc.trim().isEmpty()) mantenimientos.add(desc);
                }
            }
        }
        return mantenimientos;
    }

    public static Map<String, String> buscarProducto(String query) {
        if (productosSheet == null) return null;
        int idxRef = getColumnIndex(productosSheet, "referencia");
        int idxNombre = getColumnIndex(productosSheet, "nombre");
        int idxPrecio = getColumnIndex(productosSheet, "precio_base");
        if (idxRef == -1 || idxNombre == -1) return null;

        // Clean query if it came from autocomplete "Ref - Name"
        String referencia = query.split(" - ")[0].trim();

        for (Row row : productosSheet) {
            if (row.getRowNum() == 0) continue;
            if (referencia.equalsIgnoreCase(dataFormatter.formatCellValue(row.getCell(idxRef)))) {
                Map<String, String> map = new HashMap<>();
                map.put("id", referencia);
                map.put("nombre", dataFormatter.formatCellValue(row.getCell(idxNombre)));
                map.put("precio", idxPrecio != -1 ? dataFormatter.formatCellValue(row.getCell(idxPrecio)) : "0");
                return map;
            }
        }
        return null;
    }

    public static Map<String, String> buscarMantenimiento(String descripcion) {
        if (tarifasMantenimientoSheet == null) return null;
        int idxDesc = getColumnIndex(tarifasMantenimientoSheet, "descripcion");
        int idxRef = getColumnIndex(tarifasMantenimientoSheet, "referencia");
        int idxPrecio = getColumnIndex(tarifasMantenimientoSheet, "precio_base");
        if (idxDesc == -1 || idxRef == -1) return null;

        for (Row row : tarifasMantenimientoSheet) {
            if (row.getRowNum() == 0) continue;
            if (descripcion.equalsIgnoreCase(dataFormatter.formatCellValue(row.getCell(idxDesc)))) {
                Map<String, String> map = new HashMap<>();
                map.put("id", dataFormatter.formatCellValue(row.getCell(idxRef)));
                map.put("nombre", descripcion);
                map.put("precio", idxPrecio != -1 ? dataFormatter.formatCellValue(row.getCell(idxPrecio)) : "0");
                return map;
            }
        }
        return null;
    }

    public static List<String> getListaFincas() {
        List<String> lista = new ArrayList<>();
        if (clientesSheet == null) return lista;
        int idxId = getColumnIndex(clientesSheet, "finca_id");
        int idxNom = getColumnIndex(clientesSheet, "persona_nombre");
        if (idxId == -1) return lista;

        for (Row row : clientesSheet) {
            if (row.getRowNum() == 0) continue;
            String id = dataFormatter.formatCellValue(row.getCell(idxId));
            String nom = (idxNom != -1) ? dataFormatter.formatCellValue(row.getCell(idxNom)) : "";
            if (!id.isEmpty()) {
                lista.add(id + (nom.isEmpty() ? "" : " - " + nom));
            }
        }
        return lista;
    }

    public static List<String> getListaProductos() {
        List<String> lista = new ArrayList<>();
        if (productosSheet == null) return lista;
        int idxRef = getColumnIndex(productosSheet, "referencia");
        int idxNom = getColumnIndex(productosSheet, "nombre");
        if (idxRef == -1) return lista;

        for (Row row : productosSheet) {
            if (row.getRowNum() == 0) continue;
            String ref = dataFormatter.formatCellValue(row.getCell(idxRef));
            String nom = (idxNom != -1) ? dataFormatter.formatCellValue(row.getCell(idxNom)) : "";
            if (!ref.isEmpty()) {
                lista.add(ref + (nom.isEmpty() ? "" : " - " + nom));
            }
        }
        return lista;
    }

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

    public static String generarConsecutivo(Context context, String tecnicoNumero) {
        if (tecnicoNumero == null || tecnicoNumero.isEmpty()) {
            showFatalError(context, "Error: El número del técnico no está disponible para generar consecutivo.");
            return null;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Hardcoded custom epoch             
            OffsetDateTime customEpoch = OffsetDateTime.of(2026, 5, 14, 0, 0, 0, 0, ZoneOffset.UTC);
            Instant now = Instant.now();
            long minutesSinceCustomEpoch = ChronoUnit.MINUTES.between(customEpoch.toInstant(), now);
            return tecnicoNumero + minutesSinceCustomEpoch;
        } else {
            return tecnicoNumero + (System.currentTimeMillis() / 60000);
        }
    }

    /**
     * Loads the car inventory from a JSON file in internal storage.
     */
    public static HashMap<String, Integer> getCarInventory(Context context) {
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

    public static void saveCarInventory(Context context, HashMap<String, Integer> inventory) {
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

    public static void saveRemisionData(Context context, String consecutivo, Map<String, String> fincaData, List<Map<String, String>> items, Map<String, String> tecnicoData, boolean restored) {
        try {
            JSONObject json = new JSONObject();
            json.put("consecutivo", consecutivo);
            json.put("fecha", System.currentTimeMillis());
            json.put("restored", restored);
            
            JSONObject fincaJson = new JSONObject(fincaData);
            json.put("fincaData", fincaJson);
            
            org.json.JSONArray itemsArray = new org.json.JSONArray();
            for (Map<String, String> item : items) {
                itemsArray.put(new JSONObject(item));
            }
            json.put("items", itemsArray);
            
            JSONObject tecnicoJson = new JSONObject(tecnicoData);
            json.put("tecnicoData", tecnicoJson);
            
            File file = new File(context.getFilesDir(), "Remision_" + consecutivo + ".json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving remission JSON", e);
        }
    }

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
        File[] files = dir.listFiles((d, name) -> name.startsWith("Remision_") && name.endsWith(".json"));
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

    /**
     * Adds items back to stock (used during modification).
     */
    public static void restaurarStock(Context context, List<Map<String, String>> items) {
        // 1. Restore to Car Inventory
        HashMap<String, Integer> carInv = getCarInventory(context);
        for (Map<String, String> item : items) {
            String ref = item.get("id");
            if (ref == null || ref.isEmpty()) continue;
            
            String qtyStr = item.get("cantidad");
            if (qtyStr == null) continue;
            
            int qty = Integer.parseInt(qtyStr);
            Integer current = carInv.get(ref);
            carInv.put(ref, (current != null ? current : 0) + qty);
        }
        saveCarInventory(context, carInv);

        // 2. Restore to Warehouse Inventory (only if item exists there)
        try {
            if (inventarioWb == null) loadInventarioWb(context);
        } catch (Exception e) {
            Log.e(TAG, "Error loading inventory for restoration", e);
        }
        
        if (inventarioWb != null) {
            try {
                Sheet sheet = inventarioWb.getSheet("Inventario");
                if (sheet == null) sheet = inventarioWb.getSheetAt(0);

                int idxRef = getColumnIndex(sheet, "referencia");
                int idxSaldo = getColumnIndex(sheet, "saldo");
                if (idxRef == -1 || idxSaldo == -1) return;

                boolean modified = false;
                for (Map<String, String> item : items) {
                    String refToRestore = item.get("id");
                    String qtyStr = item.get("cantidad");
                    if (refToRestore == null || qtyStr == null) continue;
                    
                    int qtyToRestore = Integer.parseInt(qtyStr);

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
                            saldoCell.setCellValue(currentSaldo + qtyToRestore);
                            modified = true;
                            break;
                        }
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
     */
    public static void updateWarehouseStock(Context context, List<Map<String, String>> items) {
        if (inventarioWb == null) loadInventarioWb(context);
        if (inventarioWb == null) return;
        try {
            Sheet sheet = inventarioWb.getSheet("Inventario");
            if (sheet == null) sheet = inventarioWb.getSheetAt(0);

            int idxRef = getColumnIndex(sheet, "referencia");
            int idxSaldo = getColumnIndex(sheet, "saldo");

            if (idxRef == -1 || idxSaldo == -1) return;

            boolean modified = false;
            for (Map<String, String> item : items) {
                String refToDeduct = item.get("id");
                if (refToDeduct == null || refToDeduct.isEmpty()) continue;
                
                int qtyToDeduct;
                try {
                    qtyToDeduct = Integer.parseInt(item.get("cantidad"));
                } catch (Exception e) { continue; }

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
                        
                        saldoCell.setCellValue(currentSaldo - qtyToDeduct);
                        modified = true;
                        break;
                    }
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
    public static void updateCarStock(Context context, List<Map<String, String>> items) {
        HashMap<String, Integer> carInv = getCarInventory(context);
        boolean modified = false;
        for (Map<String, String> item : items) {
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
            pict.resize(0.5); // Adjust size as needed
        } catch (Exception e) {
            Log.e(TAG, "Error inserting image to workbook", e);
        }
    }

    public static File generarPDFRemision(Context context, String consecutivo, Map<String, String> fincaData, List<Map<String, String>> items, Map<String, String> tecnicoData, Bitmap firmaTecnico, Bitmap firmaCliente) {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();
        Paint titlePaint = new Paint();
        titlePaint.setTextSize(18f);
        titlePaint.setFakeBoldText(true);
        
        float x = 50, y = 50;
        
        // Header
        canvas.drawText("CONAGRO S.A.", x, y, titlePaint);
        y += 20;
        paint.setTextSize(9f);
        canvas.drawText("EQUIPOS DE ORDEÑO - INEMINACIÓN - ACCESORIOS", x, y, paint);
        y += 15;
        canvas.drawText("NIT. 890.922.928 - 5. Direccion: Cra. 43A No.27ASur - 86 Local 301 - 4. Centro empresarial la Casona. PBX: 557 55 28 cel: 311 876 15 39.", x, y, paint);
        
        y += 20;
        canvas.drawText("PUNTO DE VENTA SAN PEDRO", x, y, paint);
        y += 12;
        canvas.drawText("Calle 45E No. 51AA - 72 Tel: 888 7708 Cel: 310 469 1338", x, y, paint);
        y += 15;
        canvas.drawText("PUNTO DE VENTA ENTRERRIOS", x, y, paint);
        y += 12;
        canvas.drawText("Cra. 12 No. 13-73 Tel: 867 0014 Cel: 321 759 4689", x, y, paint);
        y += 15;
        canvas.drawText("PUNTO DE VENTA SANTA ROSA DE OSOS", x, y, paint);
        y += 12;
        canvas.drawText("Av. 38 No. 28-100 Interior 233 Tel 8607211 Cel: 310 483 7545", x, y, paint);
        
        y += 30;
        String dateStr = "";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dateStr = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }
        canvas.drawText("FECHA: " + dateStr, x, y, paint);
        canvas.drawText("REMISIÓN: " + consecutivo, 400, y, titlePaint);
        
        y += 30;
        canvas.drawText("ID FINCA: " + fincaData.getOrDefault("finca_id", ""), x, y, paint);
        canvas.drawText("FINCA: " + fincaData.getOrDefault("direccion", ""), 200, y, paint);
        y += 15;
        canvas.drawText("CIUDAD: " + fincaData.getOrDefault("ciu", ""), x, y, paint);
        canvas.drawText("DIRECCIÓN: " + fincaData.getOrDefault("direccion", ""), 200, y, paint);
        y += 15;
        canvas.drawText("PERSONA: " + fincaData.getOrDefault("persona_nombre", ""), x, y, paint);
        canvas.drawText("DOC: " + fincaData.getOrDefault("persona_doc", ""), 200, y, paint);
        canvas.drawText("TEL: " + fincaData.getOrDefault("persona_telefono", ""), 350, y, paint);
        
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
        
        double granTotal = 0;
        for (Map<String, String> item : items) {
            canvas.drawText(item.getOrDefault("id", ""), x, y, paint);
            canvas.drawText(item.getOrDefault("nombre", ""), x + 60, y, paint);
            canvas.drawText(item.getOrDefault("cantidad", ""), x + 300, y, paint);
            
            double unit = Double.parseDouble(item.getOrDefault("precio", "0").replace(",", ""));
            int cant = Integer.parseInt(item.getOrDefault("cantidad", "1"));
            double total = unit * cant;
            granTotal += total;
            
            canvas.drawText(String.format("%,.2f", unit), x + 360, y, paint);
            canvas.drawText(String.format("%,.2f", total), x + 440, y, paint);
            y += 15;
        }
        
        y += 5;
        canvas.drawLine(x, y, 550, y, paint);
        y += 15;
        paint.setFakeBoldText(true);
        canvas.drawText("TOTAL: ", x + 360, y, paint);
        canvas.drawText(String.format("%,.2f", granTotal), x + 440, y, paint);
        paint.setFakeBoldText(false);
        
        y += 40;
        canvas.drawText("TECNICO: " + tecnicoData.getOrDefault("nombre", "") + " " + tecnicoData.getOrDefault("apellidos", ""), x, y, paint);
        
        y += 30;
        // Draw boxes and titles for signatures
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(x, y, x + 200, y + 80, paint);
        canvas.drawRect(350, y, 550, y + 80, paint);
        paint.setStyle(Paint.Style.FILL);
        
        if (firmaTecnico != null) {
            Bitmap scaledFirmaT = Bitmap.createScaledBitmap(firmaTecnico, 180, 60, false);
            canvas.drawBitmap(scaledFirmaT, x + 10, y + 10, paint);
        }
        if (firmaCliente != null) {
            Bitmap scaledFirmaC = Bitmap.createScaledBitmap(firmaCliente, 180, 60, false);
            canvas.drawBitmap(scaledFirmaC, 360, y + 10, paint);
        }
        
        y += 95;
        canvas.drawText("FIRMA TECNICO", x, y, paint);
        canvas.drawText("FIRMA CLIENTE", 350, y, paint);
        
        document.finishPage(page);
        
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
