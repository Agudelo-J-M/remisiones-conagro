package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The CSV reader the web sync depends on ({@link ActualizarDatos#detectCsvDelimiter} and
 * {@link ActualizarDatos#parseCsvLine}).
 *
 * Worth its own class because the server's payload shape drifted: "tecnicos" and "productos"
 * now arrive semicolon-delimited and unquoted, while "clientes" and "tarifas" are still
 * comma-delimited and quoted. Per-table delimiter detection is therefore load-bearing for the
 * database path, not just for the bodega CSV.
 */
public class CsvParsingTest {

    private static final String TECNICOS_HEADER =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA";

    private static final String CLIENTES_HEADER =
            "\"finca_id\",\"finca_nombre\",\"empresa\",\"direccion\",\"zona\",\"ciu\"";

    /** The synthetic fixture, shaped like the live "tecnicos" payload. */
    static List<String> fixtureLines() throws Exception {
        InputStream in = CsvParsingTest.class.getClassLoader()
                .getResourceAsStream("tecnicos_fixture.csv");
        assertNotNull("tecnicos_fixture.csv missing from test resources", in);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    // ----- detectCsvDelimiter -----------------------------------------------------------

    @Test
    public void semicolonTableIsDetectedAsSemicolon() {
        assertEquals(';', ActualizarDatos.detectCsvDelimiter(TECNICOS_HEADER));
    }

    @Test
    public void quotedCommaTableIsDetectedAsComma() {
        assertEquals(',', ActualizarDatos.detectCsvDelimiter(CLIENTES_HEADER));
    }

    /** Delimiters inside quotes must not be counted, or a quoted field could flip detection. */
    @Test
    public void semicolonsInsideQuotesDoNotFlipDetection() {
        assertEquals(',', ActualizarDatos.detectCsvDelimiter("\"a;b;c;d\",\"e\""));
    }

    /** Documents the tie-break: comma wins when counts are equal. */
    @Test
    public void equalCountsFallBackToComma() {
        assertEquals(',', ActualizarDatos.detectCsvDelimiter("a,b;c"));
    }

    // ----- parseCsvLine -----------------------------------------------------------------

    @Test
    public void semicolonRowSplitsIntoEveryField() {
        List<String> fields = ActualizarDatos.parseCsvLine(
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com", ';');

        assertEquals(7, fields.size());
        assertEquals("7", fields.get(0));
        assertEquals("ventas.sur@example.com", fields.get(6));
    }

    /**
     * The blank-office-address case: a row ending in the delimiter must still produce the last
     * field, as "". If it collapsed to 6 fields the office column would read as missing data
     * rather than as an empty value.
     */
    @Test
    public void trailingEmptyFieldIsPreserved() {
        List<String> fields = ActualizarDatos.parseCsvLine(
                "3;1000000002;LOPEZ;DIAZ;CARLOS;carlos@example.com;", ';');

        assertEquals(7, fields.size());
        assertEquals("", fields.get(6));
    }

    /** A comma inside an unquoted semicolon row is just data. */
    @Test
    public void commaInsideSemicolonRowIsNotASeparator() {
        List<String> fields = ActualizarDatos.parseCsvLine(
                "7;1000000003;GOMEZ, JR;SOTO;LUIS;luis@example.com;ventas.sur@example.com", ';');

        assertEquals(7, fields.size());
        assertEquals("GOMEZ, JR", fields.get(2));
    }

    /** The clientes/tarifas shape: a quoted field keeps its commas. */
    @Test
    public void quotedFieldKeepsItsCommas() {
        List<String> fields = ActualizarDatos.parseCsvLine(
                "\"1822\",\"CALLE 33B SUR N0, 45  18\",\"Conagro\"", ',');

        assertEquals(3, fields.size());
        assertEquals("CALLE 33B SUR N0, 45  18", fields.get(1));
    }

    @Test
    public void doubledQuoteBecomesALiteralQuote() {
        List<String> fields = ActualizarDatos.parseCsvLine("\"say \"\"hi\"\"\",\"b\"", ',');

        assertEquals(2, fields.size());
        assertEquals("say \"hi\"", fields.get(0));
    }

    @Test
    public void fieldsAreTrimmed() {
        List<String> fields = ActualizarDatos.parseCsvLine("  a  ;  b  ", ';');

        assertEquals("a", fields.get(0));
        assertEquals("b", fields.get(1));
    }

    /**
     * A stray CR left on the last field by a CRLF payload must not end up inside the office
     * address. Production strips line endings when splitting, but the trim is the backstop.
     */
    @Test
    public void strayCarriageReturnIsTrimmedOffTheLastField() {
        List<String> fields = ActualizarDatos.parseCsvLine(
                "7;ventas.sur@example.com\r", ';');

        assertEquals("ventas.sur@example.com", fields.get(1));
    }

    // ----- the fixture, end to end ------------------------------------------------------

    /**
     * Ragged-row guard: every row of a semicolon payload must parse to the header's field
     * count. This is the regression that would silently shift the office address into another
     * column if the server ever re-quoted these tables.
     */
    @Test
    public void everyFixtureRowHasTheHeaderFieldCount() throws Exception {
        List<String> lines = fixtureLines();
        char delimiter = ActualizarDatos.detectCsvDelimiter(lines.get(0));
        assertEquals(';', delimiter);

        int expected = ActualizarDatos.parseCsvLine(lines.get(0), delimiter).size();
        assertEquals(7, expected);

        for (int i = 1; i < lines.size(); i++) {
            assertEquals("row " + i + " is ragged: " + lines.get(i),
                    expected, ActualizarDatos.parseCsvLine(lines.get(i), delimiter).size());
        }
    }

    @Test
    public void fixtureHeaderExposesTheOfficeColumnLast() throws Exception {
        List<String> header = ActualizarDatos.parseCsvLine(fixtureLines().get(0), ';');

        assertEquals("CORREO", header.get(5));
        assertEquals("CORREO OFICINA", header.get(6));
    }
}
