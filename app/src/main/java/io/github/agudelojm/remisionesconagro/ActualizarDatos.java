package io.github.agudelojm.remisionesconagro;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;

public class ActualizarDatos {

    private static FileInputStream fileInputStream;

    private static XSSFWorkbook wb;

    private static AlertDialog alertDialog;

    public static String setFileInputStream(Context context, Uri uri) {
        try {
            fileInputStream = new FileInputStream(context.getContentResolver().openFileDescriptor(uri, "r").getFileDescriptor());
            wb = Methods.loadWorkbook(context, fileInputStream);
            fileInputStream.close();

        } catch (Exception e) {
            Log.w("Error leyendo", e.toString());

        }
        Log.w("contenidoWB", wb.getSheetAt(1).getRow(0).getCell(0).toString() );
        checkWorkbookType(context);
        return wb.getSheetAt(1).getSheetName();

    }

    private static void checkWorkbookType(Context context){
        Sheet sheet = wb.getSheetAt(1);
        String sheetName = sheet.getSheetName();
        switch (sheetName){
            case "Tecnico":
                Methods.saveWorkbook(context, wb,"Tecnico.xlsx");
                break;
            case "Producto":
                Methods.saveWorkbook(context, wb,"Producto.xlsx");
                break;
            case "Cliente":
                Methods.saveWorkbook(context, wb,"Cliente.xlsx");
                break;

            default:
                alertDialog = Methods.showAlert(context, "Error del excel, Solicite su revisión a un Administrador, y que se reenvie corregido");
                Methods.setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
                break;
        };
    }


}
