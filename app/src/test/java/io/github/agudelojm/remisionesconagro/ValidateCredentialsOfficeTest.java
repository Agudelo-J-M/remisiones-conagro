package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Methods#validateCredentials} — reading the "CORREO OFICINA" column (R6), degrading cleanly
 * when it is absent or blank, and refusing an ambiguous login.
 *
 * <p>The Tecnicos table is installed through the real {@link Methods#adoptDatosApp}, the same entry
 * point both input routes use, so these tests exercise the production path for getting data into the
 * app. (They used to reach into a private static {@code Sheet} field by reflection, which the
 * string-table refactor made unnecessary.)
 *
 * <p>Paths that log are covered too: testOptions.unitTests.isReturnDefaultValues is enabled, so
 * android.util.Log is a no-op here rather than throwing "not mocked".
 */
public class ValidateCredentialsOfficeTest {

    private static final String HEADER =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA";

    /**
     * The adopted tables are static app state, so each test resets them rather than relying on
     * ordering. Empty tables, not null: no production seam exists to un-adopt a database, and none
     * is added just for tests — an empty table reaches the same "columns missing" branch.
     */
    @After
    public void resetTables() {
        installEmptyTables();
    }

    private static void installEmptyTables() {
        Map<String, Tabla> vacias = new LinkedHashMap<>();
        for (String[] t : DatosAppStore.TABLES) vacias.put(t[1], Tabla.fromCsv(t[1], ""));
        Methods.adoptDatosApp(vacias);
    }

    /** Installs a Tecnicos table built from CSV lines, plus the stubs adoptDatosApp requires. */
    private static void installTecnicos(String... csvLines) {
        Map<String, Tabla> tablas = new LinkedHashMap<>();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos", String.join("\n", csvLines)));
        // adoptDatosApp is all-or-nothing by design, so the other three must be present. They are
        // not read by validateCredentials; minimal shapes are enough.
        tablas.put("Productos", Tabla.fromCsv("Productos", "referencia;nombre\n1;UNO"));
        tablas.put("Clientes", Tabla.fromCsv("Clientes", "finca_id;finca_nombre\n1;LA FINCA"));
        tablas.put("Tarifas Mantenimiento",
                Tabla.fromCsv("Tarifas Mantenimiento", "referencia;descripcion\n1;SERVICIO"));
        Methods.adoptDatosApp(tablas);
    }

    @Test
    public void officeAddressIsReturnedOnSuccessfulLogin() {
        installTecnicos(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("ventas.sur@example.com", data.get("correoOficina"));
        assertEquals("7", data.get("numero"));
        assertEquals("GOMEZ SOTO", data.get("apellidos"));
    }

    /** Técnico 3 on the live server today: the cell exists but is empty. */
    @Test
    public void blankOfficeCellYieldsEmptyStringAndStillLogsIn() {
        installTecnicos(HEADER,
                "3;1000000002;LOPEZ;DIAZ;CARLOS;carlos@example.com;");

        HashMap<String, String> data = Methods.validateCredentials("carlos@example.com", "1000000002");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("correoOficina"));
    }

    /**
     * The upgrade-safety guard: a phone whose stored database predates the column. Login must still
     * succeed and the address must read as "" (-> the manual reminder), never fail the login.
     */
    @Test
    public void headerWithoutTheColumnStillLogsInWithEmptyAddress() {
        installTecnicos("NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO",
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("correoOficina"));
    }

    /**
     * "CORREO" and "CORREO OFICINA" must not be confused for one another: column matching is an
     * exact comparison, not a prefix one, so putting the office column FIRST must not hijack the
     * credential column.
     */
    @Test
    public void officeColumnBeforeLoginColumnDoesNotCollide() {
        installTecnicos("NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO OFICINA;CORREO",
                "7;1000000003;GOMEZ;SOTO;LUIS;ventas.sur@example.com;luis@example.com");

        // Logging in with the OFFICE address must fail: it is not the credential column.
        assertEquals("2", Methods.validateCredentials("ventas.sur@example.com", "1000000003").get("code"));

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");
        assertEquals("1", data.get("code"));
        assertEquals("ventas.sur@example.com", data.get("correoOficina"));
    }

    /** Pins the column-matching contract: case- and whitespace-insensitive. */
    @Test
    public void headerCaseAndSpacingAreTolerated() {
        installTecnicos("NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;  correo oficina  ",
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        assertEquals("ventas.sur@example.com",
                Methods.validateCredentials("luis@example.com", "1000000003").get("correoOficina"));
    }

    /** A rejected login must not carry an office address at all. */
    @Test
    public void failedLoginCarriesNoOfficeAddress() {
        installTecnicos(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "9999999999");

        assertEquals("2", data.get("code"));
        assertFalse(data.containsKey("correoOficina"));
    }

    @Test
    public void emptyCredentialsCarryNoOfficeAddress() {
        installTecnicos(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("", "");

        assertEquals("2", data.get("code"));
        assertFalse(data.containsKey("correoOficina"));
    }

    /** Values are trimmed on the way in, so a padded cell still yields a usable recipient. */
    @Test
    public void untrimmedCellValueIsTrimmed() {
        installTecnicos(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;   ventas.sur@example.com   ");

        assertEquals("ventas.sur@example.com",
                Methods.validateCredentials("luis@example.com", "1000000003").get("correoOficina"));
    }

    /**
     * A multi-address office cell must reach the mail client as two recipients: CSV cell -> table
     * -> login -> EXTRA_EMAIL array.
     */
    @Test
    public void multiAddressOfficeCellBecomesTwoRecipients() {
        installTecnicos(HEADER,
                "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;\"oficina@example.com, conta@example.com\"");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("oficina@example.com, conta@example.com", data.get("correoOficina"));
        assertArrayEquals(new String[]{"cli@example.com", "oficina@example.com", "conta@example.com"},
                Methods.buildEmailRecipients("cli@example.com", Methods.getCorreoOficina(data)));
    }

    /** A required column really missing is an error, not a silent fallback: code 0. */
    @Test
    public void missingRequiredColumnIsAnError() {
        installTecnicos("NUMERO;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA",
                "7;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("0", data.get("code"));
        assertFalse(data.containsKey("correoOficina"));
    }

    /** An empty Tecnicos table (a server hiccup, a truncated store) is an error, not a crash. */
    @Test
    public void emptyTableIsAnErrorNotACrash() {
        installEmptyTables();

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("0", data.get("code"));
    }

    // ----- apellido columns are not required ---------------------------------------------

    /**
     * "1 APELLIDO"/"2 APELLIDO" are not in the required-column check, so a database missing one
     * must shorten the name rather than fail every login — reading cell -1 used to throw straight
     * into the catch block and return code 0 for everybody.
     */
    @Test
    public void missingApellidoColumnsStillLogIn() {
        installTecnicos("NUMERO;CEDULA;NOMBRES;CORREO;CORREO OFICINA",
                "7;1000000003;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("apellidos"));
        assertEquals("ventas.sur@example.com", data.get("correoOficina"));
    }

    /** One apellido present, the other absent: no stray separator left behind. */
    @Test
    public void oneApellidoColumnYieldsNoTrailingSpace() {
        installTecnicos("NUMERO;CEDULA;1 APELLIDO;NOMBRES;CORREO",
                "7;1000000003;GOMEZ;LUIS;luis@example.com");

        assertEquals("GOMEZ",
                Methods.validateCredentials("luis@example.com", "1000000003").get("apellidos"));
    }

    // ----- ambiguous identities ----------------------------------------------------------

    /**
     * Two rows sharing a correo but NOT a cédula is a real, current state on the server (two
     * técnicos registered under one address). Each must resolve to their OWN row — número, office
     * address and all — because the cédula separates them.
     */
    @Test
    public void sameCorreoDifferentCedulasResolveToTheirOwnRows() {
        installTecnicos(HEADER,
                "1;1000000001;PEREZ;GOMEZ;ANA;compartido@example.com;ventas.norte@example.com",
                "13;1000000013;RUIZ;MORA;SOFIA;compartido@example.com;ventas.centro@example.com");

        HashMap<String, String> ana = Methods.validateCredentials("compartido@example.com", "1000000001");
        HashMap<String, String> sofia = Methods.validateCredentials("compartido@example.com", "1000000013");

        assertEquals("1", ana.get("code"));
        assertEquals("1", ana.get("numero"));
        assertEquals("ventas.norte@example.com", ana.get("correoOficina"));

        assertEquals("1", sofia.get("code"));
        assertEquals("13", sofia.get("numero"));
        assertEquals("ventas.centro@example.com", sofia.get("correoOficina"));

        assertNotEquals(ana.get("numero"), sofia.get("numero"));
    }

    /**
     * Two rows sharing BOTH correo and cédula are indistinguishable. Taking the first silently
     * would hand this técnico the other row's NUMERO — which is the bodega code, the consecutivo
     * prefix and the office address — so the login must be refused outright.
     */
    @Test
    public void duplicateCorreoAndCedulaIsRefused() {
        installTecnicos(HEADER,
                "1;1000000001;PEREZ;GOMEZ;ANA;ana@example.com;ventas.norte@example.com",
                "13;1000000001;RUIZ;MORA;SOFIA;ana@example.com;ventas.centro@example.com");

        HashMap<String, String> data = Methods.validateCredentials("ana@example.com", "1000000001");

        assertEquals("0", data.get("code"));
        assertFalse(data.containsKey("numero"));
        assertFalse(data.containsKey("correoOficina"));
    }

    /** The duplicate check must not fire on a correo collision alone. */
    @Test
    public void duplicateCorreoAloneIsNotRefused() {
        installTecnicos(HEADER,
                "1;1000000001;PEREZ;GOMEZ;ANA;ana@example.com;ventas.norte@example.com",
                "13;1000000013;RUIZ;MORA;SOFIA;ana@example.com;ventas.centro@example.com");

        assertEquals("1", Methods.validateCredentials("ana@example.com", "1000000013").get("code"));
    }

    // ----- the whole fixture -------------------------------------------------------------

    /** The mixed blank/filled table, row by row. */
    @Test
    public void fixtureTableResolvesEveryRow() throws Exception {
        List<String> lines = CsvParsingTest.fixtureLines();
        installTecnicos(lines.toArray(new String[0]));

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
