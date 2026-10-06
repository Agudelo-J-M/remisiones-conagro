package io.github.agudelojm.remisionesconagro;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One of the four "Datos App" tables (Tecnicos, Productos, Clientes, Tarifas Mantenimiento),
 * held as plain strings instead of as an Apache POI {@code Sheet}.
 *
 * <p><b>Why this exists.</b> POI's XSSF model keeps a full DOM per workbook: measured against the
 * real payloads, the four tables cost ~100 MB of heap as a workbook versus ~10 MB as String[][].
 * The sync path used to hold two of those workbooks at once (the live one plus the incoming one),
 * which overflowed a 192 MB heap, and the parse alone blocked the UI thread for over a second on
 * the login screen. A table is read far more often than it is loaded, so the in-memory form is
 * what matters, and nothing above this class needs a workbook.
 *
 * <p>The shape deliberately mirrors what the old {@code Sheet} consumers did — resolve a column by
 * header name, then walk rows reading cells — so the call sites kept their logic:
 * {@link #columnIndex(String)} replaces {@code Methods.getColumnIndex(sheet, name)} and
 * {@link #value(int, int)} replaces {@code dataFormatter.formatCellValue(row.getCell(i))}.
 *
 * <p>Instances are immutable and safe to publish across threads once built.
 */
final class Tabla {

    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    private final String nombre;
    private final String[] headers;
    /** Data rows only; the header is not one of these. Rows may be shorter than {@link #headers}. */
    private final String[][] rows;
    /** Normalized header -> column. First occurrence wins, matching the old linear header scan. */
    private final Map<String, Integer> columnas;
    /** Lazily built value -> first row index, per column. Guarded by {@code this}. */
    private final Map<String, Map<String, Integer>> indices = new HashMap<>();

    private Tabla(String nombre, String[] headers, String[][] rows) {
        this.nombre = nombre;
        this.headers = headers;
        this.rows = rows;
        Map<String, Integer> cols = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String key = normalizarHeader(headers[i]);
            // putIfAbsent, not put: the old getColumnIndex returned the FIRST matching column, so a
            // duplicated header in the payload must not silently repoint an existing lookup.
            if (!key.isEmpty()) cols.putIfAbsent(key, i);
        }
        this.columnas = cols;
    }

    private static String normalizarHeader(String h) {
        return h == null ? "" : h.trim().toLowerCase(Locale.ROOT);
    }

    // -----------------------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------------------

    /** @param filas row 0 is the header; an empty list yields a table with no columns. */
    static Tabla fromRows(String nombre, List<String[]> filas) {
        if (filas == null || filas.isEmpty()) {
            return new Tabla(nombre, new String[0], new String[0][]);
        }
        // Trimmed here so all three factories agree: fromCsv and fromSheet already trim, and a
        // table that trimmed on one route but not another would not survive its own CSV round-trip.
        String[] headers = trimmed(filas.get(0));
        String[][] rows = new String[filas.size() - 1][];
        for (int i = 1; i < filas.size(); i++) rows[i - 1] = trimmed(filas.get(i));
        return new Tabla(nombre, headers, rows);
    }

    private static String[] trimmed(String[] fila) {
        if (fila == null) return new String[0];
        String[] out = new String[fila.length];
        for (int i = 0; i < fila.length; i++) out[i] = (fila[i] == null) ? "" : fila[i].trim();
        return out;
    }

    /**
     * Parses a whole CSV payload. Delimiter detection and field splitting are
     * {@link ActualizarDatos}' own quote-aware reader, so this behaves identically to the workbook
     * the sync used to assemble — including the per-table delimiter, which is load-bearing because
     * the server sends "tecnicos"/"productos" semicolon-delimited and the other two comma-delimited.
     */
    static Tabla fromCsv(String nombre, String texto) {
        List<String[]> filas = new ArrayList<>();
        char delimiter = ',';
        boolean delimiterDetected = false;
        for (String line : stripBom(texto).split("\r\n|\n|\r")) {
            if (line.isEmpty()) continue; // trailing newline / blank separator
            if (!delimiterDetected) {
                delimiter = ActualizarDatos.detectCsvDelimiter(line);
                delimiterDetected = true;
            }
            List<String> campos = ActualizarDatos.parseCsvLine(line, delimiter);
            filas.add(campos.toArray(new String[0]));
        }
        return fromRows(nombre, filas);
    }

    /**
     * Converts a POI sheet, used by the email-import path and by the one-time migration of a
     * phone whose only copy of the database is still a stored "Datos App.xlsx". The caller is
     * expected to close the workbook straight afterwards — that is the whole point.
     */
    static Tabla fromSheet(String nombre, Sheet sheet) {
        if (sheet == null) return new Tabla(nombre, new String[0], new String[0][]);
        Row header = sheet.getRow(0);
        if (header == null) return new Tabla(nombre, new String[0], new String[0][]);

        int columnCount = Math.max(0, header.getLastCellNum());
        String[] headers = new String[columnCount];
        for (int c = 0; c < columnCount; c++) headers[c] = cellValue(header.getCell(c));

        List<String[]> datos = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String[] fila = new String[columnCount];
            for (int c = 0; c < columnCount; c++) fila[c] = cellValue(row.getCell(c));
            datos.add(fila);
        }

        String[][] rows = datos.toArray(new String[0][]);
        return new Tabla(nombre, headers, rows);
    }

    private static String cellValue(Cell cell) {
        return cell == null ? "" : DATA_FORMATTER.formatCellValue(cell).trim();
    }

    /**
     * Removes a leading UTF-8 BOM. Not cosmetic: header lookups compare trimmed strings and
     * {@link String#trim()} does not strip U+FEFF, so a BOM'd first header ("﻿finca_id")
     * resolves to -1 and the finca lookups silently return nothing.
     */
    private static String stripBom(String text) {
        return (text != null && !text.isEmpty() && text.charAt(0) == '﻿') ? text.substring(1) : text;
    }

    // -----------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------

    String nombre() {
        return nombre;
    }

    /** Data rows, excluding the header. */
    int rowCount() {
        return rows.length;
    }

    int columnCount() {
        return headers.length;
    }

    String[] headers() {
        return headers.clone();
    }

    /**
     * Column for this header name, or -1. Matching is case-insensitive and ignores surrounding
     * whitespace on both sides, and it is EXACT, never a prefix — "CORREO" and "CORREO OFICINA"
     * must not resolve to one another.
     */
    int columnIndex(String colName) {
        Integer idx = columnas.get(normalizarHeader(colName));
        return idx == null ? -1 : idx;
    }

    /** Value at this row/column, or "" for any out-of-range or ragged-row access. */
    String value(int row, int col) {
        if (row < 0 || row >= rows.length || col < 0) return "";
        String[] fila = rows[row];
        if (fila == null || col >= fila.length) return "";
        String v = fila[col];
        return v == null ? "" : v;
    }

    String value(int row, String colName) {
        return value(row, columnIndex(colName));
    }

    /**
     * First row whose {@code colName} equals {@code valor}, case-insensitively, or -1.
     *
     * <p>Backed by a lazily built index, so the repeated lookups that used to be a linear scan of
     * every row (one per producto price check, one per finca resolution) are now constant time.
     * First row wins, which is what the old scan did.
     */
    synchronized int findRow(String colName, String valor) {
        int col = columnIndex(colName);
        if (col == -1 || valor == null) return -1;
        String key = normalizarHeader(colName);
        Map<String, Integer> index = indices.get(key);
        if (index == null) {
            index = new HashMap<>(Math.max(16, rows.length * 2));
            for (int r = 0; r < rows.length; r++) {
                String v = value(r, col).trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) index.putIfAbsent(v, r);
            }
            indices.put(key, index);
        }
        Integer row = index.get(valor.trim().toLowerCase(Locale.ROOT));
        return row == null ? -1 : row;
    }

    /** Every column of one row as a header -> value map, as the PDF and the client search expect. */
    Map<String, String> rowAsMap(int row) {
        Map<String, String> map = new HashMap<>();
        for (int c = 0; c < headers.length; c++) {
            String key = headers[c] == null ? "" : headers[c].trim();
            if (key.isEmpty()) continue;
            map.put(key, value(row, c));
        }
        return map;
    }

    // -----------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------

    /**
     * Serializes back to CSV for the on-disk store.
     *
     * <p>Every field is quoted unconditionally. That costs ~2 bytes per cell and buys an
     * unambiguous round-trip: because {@link ActualizarDatos#detectCsvDelimiter} ignores anything
     * inside quotes, a value containing a stray ";" can never flip the detected delimiter and
     * shift a whole table by one column.
     */
    String toCsv() {
        // The widest row, not headers.length: a payload row with MORE fields than its header is
        // readable through value(row, col), so writing only the header's width would drop those
        // fields and make the round-trip lossy. Short rows are padded instead.
        int width = headers.length;
        for (String[] fila : rows) {
            if (fila != null && fila.length > width) width = fila.length;
        }
        StringBuilder sb = new StringBuilder(Math.max(64, (rows.length + 1) * Math.max(1, width) * 12));
        appendCsvRow(sb, headers, width);
        for (String[] fila : rows) appendCsvRow(sb, fila, width);
        return sb.toString();
    }

    private static void appendCsvRow(StringBuilder sb, String[] fila, int columnCount) {
        for (int c = 0; c < columnCount; c++) {
            if (c > 0) sb.append(',');
            String v = (fila != null && c < fila.length && fila[c] != null) ? fila[c] : "";
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char ch = v.charAt(i);
                // The reader is line-based and cannot carry a field across a newline, so a cell
                // containing one is flattened to a space rather than silently splitting the row.
                if (ch == '\r' || ch == '\n') sb.append(' ');
                else if (ch == '"') sb.append("\"\"");
                else sb.append(ch);
            }
            sb.append('"');
        }
        sb.append('\n');
    }
}
