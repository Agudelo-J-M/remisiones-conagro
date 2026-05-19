package io.github.agudelojm.remisionesconagro;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Handles the update of internal Excel workbooks when a new file is opened via URI.
 * Validates the structure of "Datos App.xlsx" to ensure it contains all required sheets and columns.
 */
public class ActualizarDatos {

    private static final String TAG = "ActualizarDatos";

    /**
     * Loads a workbook from a given Uri and triggers the update process after validation.
     */
    public static String setFileInputStream(Context context, Uri uri) {
        try (FileInputStream fis = new FileInputStream(Objects.requireNonNull(context.getContentResolver().openFileDescriptor(uri, "r")).getFileDescriptor())) {
            XSSFWorkbook wb = Methods.loadWorkbook(context, fis);
            if (wb != null) {
                return processAndValidateWorkbook(context, wb);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing input stream from URI", e);
        }
        return "Desconocido";
    }

    /**
     * Identifies the workbook type, validates its structure, and saves it if correct.
     */
    private static String processAndValidateWorkbook(Context context, XSSFWorkbook wb) {
        // Identify if it's the main database by checking for the "Tecnicos" sheet
        if (wb.getSheet("Tecnicos") != null) {
            if (validateMainDatabaseStructure(context, wb)) {
                Methods.saveWorkbook(context, wb, "Datos App.xlsx");
                return "Base de Datos";
            }
        } 
        // Identify if it's an inventory file
        else if (wb.getSheet("Inventario") != null) {
            Methods.saveWorkbook(context, wb, "Inventario.xlsx");
            return "Inventario";
        } 
        else {
            Methods.showFatalError(context, "Error: El archivo no corresponde a un formato reconocido (Base de Datos o Inventario).");
        }
        return "Desconocido";
    }

    /**
     * Validates that the workbook contains all 4 required sheets and their specific headers.
     * Column names are exactly matched against the provided structure.
     */
    private static boolean validateMainDatabaseStructure(Context context, XSSFWorkbook wb) {
        try {
            // 1. Check for required sheets
            List<String> requiredSheets = Arrays.asList("Tecnicos", "Productos", "Clientes", "Tarifas Mantenimiento");
            for (String sheetName : requiredSheets) {
                if (wb.getSheet(sheetName) == null) {
                    Methods.showFatalError(context, "Error: Falta la hoja requerida '" + sheetName + "' en el archivo.");
                    return false;
                }
            }

            // 2. Validate columns for each sheet with official header names provided by user
            boolean isValid = validateHeaders(context, wb.getSheet("Tecnicos"), 
                    Arrays.asList("NUMERO", "CEDULA", "1 APELLIDO", "2 APELLIDO", "NOMBRES", "CORREO")) &&
                    validateHeaders(context, wb.getSheet("Productos"), 
                    Arrays.asList("referencia", "nombre", "seccion", "iva", "activo", "empresa", "precio_base", "descuento_inservice", "fecha_inicio_vigencia")) &&
                    validateHeaders(context, wb.getSheet("Clientes"), 
                    Arrays.asList("finca_id", "empresa", "direccion", "zona", "ciu", "numero_equipo", "clase_equipo", "persona_doc", "persona_nombre", "persona_telefono", "persona_email")) &&
                    validateHeaders(context, wb.getSheet("Tarifas Mantenimiento"), 
                    Arrays.asList("empresa", "referencia", "descripcion", "precio_base", "activo"));

            return isValid;

        } catch (Exception e) {
            Log.e(TAG, "Exception during validation", e);
            Methods.showFatalError(context, "Error inesperado validando la integridad del archivo.");
            return false;
        }
    }

    /**
     * Helper to verify if a sheet contains all specified headers in its first row.
     */
    private static boolean validateHeaders(Context context, Sheet sheet, List<String> requiredHeaders) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            Methods.showFatalError(context, "Error: La hoja '" + sheet.getSheetName() + "' está vacía o no tiene cabeceras.");
            return false;
        }

        for (String required : requiredHeaders) {
            boolean found = false;
            for (Cell cell : headerRow) {
                if (cell != null && cell.toString().trim().equalsIgnoreCase(required)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Methods.showFatalError(context, "Error de formato: Falta la columna '" + required + "' en la hoja '" + sheet.getSheetName() + "'.");
                return false;
            }
        }
        return true;
    }
}
