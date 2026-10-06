package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@link Tabla} — the string-table form that replaced the POI {@code Sheet} as the app's data model.
 *
 * <p>These pin the behaviors the old sheet reads depended on, because every column lookup in
 * {@link Methods} now goes through here: exact header matching, first-column-wins on a duplicated
 * header, "" for ragged or out-of-range cells, and a lossless CSV round-trip (the store writes CSV,
 * so a value that does not survive a round-trip is a value the técnico loses).
 */
public class TablaTest {

    private static final String TECNICOS =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA\n"
                    + "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com\n"
                    + "3;1000000002;LOPEZ;DIAZ;CARLOS;carlos@example.com;";

    // ----- parsing ----------------------------------------------------------------------

    @Test
    public void semicolonPayloadParsesEveryColumnAndRow() {
        Tabla t = Tabla.fromCsv("Tecnicos", TECNICOS);

        assertEquals(7, t.columnCount());
        assertEquals(2, t.rowCount());
        assertEquals("7", t.value(0, "NUMERO"));
        assertEquals("ventas.sur@example.com", t.value(0, "CORREO OFICINA"));
    }

    /** The clientes/tarifas shape: comma-delimited, quoted, BOM'd. */
    @Test
    public void quotedCommaPayloadWithBomParses() {
        Tabla t = Tabla.fromCsv("Clientes",
                "﻿\"finca_id\",\"finca_nombre\",\"direccion\"\n"
                        + "\"1822\",\"LA ESPERANZA\",\"CALLE 33B SUR N0, 45  18\"");

        assertEquals(3, t.columnCount());
        assertEquals(1, t.rowCount());
        assertEquals("CALLE 33B SUR N0, 45  18", t.value(0, "direccion"));
    }

    /**
     * The BOM strip is load-bearing, not cosmetic: String.trim() does not remove U+FEFF, so without
     * it the first column of every comma payload (finca_id, referencia) resolves to -1 and the
     * finca and producto lookups silently return nothing.
     */
    @Test
    public void bomDoesNotHideTheFirstColumn() {
        Tabla t = Tabla.fromCsv("Clientes", "﻿finca_id,finca_nombre\n1822,LA ESPERANZA");

        assertNotEquals(-1, t.columnIndex("finca_id"));
        assertEquals(0, t.columnIndex("finca_id"));
    }

    /** The blank-office-address case: a row ending in the delimiter keeps its last field as "". */
    @Test
    public void trailingEmptyFieldIsAnEmptyValueNotAMissingColumn() {
        Tabla t = Tabla.fromCsv("Tecnicos", TECNICOS);

        assertEquals("", t.value(1, "CORREO OFICINA"));
        assertNotEquals(-1, t.columnIndex("CORREO OFICINA"));
    }

    /** The server quotes a field only when it has to; that one producto row must still parse. */
    @Test
    public void quotedFieldInsideAnUnquotedSemicolonPayload() {
        Tabla t = Tabla.fromCsv("Productos",
                "referencia;nombre;precio_base\n2150017712;\"SELLO FILTRO 7\"\"\";49098");

        assertEquals(1, t.rowCount());
        assertEquals("SELLO FILTRO 7\"", t.value(0, "nombre"));
        assertEquals("49098", t.value(0, "precio_base"));
    }

    // ----- column resolution ------------------------------------------------------------

    @Test
    public void columnMatchingIsCaseAndWhitespaceInsensitive() {
        Tabla t = Tabla.fromCsv("Tecnicos", "NUMERO;  correo oficina  \n7;ventas@example.com");

        assertEquals(1, t.columnIndex("CORREO OFICINA"));
        assertEquals(1, t.columnIndex("  Correo Oficina"));
    }

    /** Exact, never a prefix: "CORREO" must not resolve to the "CORREO OFICINA" column. */
    @Test
    public void columnMatchingIsExactNotAPrefix() {
        Tabla t = Tabla.fromCsv("Tecnicos",
                "NUMERO;CORREO OFICINA;CORREO\n7;ventas@example.com;luis@example.com");

        assertEquals(2, t.columnIndex("CORREO"));
        assertEquals(1, t.columnIndex("CORREO OFICINA"));
    }

    @Test
    public void unknownColumnIsMinusOne() {
        Tabla t = Tabla.fromCsv("Tecnicos", TECNICOS);

        assertEquals(-1, t.columnIndex("NO EXISTE"));
        assertEquals("", t.value(0, "NO EXISTE"));
    }

    /**
     * A duplicated header must resolve to the FIRST occurrence, which is what the old linear header
     * scan did. Getting this backwards would repoint a live lookup at a different column.
     */
    @Test
    public void duplicateHeaderResolvesToTheFirstColumn() {
        Tabla t = Tabla.fromCsv("Productos", "referencia;nombre;referencia\nA;UNO;B");

        assertEquals(0, t.columnIndex("referencia"));
        assertEquals("A", t.value(0, "referencia"));
    }

    // ----- defensive reads --------------------------------------------------------------

    /** A short row must read as "" rather than throwing: the office column is the last one. */
    @Test
    public void raggedRowReadsAsEmptyNotAnException() {
        Tabla t = Tabla.fromCsv("Tecnicos", "NUMERO;CEDULA;CORREO OFICINA\n7;1000000003");

        assertEquals("", t.value(0, "CORREO OFICINA"));
        assertEquals("7", t.value(0, "NUMERO"));
    }

    @Test
    public void outOfRangeAccessIsEmpty() {
        Tabla t = Tabla.fromCsv("Tecnicos", TECNICOS);

        assertEquals("", t.value(99, 0));
        assertEquals("", t.value(0, 99));
        assertEquals("", t.value(-1, 0));
        assertEquals("", t.value(0, -1));
    }

    @Test
    public void emptyPayloadYieldsAnEmptyTable() {
        Tabla t = Tabla.fromCsv("Tecnicos", "");

        assertEquals(0, t.columnCount());
        assertEquals(0, t.rowCount());
        assertEquals(-1, t.columnIndex("NUMERO"));
    }

    // ----- indexed lookup ---------------------------------------------------------------

    @Test
    public void findRowLocatesByValueCaseInsensitively() {
        Tabla t = Tabla.fromCsv("Productos", "referencia;nombre\nABC123;UNO\nDEF456;DOS");

        assertEquals(0, t.findRow("referencia", "abc123"));
        assertEquals(1, t.findRow("referencia", "  DEF456 "));
        assertEquals(-1, t.findRow("referencia", "NOPE"));
        assertEquals(-1, t.findRow("no_existe", "ABC123"));
    }

    /** First row wins, matching the linear scan the index replaced. */
    @Test
    public void findRowReturnsTheFirstMatch() {
        Tabla t = Tabla.fromCsv("Productos", "referencia;nombre\nA;PRIMERO\nA;SEGUNDO");

        assertEquals("PRIMERO", t.value(t.findRow("referencia", "A"), "nombre"));
    }

    /** The index is built once and cached, so repeated lookups must stay consistent. */
    @Test
    public void repeatedFindRowIsStable() {
        Tabla t = Tabla.fromCsv("Productos", "referencia;nombre\nA;UNO\nB;DOS");

        for (int i = 0; i < 3; i++) {
            assertEquals(1, t.findRow("referencia", "B"));
            assertEquals(0, t.findRow("referencia", "A"));
        }
    }

    @Test
    public void rowAsMapCarriesEveryNamedColumn() {
        Tabla t = Tabla.fromCsv("Clientes", "finca_id,finca_nombre,persona_email\n1822,LA ESPERANZA,");

        Map<String, String> row = t.rowAsMap(0);

        assertEquals("1822", row.get("finca_id"));
        assertEquals("LA ESPERANZA", row.get("finca_nombre"));
        assertEquals("", row.get("persona_email"));
        assertEquals(3, row.size());
    }

    // ----- CSV round-trip ---------------------------------------------------------------

    /**
     * The store persists tables as CSV, so anything lost here is lost on the phone. Quoting is
     * unconditional on write precisely so a value containing the OTHER delimiter cannot flip
     * delimiter detection on the way back in and shift the whole table by a column.
     */
    @Test
    public void csvRoundTripPreservesAwkwardValues() {
        List<String[]> filas = Arrays.asList(
                new String[]{"referencia", "nombre", "precio_base"},
                new String[]{"A1", "TUBO 3\" x 1/2", "100"},
                new String[]{"A2", "SELLO; CON PUNTO Y COMA", "200"},
                new String[]{"A3", "DIRECCION, CON COMA", "300"},
                new String[]{"A4", "", "400"});
        Tabla original = Tabla.fromRows("Productos", filas);

        Tabla vuelta = Tabla.fromCsv("Productos", original.toCsv());

        assertEquals(original.columnCount(), vuelta.columnCount());
        assertEquals(original.rowCount(), vuelta.rowCount());
        for (int r = 0; r < original.rowCount(); r++) {
            for (int c = 0; c < original.columnCount(); c++) {
                assertEquals("row " + r + " col " + c, original.value(r, c), vuelta.value(r, c));
            }
        }
        assertEquals("TUBO 3\" x 1/2", vuelta.value(0, "nombre"));
        assertEquals("SELLO; CON PUNTO Y COMA", vuelta.value(1, "nombre"));
        assertEquals("DIRECCION, CON COMA", vuelta.value(2, "nombre"));
    }

    /** A semicolon-delimited payload must survive being stored (as commas) and re-read. */
    @Test
    public void semicolonPayloadSurvivesTheStoreRoundTrip() {
        Tabla original = Tabla.fromCsv("Tecnicos", TECNICOS);

        Tabla vuelta = Tabla.fromCsv("Tecnicos", original.toCsv());

        assertEquals(7, vuelta.columnCount());
        assertEquals(2, vuelta.rowCount());
        assertEquals("ventas.sur@example.com", vuelta.value(0, "CORREO OFICINA"));
        assertEquals("", vuelta.value(1, "CORREO OFICINA"));
    }

    /** The reader is line-based, so a newline inside a cell is flattened, never left to split it. */
    @Test
    public void newlineInsideAValueDoesNotSplitTheRow() {
        Tabla original = Tabla.fromRows("Clientes", Arrays.asList(
                new String[]{"finca_id", "direccion"},
                new String[]{"1", "CALLE 1\nAPTO 2"}));

        Tabla vuelta = Tabla.fromCsv("Clientes", original.toCsv());

        assertEquals(1, vuelta.rowCount());
        assertEquals("CALLE 1 APTO 2", vuelta.value(0, "direccion"));
    }

    // ----- the workbook import path -----------------------------------------------------

    /**
     * A workbook from the correo must convert to exactly what the equivalent CSV payload produces —
     * this is the invariant that lets both input routes share one validation and one data model.
     */
    @Test
    public void workbookImportMatchesTheCsvPayload() throws Exception {
        Tabla desdeCsv = Tabla.fromCsv("Tecnicos", TECNICOS);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tecnicos");
            String[] lineas = TECNICOS.split("\n");
            for (int r = 0; r < lineas.length; r++) {
                List<String> campos = ActualizarDatos.parseCsvLine(lineas[r], ';');
                Row row = sheet.createRow(r);
                for (int c = 0; c < campos.size(); c++) row.createCell(c).setCellValue(campos.get(c));
            }

            Tabla desdeSheet = Tabla.fromSheet("Tecnicos", sheet);

            assertEquals(desdeCsv.columnCount(), desdeSheet.columnCount());
            assertEquals(desdeCsv.rowCount(), desdeSheet.rowCount());
            for (int r = 0; r < desdeCsv.rowCount(); r++) {
                for (int c = 0; c < desdeCsv.columnCount(); c++) {
                    assertEquals("row " + r + " col " + c,
                            desdeCsv.value(r, c), desdeSheet.value(r, c));
                }
            }
            assertEquals(desdeCsv.columnIndex("CORREO OFICINA"),
                    desdeSheet.columnIndex("CORREO OFICINA"));
        }
    }

    /** A sheet with gaps (a blank row, a missing cell) must not shift or drop data. */
    @Test
    public void workbookImportToleratesBlankRowsAndMissingCells() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tecnicos");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("NUMERO");
            header.createCell(1).setCellValue("CORREO OFICINA");
            // Row 1 left entirely absent, row 2 has only the first cell.
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("7");

            Tabla t = Tabla.fromSheet("Tecnicos", sheet);

            assertEquals(1, t.rowCount());
            assertEquals("7", t.value(0, "NUMERO"));
            assertEquals("", t.value(0, "CORREO OFICINA"));
        }
    }

    @Test
    public void nullSheetYieldsAnEmptyTable() {
        Tabla t = Tabla.fromSheet("Tecnicos", null);

        assertEquals(0, t.rowCount());
        assertEquals(0, t.columnCount());
        assertTrue(t.toCsv().isEmpty() || t.toCsv().equals("\n"));
    }
}
