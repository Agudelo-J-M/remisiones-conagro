package io.github.agudelojm.remisionesconagro;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
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
        String fileName = Methods.getFileName(context, uri).toLowerCase();
        
        try {
            if (fileName.endsWith(".csv")) {
                return processCsvInventory(context, uri, fileName);
            }

            try (FileInputStream fis = new FileInputStream(Objects.requireNonNull(context.getContentResolver().openFileDescriptor(uri, "r")).getFileDescriptor())) {
                XSSFWorkbook wb = Methods.loadWorkbook(context, fis);
                if (wb != null) {
                    return processAndValidateWorkbook(context, wb, fileName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing input stream from URI", e);
        }
        return "Desconocido";
    }

    /**
     * Converts an incoming CSV inventory file into the internal "Inventario.xlsx" format.
     */
    private static String processCsvInventory(Context context, Uri uri, String fileName) {
        if (!fileName.toUpperCase().startsWith("INV")) {
            Methods.showFatalError(context, "Error: El archivo de inventario debe empezar por 'INV'.");
            return "Error Nombre";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getContentResolver().openInputStream(uri)))) {
            XSSFWorkbook wb = new XSSFWorkbook();
            Sheet sheet = wb.createSheet("Inventario");
            
            String line;
            int rowCount = 0;
            char delimiter = ',';
            boolean delimiterDetected = false;
            while ((line = reader.readLine()) != null) {
                // Detect the delimiter (comma or semicolon) once, from the header row.
                if (!delimiterDetected) {
                    delimiter = detectCsvDelimiter(line);
                    delimiterDetected = true;
                }
                // Quote-aware parse: a quoted field can itself contain the delimiter
                // (e.g. a product "nombre" with commas), so never use a naive split.
                List<String> columns = parseCsvLine(line, delimiter);
                Row row = sheet.createRow(rowCount++);
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.createCell(i);
                    cell.setCellValue(columns.get(i));
                }
            }
            
            if (validateInventoryStructure(context, wb)) {
                // Fresh start for CSV
                File oldTempCsv = new File(context.getFilesDir(), "Inventario_Temp.xlsx");
                if (oldTempCsv.exists()) oldTempCsv.delete();

                Methods.saveWorkbook(context, wb, "Inventario_Temp.xlsx");
                Methods.setInventarioWbToNull(); // Ensure it reloads from disk next time
                Methods.setLastInventoryFileName(context, fileName.replace(".csv", ".xlsx"));
                return "Inventario (CSV)";
            } else {
                return "Error Formato CSV";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting CSV to XLSX", e);
            Methods.showFatalError(context, "Error procesando el archivo CSV de Inventario.");
            return "Error CSV";
        }
    }

    /**
     * Identifies the workbook type, validates its structure, and saves it if correct.
     */
    private static String processAndValidateWorkbook(Context context, XSSFWorkbook wb, String originalFileName) {
        // Identify if it's the main database by checking for the "Tecnicos" sheet
        if (wb.getSheet("Tecnicos") != null) {
            if (validateMainDatabaseStructure(context, wb)) {
                Methods.saveWorkbook(context, wb, "Datos App.xlsx");
                return "Base de Datos";
            }
        } 
        // Identify if it's an inventory file (Must start with INV and have correct headers)
        else if (originalFileName.toUpperCase().startsWith("INV")) {
            // Delete ANY existing temp file to ensure fresh import
            File oldTemp = new File(context.getFilesDir(), "Inventario_Temp.xlsx");
            if (oldTemp.exists()) oldTemp.delete();

            if (validateInventoryStructure(context, wb)) {
                Methods.saveWorkbook(context, wb, "Inventario_Temp.xlsx");
                Methods.setInventarioWbToNull(); // Ensure refresh
                Methods.setLastInventoryFileName(context, originalFileName);
                return "Inventario";
            }
        } 
        else {
            Methods.showFatalError(context, "Error: El archivo no corresponde a un formato reconocido (Base de Datos o Inventario). El inventario debe empezar por 'INV'.");
        }
        return "Desconocido";
    }

    /**
     * Validates that the inventory workbook contains the required columns.
     */
    private static boolean validateInventoryStructure(Context context, XSSFWorkbook wb) {
        Sheet sheet = wb.getSheet("Inventario");
        if (sheet == null) sheet = wb.getSheetAt(0);
        return validateHeaders(context, sheet, Arrays.asList("cod_suc", "cod_bod", "ano", "referencia", "nombre", "saldo"));
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

    /**
     * Detects whether a CSV line is comma- or semicolon-delimited by counting each
     * candidate outside of quoted sections. Defaults to comma on a tie.
     */
    private static char detectCsvDelimiter(String line) {
        int commas = 0, semis = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && c == ',') {
                commas++;
            } else if (!inQuotes && c == ';') {
                semis++;
            }
        }
        return (semis > commas) ? ';' : ',';
    }

    /**
     * Parses a single CSV line into fields, honouring RFC-4180 quoting: quoted fields may
     * contain the delimiter, and a doubled quote ("") inside a quoted field is a literal
     * quote. Each returned field is trimmed. This replaces the previous naive split, which
     * corrupted rows where a text field (e.g. "nombre") contained the delimiter.
     */
    private static List<String> parseCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"'); // escaped quote
                        i++;
                    } else {
                        inQuotes = false; // closing quote
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == delimiter) {
                    fields.add(sb.toString().trim());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString().trim());
        return fields;
    }
}
