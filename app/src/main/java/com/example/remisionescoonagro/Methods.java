package com.example.remisionescoonagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
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
    public static String[] validateCredentials(String username, String password) {
        if (validateEmptyStringArray(new String[]{username, password})) {
            return new String[]{"2", "Error, no puede haber campos vacíos"};
        }


        try {
            Sheet conductoresSheet = tecnicoWb.getSheetAt(1);

            if (!dataFormatter.formatCellValue(conductoresSheet.getRow(0).getCell(0)).equalsIgnoreCase("Conductor")) {
                return new String[]{"0", "Formato incorrecto de la hoja de Excel, comuníquese con un administrador."};
            }
            for (Row row : conductoresSheet) {
                if (row.getRowNum() == 0 || row.getRowNum() == 1) {
                    continue;
                }
                if (username.equals(dataFormatter.formatCellValue(row.getCell(3)))) {
                    if (password.equals(dataFormatter.formatCellValue(row.getCell(1)))) {
                        //name, id, plate
                        return new String[]{"1", "Inicio de sesión exitoso.", dataFormatter.formatCellValue(row.getCell(2)), dataFormatter.formatCellValue(row.getCell(1)), dataFormatter.formatCellValue(row.getCell(3))};
                    }
                }
            }
        } catch (RuntimeException e) {
            return new String[]{"0", "Error, excel inaccesible, revise los permisos de la aplicación y que el Excel se encuentre nombrado correctamente en la carpeta /Download"};
        }
        return new String[]{"2", "Credenciales incorrectas, intente nuevamente"};
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
