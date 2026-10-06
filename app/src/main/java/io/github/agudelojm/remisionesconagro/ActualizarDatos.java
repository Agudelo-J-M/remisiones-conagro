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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Handles the update of internal Excel workbooks when a new file is opened via URI.
 * Validates the structure of "Datos App.xlsx" to ensure it contains all required sheets and columns.
 */
public class ActualizarDatos {

    private static final String TAG = "ActualizarDatos";

    /**
     * Official header names per table, as provided by the client. Declared once so the two checks
     * that use them — the dialog-raising validation on import and the silent one on load — cannot
     * drift apart.
     *
     * Superset columns are deliberately NOT here: "CORREO OFICINA" (R6) and everything else the
     * server sends beyond these lists. A database saved before a column existed must keep working;
     * making a new column required would fire the app-closing fatal dialog on every older file and
     * on any server rollback.
     */
    private static final Map<String, List<String>> REQUIRED_HEADERS = new HashMap<>();
    static {
        REQUIRED_HEADERS.put("Tecnicos",
                Arrays.asList("NUMERO", "CEDULA", "1 APELLIDO", "2 APELLIDO", "NOMBRES", "CORREO"));
        REQUIRED_HEADERS.put("Productos",
                Arrays.asList("referencia", "nombre", "seccion", "iva", "activo", "empresa",
                        "precio_base", "descuento_inservice", "fecha_inicio_vigencia"));
        REQUIRED_HEADERS.put("Clientes",
                Arrays.asList("finca_id", "empresa", "direccion", "zona", "ciu", "numero_equipo",
                        "clase_equipo", "persona_doc", "persona_nombre", "persona_telefono",
                        "persona_email"));
        REQUIRED_HEADERS.put("Tarifas Mantenimiento",
                Arrays.asList("empresa", "referencia", "descripcion", "precio_base", "activo"));
    }

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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getContentResolver().openInputStream(uri)));
             // Closed on every exit, like the import path above: nothing retains this workbook
             // (the inventory is reloaded from disk afterwards), so its package should be released.
             XSSFWorkbook wb = new XSSFWorkbook()) {
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
        // This method owns the workbook and closes it on every exit (the finally below), so the
        // OPC package and its temp files are released rather than left to GC.
        try {
            // Identify if it's the main database by checking for the "Tecnicos" sheet
            if (wb.getSheet("Tecnicos") != null) {
                // Convert to the four plain-string tables, then close the workbook BEFORE
                // validating and saving. Holding both costs ~109 MB (POI) on top of the ~8 MB of
                // tables, and this import is the one remaining place POI touches this data —
                // releasing it here is what keeps the peak off a low-RAM phone.
                Map<String, Tabla> tablas = DatosAppStore.fromWorkbook(wb);
                Methods.closeQuietly(wb);
                wb = null;

                if (tablas == null) {
                    Methods.showFatalError(context, "Error: Falta una de las hojas requeridas "
                            + "(Tecnicos, Productos, Clientes, Tarifas Mantenimiento) en el archivo.");
                    return "Desconocido";
                }
                if (ingestDatosAppTables(context, tablas)) {
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
        } finally {
            // Null on the database path, which already closed it; null-safe either way.
            Methods.closeQuietly(wb);
        }
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
     * Validates the four tables and, if they hold up, makes them the app's data: persisted to the
     * store and swapped in for the current session.
     *
     * <p>Shared by both input routes — the correo (a workbook, converted first) and the web sync
     * (one CSV per table) — so a database arriving either way is held to exactly the same structure
     * checks the workbook validation always applied.
     *
     * @return true once the tables are validated, persisted and live.
     */
    static boolean ingestDatosAppTables(Context context, Map<String, Tabla> tablas) {
        if (!validateDatosAppStructure(context, tablas)) return false;
        if (!DatosAppStore.save(context, tablas)) {
            Methods.showFatalError(context, "Error guardando la base de datos, comuniquese con un administrador");
            return false;
        }
        Methods.adoptDatosApp(tablas);
        return true;
    }

    /**
     * Validates that all 4 required tables are present with their specific headers.
     * Column names are exactly matched against the provided structure.
     *
     * Superset columns are deliberately NOT required — "CORREO OFICINA" (R6) and everything else
     * the server sends beyond this list. A database saved before a column existed must keep
     * working; making a new column required would fire the app-closing fatal dialog on every
     * older file and on any server rollback.
     */
    private static boolean validateDatosAppStructure(Context context, Map<String, Tabla> tablas) {
        try {
            if (tablas == null) {
                Methods.showFatalError(context, "Error: No se pudo leer la base de datos.");
                return false;
            }
            // 1. Check for required tables
            for (String[] t : DatosAppStore.TABLES) {
                if (tablas.get(t[1]) == null) {
                    Methods.showFatalError(context, "Error: Falta la hoja requerida '" + t[1] + "' en el archivo.");
                    return false;
                }
            }

            // 2. Validate columns for each table with official header names provided by user
            for (String[] t : DatosAppStore.TABLES) {
                if (!validateHeaders(context, tablas.get(t[1]), REQUIRED_HEADERS.get(t[1]))) {
                    return false;
                }
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Exception during validation", e);
            Methods.showFatalError(context, "Error inesperado validando la integridad del archivo.");
            return false;
        }
    }

    /**
     * The same structure check without any dialogs, for data read back off the disk rather than
     * freshly received.
     *
     * Needed because a stored table that is present but unusable (an empty or truncated CSV) would
     * otherwise be adopted as if it were fine, and every login would then fail with "error de
     * formato" and no way out — where a corrupt workbook used to reach the "abra el archivo desde
     * su correo" dialog. A data row is required too: these four tables always have rows, so an
     * empty Tecnicos is corruption, not a legitimate state.
     */
    static boolean datosAppTablesAreUsable(Map<String, Tabla> tablas) {
        if (tablas == null) return false;
        for (String[] t : DatosAppStore.TABLES) {
            Tabla tabla = tablas.get(t[1]);
            if (tabla == null || tabla.rowCount() < 1) {
                Log.w(TAG, "Stored table '" + t[1] + "' is missing or has no data rows.");
                return false;
            }
            for (String required : REQUIRED_HEADERS.get(t[1])) {
                if (tabla.columnIndex(required) == -1) {
                    Log.w(TAG, "Stored table '" + t[1] + "' has no '" + required + "' column.");
                    return false;
                }
            }
        }
        return true;
    }

    /** Verifies a table exposes every required column, keeping the original per-column message. */
    private static boolean validateHeaders(Context context, Tabla tabla, List<String> requiredHeaders) {
        if (tabla == null || tabla.columnCount() == 0) {
            Methods.showFatalError(context, "Error: La hoja '" + (tabla != null ? tabla.nombre() : "?")
                    + "' esta vacia o no tiene cabeceras.");
            return false;
        }
        for (String required : requiredHeaders) {
            if (tabla.columnIndex(required) == -1) {
                Methods.showFatalError(context, "Error de formato: Falta la columna '" + required
                        + "' en la hoja '" + tabla.nombre() + "'.");
                return false;
            }
        }
        return true;
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
     *
     * Package-private so {@link InventarioSync} can reuse it on the CSVs it downloads from
     * the Datos App endpoint (R5) instead of keeping a second CSV implementation.
     */
    static char detectCsvDelimiter(String line) {
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
     *
     * Package-private so {@link InventarioSync} can reuse it on the CSVs it downloads from
     * the Datos App endpoint (R5) instead of keeping a second CSV implementation.
     */
    static List<String> parseCsvLine(String line, char delimiter) {
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
