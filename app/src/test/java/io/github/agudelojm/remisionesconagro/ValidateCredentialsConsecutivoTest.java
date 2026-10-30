package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R8 §4 read side, end to end through the production entry point: the CONSECUTIVO column is
 * resolved by {@link Methods#validateCredentials}, lands in the session map, and is read back by
 * {@link Methods#getSeqServidor}.
 *
 * <p>Same harness as {@link ValidateCredentialsOfficeTest}: the Tecnicos table is installed with
 * the real {@link Methods#adoptDatosApp}. The point of testing it this way is that the column is
 * a <em>superset</em> column — an older stored database or a server rollback must still log
 * everyone in, so the degradation path matters as much as the happy one.
 */
public class ValidateCredentialsConsecutivoTest {

    /** 8 columns, the live server's shape since 2026-10-15. */
    private static final String HEADER_CON =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA;CONSECUTIVO";

    /** 7 columns: a phone whose stored database predates R8. */
    private static final String HEADER_SIN =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA";

    @After
    public void resetTables() {
        Map<String, Tabla> vacias = new LinkedHashMap<>();
        for (String[] t : DatosAppStore.TABLES) vacias.put(t[1], Tabla.fromCsv(t[1], ""));
        Methods.adoptDatosApp(vacias);
    }

    private static void installTecnicos(String... csvLines) {
        Map<String, Tabla> tablas = new LinkedHashMap<>();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos", String.join("\n", csvLines)));
        tablas.put("Productos", Tabla.fromCsv("Productos", "referencia;nombre\n1;UNO"));
        tablas.put("Clientes", Tabla.fromCsv("Clientes", "finca_id;finca_nombre\n1;LA FINCA"));
        tablas.put("Tarifas Mantenimiento",
                Tabla.fromCsv("Tarifas Mantenimiento", "referencia;descripcion\n1;SERVICIO"));
        Methods.adoptDatosApp(tablas);
    }

    @Test
    public void seededValueReachesTheSessionAndParses() {
        installTecnicos(HEADER_CON,
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com;3-000001");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("3-000001", data.get("consecutivoServidor"));
        assertEquals(1, Methods.getSeqServidor(data));
    }

    @Test
    public void aRealCounterParses() {
        installTecnicos(HEADER_CON,
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com;3-000450");

        assertEquals(450, Methods.getSeqServidor(
                Methods.validateCredentials("luis@example.com", "1000000003")));
    }

    /** The test row on the live server: the cell exists and is empty. Never an error. */
    @Test
    public void blankCellIsNeverClaimed() {
        installTecnicos(HEADER_CON,
                "11;1;apellido1;apellido2;nombre;test@example.com;;");

        HashMap<String, String> data = Methods.validateCredentials("test@example.com", "1");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("consecutivoServidor"));
        assertEquals(-1, Methods.getSeqServidor(data));
    }

    /** A database from before the column existed: login must still work, numbering goes local. */
    @Test
    public void absentColumnStillLogsIn() {
        installTecnicos(HEADER_SIN,
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("", data.get("consecutivoServidor"));
        assertEquals(-1, Methods.getSeqServidor(data));
        // The office address must be unaffected by the new column's absence.
        assertEquals("ventas.sur@example.com", data.get("correoOficina"));
    }

    @Test
    public void headerCaseAndSpacingAreTolerated() {
        installTecnicos(
                "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO;CORREO OFICINA;  consecutivo  ",
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com;3-000045");

        assertEquals(45, Methods.getSeqServidor(
                Methods.validateCredentials("luis@example.com", "1000000003")));
    }

    /** A value belonging to another técnico must not be adopted, but must not break the login. */
    @Test
    public void foreignPrefixIsIgnoredWithoutFailingTheLogin() {
        installTecnicos(HEADER_CON,
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com;7-000450");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1", data.get("code"));
        assertEquals("7-000450", data.get("consecutivoServidor"));
        assertEquals(-1, Methods.getSeqServidor(data));
    }

    @Test
    public void failedLoginCarriesNoConsecutivo() {
        installTecnicos(HEADER_CON,
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com;3-000045");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "9999");

        assertEquals("2", data.get("code"));
        assertEquals(null, data.get("consecutivoServidor"));
        assertEquals(-1, Methods.getSeqServidor(data));
    }

    /** The cédula, which Phase 2's claim needs alongside the número, is already exported. */
    @Test
    public void sessionCarriesTheCedulaForTheClaim() {
        installTecnicos(HEADER_CON,
                "3;1000000003;GOMEZ;SOTO;LUIS;luis@example.com;ventas.sur@example.com;3-000045");

        HashMap<String, String> data = Methods.validateCredentials("luis@example.com", "1000000003");

        assertEquals("1000000003", data.get("cedula"));
        assertEquals("3", data.get("numero"));
    }
}
