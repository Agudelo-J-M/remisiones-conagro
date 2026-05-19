package io.github.agudelojm.remisionesconagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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

                alertDialog.dismiss();
                alertDialog = showAlert(context, "Éxito leyendo Exceles");
                alertDialog.show();

            } catch (Exception e) {
                Log.e(TAG, "Error initializing data: " + e.getMessage());
                if (alertDialog != null) alertDialog.dismiss();
                
                String errorMsg = "Error: " + e.getMessage() + ". Por favor, abra el archivo desde su correo usando esta aplicación.";
                showFatalError(context, errorMsg);
            }
        }, 1250);
    }

    /**
     * Helper to load a workbook from a specific filename in internal storage.
     */
    private static XSSFWorkbook loadWorkbookFromFile(Context context, String fileName) throws Exception {
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
     * Searches for a product name by its reference ID in the Productos sheet.
     */
    public static String getProductoName(Context context, String referencia) {
        try {
            if (productosSheet == null) throw new Exception("Productos sheet not initialized");
            int idxRef = getColumnIndex(productosSheet, "referencia");
            int idxNombre = getColumnIndex(productosSheet, "nombre");

            if (idxRef == -1 || idxNombre == -1) return null;

            for (Row row : productosSheet) {
                if (row.getRowNum() == 0) continue;
                if (referencia.equals(dataFormatter.formatCellValue(row.getCell(idxRef)))) {
                    return dataFormatter.formatCellValue(row.getCell(idxNombre));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching product name", e);
            showFatalError(context, "Error inesperado, comuniquese con un administrador");
        }
        return null;
    }

    /**
     * Loads the Inventario workbook from storage and sets the global reference.
     */
    public static XSSFWorkbook loadInventarioWb(Context context) {
        try {
            inventarioWb = loadWorkbookFromFile(context, "Inventario.xlsx");
            if (inventarioWb == null) throw new Exception("Archivo 'Inventario.xlsx' no encontrado");
            return inventarioWb;
        } catch (Exception e) {
            Log.e(TAG, "Error loading Inventario.xlsx", e);
            showErrorAndFinishActivity(context, "Error, Excel de Inventario no encontrado.");
            return null;
        }
    }

    public static XSSFWorkbook getInventarioWb() { return inventarioWb; }
    
    public static void setInventarioWbToNull() { inventarioWb = null; }

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

    public static Map<String, String> buscarProducto(String referencia) {
        if (productosSheet == null) return null;
        int idxRef = getColumnIndex(productosSheet, "referencia");
        int idxNombre = getColumnIndex(productosSheet, "nombre");
        int idxPrecio = getColumnIndex(productosSheet, "precio_base");
        if (idxRef == -1 || idxNombre == -1) return null;

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

    public static Map<String, String> buscarCliente(String fincaId) {
        if (clientesSheet == null) return null;
        int idxId = getColumnIndex(clientesSheet, "finca_id");
        if (idxId == -1) return null;

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
            showFatalError(context, "Error: El número del técnico no está disponible. Contacte a un administrador.");
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

    private static int getColumnIndex(Sheet sheet, String colName) {
        if (sheet == null) return -1;
        Row header = sheet.getRow(0);
        if (header == null) return -1;
        for (Cell cell : header) {
            if (cell.toString().trim().equalsIgnoreCase(colName)) return cell.getColumnIndex();
        }
        return -1;
    }

    public static void generarPDFRemision(Context context, String consecutivo, Map<String, String> fincaData, List<Map<String, String>> items, Map<String, String> tecnicoData, Bitmap firmaTecnico, Bitmap firmaCliente) {
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
            showAlert(context, "PDF Guardado en: " + file.getAbsolutePath()).show();
        } catch (Exception e) {
            Log.e(TAG, "Error writing PDF", e);
            showAlert(context, "Error guardando PDF").show();
        }
        document.close();
    }
}
