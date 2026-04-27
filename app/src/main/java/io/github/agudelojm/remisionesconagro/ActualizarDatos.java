package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.util.Objects;

/**
 * Handles the update of internal Excel workbooks when a new file is opened via URI.
 */
public class ActualizarDatos {

    private static final String TAG = "ActualizarDatos";

    /**
     * Loads a workbook from a given Uri and triggers the update process.
     */
    public static String setFileInputStream(Context context, Uri uri) {
        try (FileInputStream fis = new FileInputStream(Objects.requireNonNull(context.getContentResolver().openFileDescriptor(uri, "r")).getFileDescriptor())) {
            XSSFWorkbook wb = Methods.loadWorkbook(context, fis);
            if (wb != null) {
                checkWorkbookType(context, wb);
                return wb.getSheetAt(1).getSheetName();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing input stream from URI", e);
        }
        return "Desconocido";
    }

    /**
     * Determines the type of workbook by its sheet name and saves it accordingly.
     */
    private static void checkWorkbookType(Context context, XSSFWorkbook wb) {
        Sheet sheet = wb.getSheetAt(1);
        String sheetName = sheet.getSheetName();
        
        String fileName;
        switch (sheetName) {
            case "Tecnico":
                fileName = "Tecnico.xlsx";
                break;
            case "Producto":
                fileName = "Producto.xlsx";
                break;
            case "Cliente":
                fileName = "Cliente.xlsx";
                break;
            case "Inventario":
                fileName = "Inventario.xlsx";
                break;
            default:
                AlertDialog alertDialog = Methods.showAlert(context, "Error del excel. Solicite revisión a un Administrador.");
                Methods.setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
                return;
        }
        
        Methods.saveWorkbook(context, wb, fileName);
    }
}
