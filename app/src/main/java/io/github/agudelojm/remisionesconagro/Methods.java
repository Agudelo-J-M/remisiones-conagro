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

public class Methods {

    private static WeakReference<Activity> activityRef;

    private static AlertDialog alertDialog = null;

    //decided to keep workbook in methods in order to declutter extras

    private static final DataFormatter dataFormatter = new DataFormatter();

    private static XSSFWorkbook tecnicoWb = null;

    private static XSSFWorkbook productoWb;

    private static XSSFWorkbook clienteWb;




    //changes actvity context on new activity load to set alerdialongs and close app on dismiss
    public static void updateActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);

    }

    public static void initializeData(Context context){
        alertDialog = showAlert(context, "Leyendo Exceles...");
        alertDialog.show();
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            FileInputStream fileInputStream;
            try {
                fileInputStream = new FileInputStream(context.getFilesDir() + File.separator + "Tecnico.xlsx");
                tecnicoWb = loadWorkbook(context, fileInputStream);
                fileInputStream.close();
                if(tecnicoWb == null){
                    throw new Exception("Error leyendo");
                }
            } catch (Exception e) {
                Log.w("Error leyendo", e.toString());
                alertDialog.dismiss();
                alertDialog = showAlert(context, "Error, Excel de Técnico no encontrado, abra el Excel desde su correo con la aplicación");
                setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
                return;
            }
            try {
                fileInputStream = new FileInputStream(context.getFilesDir() + File.separator + "Producto.xlsx");
                productoWb = loadWorkbook(context, fileInputStream);
                fileInputStream.close();

                if(productoWb == null){
                    throw new Exception("Error leyendo");
                }
            } catch (Exception e) {
                Log.w("Error leyendo", e.toString());
                alertDialog.dismiss();
                alertDialog = showAlert(context, "Error, Excel de Producto no encontrado, abra el Excel desde su correo con la aplicación");
                setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
                return;
            }
            try {
                fileInputStream = new FileInputStream(context.getFilesDir() + File.separator + "Cliente.xlsx");
                clienteWb = loadWorkbook(context, fileInputStream);
                fileInputStream.close();
                if(clienteWb == null){
                    throw new Exception("Error leyendo");
                }
            } catch (Exception e) {
                Log.w("Error leyendo", e.toString());
                alertDialog.dismiss();
                alertDialog = showAlert(context, "Error, Excel de Cliente no encontrado, abra el Excel desde su correo con la aplicación");
                setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
                return;
            }
            alertDialog.dismiss();
            alertDialog = showAlert(context, "Éxito leyendo Exceles");
            alertDialog.show();
        }, 1250);

    }

    public static XSSFWorkbook loadWorkbook(Context context, FileInputStream fileInputStream) {
        XSSFWorkbook wb = null;
        try {
            if (fileInputStream != null) {
                OPCPackage pkg = OPCPackage.open(fileInputStream);
                wb = new XSSFWorkbook(pkg);
                Log.w("WB creado", wb.toString());
                fileInputStream.close();
            }

        } catch (Exception e) {
            alertDialog = showAlert(context, "Error del excel, Solicite su reivisión a un Administrador, y que se reenvie");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
            return null;
        }
        Log.w("if de credenciales", "esperando");
        return wb;
    }

    public static void saveWorkbook(Context context, XSSFWorkbook wb, String fileName) {
        try {
            String filePath = context.getFilesDir() + File.separator + fileName;
            OutputStream fileOut = new FileOutputStream(filePath);
            wb.write(fileOut);
            fileOut.close();
            Log.w("saveWorkbook", "Workbook saved");

        } catch (Error e) {
            Log.w("Error: cant save wb", e.toString());
            alertDialog = showAlert(context, "Error guardando excel, comuniquese con un administrador");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();

        } catch (Exception e) {
            Log.w("Exception: cant save wb", e.toString());
            alertDialog = showAlert(context, "Error guardando excel, comuniquese con un administrador");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();

        }

    }

    public static AlertDialog showAlert(Context context, String message) {
        AlertDialog alertDialog = new AlertDialog.Builder(context).create();
        alertDialog.setTitle("Alerta");
        alertDialog.setMessage(message);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                (dialog, which) -> dialog.dismiss());
        return alertDialog;
    }

    public static void setCloseAppOnDismiss(AlertDialog alertDialog) {
        alertDialog.setOnDismissListener(dialogInterface -> activityRef.get().finishAndRemoveTask());
    }
    public static HashMap<String, String> validateCredentials(String username, String password) {
        var data = new HashMap<String, String>();
        if (validateEmptyStringArray(new String[]{username, password})) {
            data.put("code", "2");
            data.put("message", "Error, no puede haber campos vacíos");
            return data;
        }
        try {
            Sheet tecnicoSheet = tecnicoWb.getSheetAt(1);
            int indexUsuario = 0;
            int indexContraseña = 0;
            int indexCorreoGestora = 0;
            int indexNombre = 0;
            int indexPrefijo = 0;
            int indexConsecutivo = 0;
            for (Row row : tecnicoSheet) {
                if (row.getRowNum() == 0) {
                    for (Cell cell : row){
                        if (cell.toString().equalsIgnoreCase("Usuario")){
                            indexUsuario = cell.getColumnIndex();
                        }
                        if (cell.toString().equalsIgnoreCase("Contraseña")){
                            indexContraseña = cell.getColumnIndex();
                        }
                        if (cell.toString().equalsIgnoreCase("Correo Gestora")){
                            indexCorreoGestora = cell.getColumnIndex();
                        }
                        if (cell.toString().equalsIgnoreCase("Prefijo")){
                            indexPrefijo = cell.getColumnIndex();
                        }
                        if (cell.toString().equalsIgnoreCase("Consecutivo")){
                            indexConsecutivo = cell.getColumnIndex();
                        }
                    }
                    if (indexUsuario == 0 && indexContraseña == 0){
                        throw new Exception("Error");
                    }
                    continue;
                }
                if (username.equals(dataFormatter.formatCellValue(row.getCell(indexUsuario)))) {
                    if (password.equals(dataFormatter.formatCellValue(row.getCell(indexContraseña)))) {
                        data.put("code", "1");
                        data.put("message", "Inicio de sesión exitoso.");
                        data.put("nombre", dataFormatter.formatCellValue(row.getCell(indexNombre)));
                        data.put("correoGestora", dataFormatter.formatCellValue(row.getCell(indexCorreoGestora)));
                        data.put("Prefijo", dataFormatter.formatCellValue(row.getCell(indexPrefijo)));
                        if(row.getCell(indexConsecutivo) == null || row.getCell(indexConsecutivo).getCellType() == CellType.BLANK) {
                            data.put("consecutivo", "");
                        } else{
                            data.put("consecutivo", dataFormatter.formatCellValue(row.getCell(indexConsecutivo)));
                        }
                        return data;

                    }
                }
            }
        } catch (Exception e) {
            data.put("code", "0");
            data.put("message", "Error de formato de excel, solicite a un administrador que se revise integridad de datos y cabeceras.");
            return data;
        }
        data.put("code", "2");
        data.put("message", "Credenciales incorrectas, intente nuevamente.");
        return data;
    }

    //validate no empty fields on forms
    public static boolean validateEmptyStringArray(String[] fields) {
        for (String string : fields) {
            if (string.isEmpty()) {
                return true;
            }
        }
        return false;
    }

}
