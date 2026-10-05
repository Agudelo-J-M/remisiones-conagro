package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;

/**
 * R6 — {@link Methods#validateCredentials} must pick up the "CORREO OFICINA" column and
 * degrade cleanly when it is absent or blank.
 *
 * The Tecnicos sheet is a private static field on {@link Methods}, normally populated by
 * initializeData/reloadDatosApp (both need a Context and a file on disk), so the tests inject
 * a POI sheet into it by reflection. Rows are built through the REAL
 * {@link ActualizarDatos#parseCsvLine} and {@link ActualizarDatos#detectCsvDelimiter}, so the
 * sheet under test is shaped exactly like the one the web sync assembles.
 *
 * Paths that log are covered too: testOptions.unitTests.isReturnDefaultValues is enabled, so
 * android.util.Log is a no-op here rather than throwing "not mocked".
 */
public class ValidateCredentialsOfficeTest {

    private XSSFWorkbook wb;

    @After
    public void clearInjectedSheet() throws Exception {
        tecnicosSheetField().set(null, null);
        if (wb != null) {
            wb.close();
            wb = null;
        }
    }

    private static Field tecnicosSheetField() throws Exception {
        Field field = Methods.class.getDeclaredField("tecnicosSheet");
        field.setAccessible(true);
        return field;
    }

    /** Builds a Tecnicos sheet from CSV lines using the app's own parser, and injects it. */
    private void injectSheet(String... csvLines) throws Exception {
        wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Tecnicos");
        char delimiter = ActualizarDatos.detectCsvDelimiter(csvLines[0]);
        for (int r = 0; r < csvLines.length; r++) {
            List<String> columns = ActualizarDatos.parseCsvLine(csvLines[r], delimiter);
            Row row = sheet.createRow(r);
            for (int c = 0; c < columns.size(); c++) {
                row.createCell(c).setCellValue(columns.get(c));
            }
        }
        tecnicosSheetField().set(null, sheet);
    }

    private static final String HEADER =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA";

    @Test
    public void officeAddressIsReturnedOnSuccessfulLogin() throws Exception {
        injectSheet(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("ventas.sur@example.com", data.get("correoOficina"));
        assertEquals("7", data.get("numero"));
    }

    /** Técnico 3 on the live server today: the cell exists but is empty. */
    @Test
    public void blankOfficeCellYieldsEmptyStringAndStillLogsIn() throws Exception {
        injectSheet(HEADER,
                "3;1000000002;LOPEZ;DIAZ;CARLOS;carlos@example.com;");

        HashMap<String, String> data = Methods.validateCredentials("carlos@example.com", "1000000002");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("correoOficina"));
    }

    /**
     * The upgrade-safety guard: a phone whose stored "Datos App.xlsx" predates the column.
     * Login must still succeed and the address must read as "" (-> the manual reminder),
     * never fail the login.
     */
    @Test
    public void headerWithoutTheColumnStillLogsInWithEmptyAddress() throws Exception {
        injectSheet("NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO",
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("correoOficina"));
    }

    /**
     * "CORREO" and "CORREO OFICINA" must not be confused for one another. getColumnIndex uses
     * an exact equalsIgnoreCase, not a prefix match, so putting the office column FIRST must
     * not hijack the login column.
     */
    @Test
    public void officeColumnBeforeLoginColumnDoesNotCollide() throws Exception {
        injectSheet("NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO OFICINA;CORREO",
                "7;1000000003;GOMEZ;SOTO;LUIS;ventas.sur@example.com;luis@example.com");

        // Logging in with the OFFICE address must fail: it is not the credential column.
        assertEquals("2", Methods.validateCredentials("ventas.sur@example.com", "1000000003").get("code"));

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");
        assertEquals("1", data.get("code"));
        assertEquals("ventas.sur@example.com", data.get("correoOficina"));
    }

    /** Pins getColumnIndex's contract: header matching is case- and whitespace-insensitive. */
    @Test
    public void headerCaseAndSpacingAreTolerated() throws Exception {
        injectSheet("NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;  correo oficina  ",
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        assertEquals("ventas.sur@example.com",
                Methods.validateCredentials("luis@example.com", "1000000003").get("correoOficina"));
    }

    /** A rejected login must not carry an office address at all. */
    @Test
    public void failedLoginCarriesNoOfficeAddress() throws Exception {
        injectSheet(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "9999999999");

        assertEquals("2", data.get("code"));
        assertFalse(data.containsKey("correoOficina"));
    }

    @Test
    public void emptyCredentialsCarryNoOfficeAddress() throws Exception {
        injectSheet(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("", "");

        assertEquals("2", data.get("code"));
        assertFalse(data.containsKey("correoOficina"));
    }

    /**
     * validateCredentials trims the cell itself, which matters because a value written straight
     * into the workbook (rather than through the CSV parser) is not pre-trimmed.
     */
    @Test
    public void untrimmedCellValueIsTrimmed() throws Exception {
        wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Tecnicos");
        Row header = sheet.createRow(0);
        String[] headers = {"NUMERO", "CEDULA", "1 APELLIDO", "2 APELLIDO", "NOMBRES", "CORREO", "CORREO OFICINA"};
        for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
        Row row = sheet.createRow(1);
        String[] values = {"7", "1000000003", "GOMEZ", "SOTO", "LUIS", "luis@example.com", "  ventas.sur@example.com  "};
        for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
        tecnicosSheetField().set(null, sheet);

        assertEquals("ventas.sur@example.com",
                Methods.validateCredentials("luis@example.com", "1000000003").get("correoOficina"));
    }

    /**
     * A multi-address office cell must reach the mail client as two recipients. This is the
     * end-to-end version of the split: CSV cell -> sheet -> login -> EXTRA_EMAIL array.
     */
    @Test
    public void multiAddressOfficeCellBecomesTwoRecipients() throws Exception {
        injectSheet(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;\"oficina@example.com, conta@example.com\"");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("oficina@example.com, conta@example.com", data.get("correoOficina"));
        assertArrayEquals(new String[]{"cli@example.com", "oficina@example.com", "conta@example.com"},
                Methods.buildEmailRecipients("cli@example.com", Methods.getCorreoOficina(data)));
    }

    /**
     * A required column really missing is an error, not a silent fallback: code 0, and no
     * office address. (Reaches the exception branch, which logs.)
     */
    @Test
    public void missingRequiredColumnIsAnError() throws Exception {
        injectSheet("NUMERO;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA",
                "7;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("0", data.get("code"));
        assertFalse(data.containsKey("correoOficina"));
    }

    /** The whole fixture, to confirm the mixed blank/filled table behaves row by row. */
    @Test
    public void fixtureTableResolvesEveryRow() throws Exception {
        injectSheet(CsvParsingTest.fixtureLines().toArray(new String[0]));

        assertEquals("ventas.norte@example.com",
                Methods.validateCredentials("ana.perez@example.com", "1000000001").get("correoOficina"));
        assertEquals("",
                Methods.validateCredentials("carlos.lopez@example.com", "1000000002").get("correoOficina"));
        assertEquals("ventas.sur@example.com",
                Methods.validateCredentials("luis.gomez@example.com", "1000000003").get("correoOficina"));
        assertEquals("ventas.centro@example.com",
                Methods.validateCredentials("sofia.ruiz@example.com", "1000000004").get("correoOficina"));
    }
}
