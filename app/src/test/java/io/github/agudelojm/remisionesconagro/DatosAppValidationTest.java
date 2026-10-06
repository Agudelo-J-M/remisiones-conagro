package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ActualizarDatos#datosAppTablesAreUsable} — the silent structure check applied to data read
 * back off the disk.
 *
 * <p>It exists because a stored table that is present but unusable (an empty or truncated CSV) was
 * being adopted as if it were fine, after which every login failed with "error de formato" and the
 * técnico had no way out: no fatal dialog, no prompt to open the file from the correo. Anything this
 * check rejects, {@link Methods#loadDatosApp} discards so the sync can rebuild it.
 */
public class DatosAppValidationTest {

    private static final String TECNICOS_HEADER =
            "NUMERO;CEDULA;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO";
    private static final String TECNICOS_ROW =
            "7;1000000003;GOMEZ;SOTO;LUIS;luis@example.com";

    private static final String PRODUCTOS_HEADER = "referencia;nombre;seccion;iva;activo;empresa;"
            + "precio_base;descuento_inservice;fecha_inicio_vigencia";
    private static final String PRODUCTOS_ROW = "A1;TUBO;Repuestos;19;t;Conagro;1000;0;1/2/2026";

    private static final String CLIENTES_HEADER = "finca_id;empresa;direccion;zona;ciu;numero_equipo;"
            + "clase_equipo;persona_doc;persona_nombre;persona_telefono;persona_email";
    private static final String CLIENTES_ROW =
            "1822;Conagro;CALLE 1;Norte;Medellin;5;Tanque;123;ANA;3000000;ana@example.com";

    private static final String TARIFAS_HEADER = "empresa;referencia;descripcion;precio_base;activo";
    private static final String TARIFAS_ROW = "Conagro;M1;MANTENIMIENTO;50000;t";

    /** A complete, valid set, as either input route would produce it. */
    private static Map<String, Tabla> validTables() {
        Map<String, Tabla> tablas = new LinkedHashMap<>();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos", TECNICOS_HEADER + "\n" + TECNICOS_ROW));
        tablas.put("Productos", Tabla.fromCsv("Productos", PRODUCTOS_HEADER + "\n" + PRODUCTOS_ROW));
        tablas.put("Clientes", Tabla.fromCsv("Clientes", CLIENTES_HEADER + "\n" + CLIENTES_ROW));
        tablas.put("Tarifas Mantenimiento",
                Tabla.fromCsv("Tarifas Mantenimiento", TARIFAS_HEADER + "\n" + TARIFAS_ROW));
        return tablas;
    }

    @Test
    public void completeTablesAreUsable() {
        assertTrue(ActualizarDatos.datosAppTablesAreUsable(validTables()));
    }

    @Test
    public void nullIsNotUsable() {
        assertFalse(ActualizarDatos.datosAppTablesAreUsable(null));
    }

    @Test
    public void aMissingTableIsNotUsable() {
        Map<String, Tabla> tablas = validTables();
        tablas.remove("Productos");

        assertFalse(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    /** A 0-byte stored CSV: this is the exact shape that used to be adopted silently. */
    @Test
    public void anEmptyTableIsNotUsable() {
        Map<String, Tabla> tablas = validTables();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos", ""));

        assertFalse(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    /**
     * Header but no rows. These four tables always have rows on the server, so an empty Tecnicos is
     * corruption — and adopting it would lock every técnico out of login.
     */
    @Test
    public void aHeaderWithNoDataRowsIsNotUsable() {
        Map<String, Tabla> tablas = validTables();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos", TECNICOS_HEADER));

        assertFalse(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    @Test
    public void aMissingRequiredColumnIsNotUsable() {
        Map<String, Tabla> tablas = validTables();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos",
                "NUMERO;1 APELLIDO;2 APELLIDO;NOMBRES;CORREO\n7;GOMEZ;SOTO;LUIS;luis@example.com"));

        assertFalse(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    /** Each of the four is checked, not just the first. */
    @Test
    public void aMissingColumnInTheLastTableIsAlsoCaught() {
        Map<String, Tabla> tablas = validTables();
        tablas.put("Tarifas Mantenimiento", Tabla.fromCsv("Tarifas Mantenimiento",
                "empresa;referencia;descripcion;activo\nConagro;M1;MANTENIMIENTO;t"));

        assertFalse(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    /**
     * Superset columns must stay acceptable — the server already sends several, and R6's
     * "CORREO OFICINA" is deliberately not required. Rejecting extras would break every phone the
     * moment the client adds a column.
     */
    @Test
    public void supersetColumnsAreStillUsable() {
        Map<String, Tabla> tablas = validTables();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos",
                TECNICOS_HEADER + ";CORREO OFICINA\n" + TECNICOS_ROW + ";ventas@example.com"));
        tablas.put("Productos", Tabla.fromCsv("Productos",
                PRODUCTOS_HEADER + ";inservice_section;novasoft_ref\n" + PRODUCTOS_ROW + ";X;Y"));

        assertTrue(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    /** Header case and spacing are tolerated here too, as they are everywhere else. */
    @Test
    public void headerCaseAndSpacingAreTolerated() {
        Map<String, Tabla> tablas = validTables();
        tablas.put("Tecnicos", Tabla.fromCsv("Tecnicos",
                "  numero ;cedula;1 apellido;2 APELLIDO;Nombres;correo\n" + TECNICOS_ROW));

        assertTrue(ActualizarDatos.datosAppTablesAreUsable(tablas));
    }

    /**
     * A store written by the app must pass the check it will later be read back through —
     * otherwise a healthy database would be discarded as corrupt on the next launch.
     */
    @Test
    public void whatTheStoreWritesPassesWhenItIsReadBack() {
        Map<String, Tabla> original = validTables();

        Map<String, Tabla> vuelta = new LinkedHashMap<>();
        for (Map.Entry<String, Tabla> e : original.entrySet()) {
            vuelta.put(e.getKey(), Tabla.fromCsv(e.getKey(), e.getValue().toCsv()));
        }

        assertTrue(ActualizarDatos.datosAppTablesAreUsable(vuelta));
    }
}
