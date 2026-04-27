package io.github.agudelojm.remisionesconagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;

/**
 * Utility class containing common methods for data handling, Excel operations, 
 * and UI interactions across the application.
 */
public class Methods {

    private static final String TAG = "Methods";
    private static WeakReference<Activity> activityRef;
    private static AlertDialog alertDialog = null;
    private static final DataFormatter dataFormatter = new DataFormatter();

    // Workbooks stored globally to be accessed by different activities
    private static XSSFWorkbook tecnicoWb = null;
    private static XSSFWorkbook productoWb = null;
    private static XSSFWorkbook clienteWb = null;
    private static XSSFWorkbook inventarioWb = null;

    /**
     * Updates the current activity reference to manage dialogs and context-specific actions.
     */
    public static void updateActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }

    /**
     * Initializes the application data by loading essential Excel files from internal storage.
     */
    public static void initializeData(Context context) {
        alertDialog = showAlert(context, "Leyendo Exceles...");
        alertDialog.show();

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try {
                tecnicoWb = loadWorkbookFromFile(context, "Tecnico.xlsx");
                if (tecnicoWb == null) throw new Exception("Técnico not found");

                productoWb = loadWorkbookFromFile(context, "Producto.xlsx");
                if (productoWb == null) throw new Exception("Producto not found");

                clienteWb = loadWorkbookFromFile(context, "Cliente.xlsx");
                if (clienteWb == null) throw new Exception("Cliente not found");

                alertDialog.dismiss();
                alertDialog = showAlert(context, "Éxito leyendo Exceles");
                alertDialog.show();

            } catch (Exception e) {
                Log.e(TAG, "Error initializing data: " + e.getMessage());
                if (alertDialog != null) alertDialog.dismiss();
                
                String errorMsg = "Error, Excel no encontrado. Por favor, abra el archivo desde su correo usando esta aplicación.";
                alertDialog = showAlert(context, errorMsg);
                setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
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
            alertDialog = showAlert(context, "Error del excel. Solicite revisión a un Administrador.");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
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
            alertDialog = showAlert(context, "Error guardando excel, comuniquese con un administrador");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
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
     * Validates user credentials against the Técnico Excel file.
     */
    public static HashMap<String, String> validateCredentials(String username, String password) {
        HashMap<String, String> data = new HashMap<>();
        
        if (validateEmptyStringArray(new String[]{username, password})) {
            data.put("code", "2");
            data.put("message", "Error, no puede haber campos vacíos");
            return data;
        }

        try {
            if (tecnicoWb == null) throw new Exception("Tecnico workbook not loaded");
            Sheet sheet = tecnicoWb.getSheetAt(1);
            
            int idxUser = -1, idxPass = -1, idxEmail = -1, idxName = -1, idxPrefix = -1, idxConsec = -1;

            Row header = sheet.getRow(0);
            for (Cell cell : header) {
                String val = cell.toString().trim();
                if (val.equalsIgnoreCase("Usuario")) idxUser = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("Contraseña")) idxPass = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("Correo Gestora")) idxEmail = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("Prefijo")) idxPrefix = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("Consecutivo")) idxConsec = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("Nombre")) idxName = cell.getColumnIndex();
            }

            if (idxUser == -1 || idxPass == -1 || idxEmail == -1 || idxName == -1 || idxPrefix == -1 || idxConsec == -1) throw new Exception("Format error");

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                
                String userInFile = dataFormatter.formatCellValue(row.getCell(idxUser));
                if (username.equals(userInFile)) {
                    String passInFile = dataFormatter.formatCellValue(row.getCell(idxPass));
                    if (password.equals(passInFile)) {
                        data.put("code", "1");
                        data.put("message", "Inicio de sesión exitoso.");
                        data.put("nombre", dataFormatter.formatCellValue(row.getCell(idxName)));
                        data.put("correoGestora", dataFormatter.formatCellValue(row.getCell(idxEmail)));
                        data.put("Prefijo", dataFormatter.formatCellValue(row.getCell(idxPrefix)));
                        
                        Cell consecCell = row.getCell(idxConsec);
                        data.put("consecutivo", (consecCell == null || consecCell.getCellType() == CellType.BLANK) 
                                ? "000000" : dataFormatter.formatCellValue(consecCell));
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
     * Updates the consecutive value for a technician by their real name in the Técnico Excel file.
     */
    public static boolean updateConsecutivo(Context context, String realName, String nuevoConsecutivo) {
        try {
            if (tecnicoWb == null) return false;
            Sheet sheet = tecnicoWb.getSheetAt(1);
            
            int idxName = -1, idxConsec = -1;

            Row header = sheet.getRow(0);
            for (Cell cell : header) {
                String val = cell.toString().trim();
                if (val.equalsIgnoreCase("Nombre")) idxName = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("Consecutivo")) idxConsec = cell.getColumnIndex();
            }

            if (idxName == -1 || idxConsec == -1) return false;

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String nameInFile = dataFormatter.formatCellValue(row.getCell(idxName));
                if (realName.equals(nameInFile)) {
                    Cell cell = row.getCell(idxConsec);
                    if (cell == null) cell = row.createCell(idxConsec);
                    cell.setCellValue(nuevoConsecutivo);
                    saveWorkbook(context, tecnicoWb, "Tecnico.xlsx");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating consecutive", e);
        }
        return false;
    }

    /**
     * Searches for a product name by its ID in the Producto Excel file.
     */
    public static String getProductoName(Context context, String productoID) {
        try {
            if (productoWb == null) throw new Exception("Workbook not initialized");
            Sheet sheet = productoWb.getSheetAt(1);
            int idxItem = -1, idxNombre = -1;

            Row header = sheet.getRow(0);
            for (Cell cell : header) {
                String val = cell.toString().trim();
                if (val.equalsIgnoreCase("item")) idxItem = cell.getColumnIndex();
                else if (val.equalsIgnoreCase("nombre")) idxNombre = cell.getColumnIndex();
            }

            if (idxItem == -1 || idxNombre == -1) return null;

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                if (productoID.equals(dataFormatter.formatCellValue(row.getCell(idxItem)))) {
                    return dataFormatter.formatCellValue(row.getCell(idxNombre));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching product name", e);
            alertDialog = showAlert(context, "Error inesperado, comuniquese con un administrador");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }
        return null;
    }

    /**
     * Loads the Inventario workbook from storage and sets the global reference.
     */
    public static XSSFWorkbook loadInventarioWb(Context context) {
        try {
            File file = new File(context.getFilesDir(), "Inventario.xlsx");
            if (!file.exists()) throw new Exception("File not found");
            
            try (FileInputStream fis = new FileInputStream(file)) {
                inventarioWb = loadWorkbook(context, fis);
                return inventarioWb;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading Inventario.xlsx", e);
            alertDialog = showAlert(context, "Error, Excel de Inventario no encontrado.");
            setFinishActivityOnDismiss(alertDialog);
            alertDialog.show();
            return null;
        }
    }

    public static XSSFWorkbook getInventarioWb() { return inventarioWb; }
    
    public static void setInventarioWbToNull() { inventarioWb = null; }

    public static boolean validateEmptyStringArray(String[] fields) {
        for (String s : fields) if (s == null || s.isEmpty()) return true;
        return false;
    }
}
