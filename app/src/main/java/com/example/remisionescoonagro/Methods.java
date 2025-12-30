package com.example.remisionescoonagro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
    public static XSSFWorkbook wb;

    private static FileInputStream fileInputStream;

    private static final DataFormatter dataFormatter = new DataFormatter();

    private static String fileAddress;


    //changes actvity context on new activity load to set alerdialongs and close app on dismiss
    public static void updateActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);

    }

    public static void inputStream(Context context) {

        StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        StorageVolume storageVolume = storageManager.getStorageVolumes().get(0);

        try {
            fileAddress = storageVolume.getDirectory().getPath() + "/Download/" + getFileName();
            fileInputStream = new FileInputStream(fileAddress);
            loadWorkbook(context);
            fileInputStream.close();
            //makeWbSaveable();
        } catch (Exception e) {
            Log.w("Error leyendo", e.toString());
            alertDialog = Methods.showAlert(context, "Error, Excel no encontrado");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }

    }
    public static String getFileName() {

        LocalDate date = LocalDate.now();
        // Define a locale (e.g., US locale)
        Locale locale = Locale.US;

        // Use the defined locale with WeekFields
        int weekOfYear = date.get(WeekFields.of(locale).weekOfYear());
        return "PEDIDOS " + weekOfYear + "-" + date.getYear() % 100 + ".xlsx";

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

    private static void loadWorkbook(Context context) {
        try {
            if (!Environment.isExternalStorageManager()) {
                alertDialog = Methods.showAlert(context, "Error, la aplicación no tiene permiso para acceder al almacenamiento. Active dicho permiso.");
                setCloseAppOnDismiss(alertDialog);
                alertDialog.show();
                return;
            }
            if (fileInputStream != null) {
                OPCPackage pkg = OPCPackage.open(fileInputStream);
                wb = new XSSFWorkbook(pkg);
                Log.w("WB creado", wb.toString());
                fileInputStream.close();

            }

            Log.w("retornó el Excel", Methods.wb.toString());

        } catch (Exception e) {
            alertDialog = showAlert(context, "Error, excel inaccesible, revise los permisos de la aplicación y que el Excel se encuentre nombrado correctamente en la carpeta /Download");
            setCloseAppOnDismiss(alertDialog);
            alertDialog.show();
        }
        Log.w("if de credenciales", "esperando");
    }


    public static void validatePermission(Context context) {
        alertDialog = showAlert(context, "Leyendo Excel");
        alertDialog.setOnDismissListener(dialogInterface -> {
            if (Environment.isExternalStorageManager()) {
                inputStream(context);
            } else {
                alertDialog = Methods.showAlert(context, "Error, no se otorgaron los permisos requeridos.");
                setCloseAppOnDismiss(alertDialog);
            }
        });
        alertDialog.show();
    }

    public static String[] validateCredentials(String username, String password) {
        if (validateEmptyStringArray(new String[]{username, password})) {
            return new String[]{"2", "Error, no puede haber campos vacíos"};
        }

        try {
            Sheet conductoresSheet = wb.getSheetAt(0);

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
