package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * R6 — the two pure helpers behind the office-email auto-fill
 * ({@link Methods#buildEmailRecipients} and {@link Methods#getCorreoOficina}).
 *
 * Both are plain static Java with no Android dependency, so they run on the host JVM as-is.
 */
public class OfficeEmailRecipientsTest {

    // ----- buildEmailRecipients ---------------------------------------------------------

    @Test
    public void clienteAndOficinaBothBecomeRecipients() {
        assertArrayEquals(new String[]{"cli@example.com", "ventas@example.com"},
                Methods.buildEmailRecipients("cli@example.com", "ventas@example.com"));
    }

    @Test
    public void blankOficinaLeavesOnlyTheCliente() {
        assertArrayEquals(new String[]{"cli@example.com"},
                Methods.buildEmailRecipients("cli@example.com", ""));
    }

    @Test
    public void blankClienteLeavesOnlyTheOficina() {
        assertArrayEquals(new String[]{"ventas@example.com"},
                Methods.buildEmailRecipients("", "ventas@example.com"));
    }

    /**
     * The load-bearing case: both blank must yield an EMPTY array, never {""}. An empty string
     * recipient is what the app used to send, and some mail clients render it as an invalid
     * recipient chip the técnico then has to delete by hand.
     */
    @Test
    public void bothBlankYieldsAnEmptyArrayNotAnEmptyString() {
        assertEquals(0, Methods.buildEmailRecipients("", "").length);
    }

    @Test
    public void whitespaceOnlyCountsAsBlank() {
        assertEquals(0, Methods.buildEmailRecipients("   ", "\t").length);
    }

    @Test
    public void addressesAreTrimmed() {
        assertArrayEquals(new String[]{"cli@example.com", "ventas@example.com"},
                Methods.buildEmailRecipients("  cli@example.com ", " ventas@example.com  "));
    }

    @Test
    public void nullElementsAreDropped() {
        assertArrayEquals(new String[]{"ventas@example.com"},
                Methods.buildEmailRecipients((String) null, "ventas@example.com"));
    }

    @Test
    public void nullArrayYieldsAnEmptyArray() {
        assertEquals(0, Methods.buildEmailRecipients((String[]) null).length);
    }

    @Test
    public void noArgumentsYieldsAnEmptyArray() {
        assertEquals(0, Methods.buildEmailRecipients().length);
    }

    // ----- one cell, several addresses --------------------------------------------------
    // "CORREO OFICINA" is a human-maintained spreadsheet column, so a second address is most
    // likely to arrive as a comma inside the one cell. Passed whole it would become a single
    // invalid recipient that strict clients refuse, while the técnico still confirms the send.

    @Test
    public void commaSeparatedCellBecomesTwoRecipients() {
        assertArrayEquals(new String[]{"oficina@example.com", "contabilidad@example.com"},
                Methods.buildEmailRecipients("oficina@example.com, contabilidad@example.com"));
    }

    @Test
    public void semicolonSeparatedCellBecomesTwoRecipients() {
        assertArrayEquals(new String[]{"oficina@example.com", "contabilidad@example.com"},
                Methods.buildEmailRecipients("oficina@example.com;contabilidad@example.com"));
    }

    @Test
    public void clienteAndAMultiAddressOficinaAllBecomeRecipients() {
        assertArrayEquals(
                new String[]{"cli@example.com", "oficina@example.com", "contabilidad@example.com"},
                Methods.buildEmailRecipients("cli@example.com",
                        "oficina@example.com, contabilidad@example.com"));
    }

    @Test
    public void emptySegmentsInsideACellAreDropped() {
        assertArrayEquals(new String[]{"oficina@example.com"},
                Methods.buildEmailRecipients("oficina@example.com,,  ;"));
    }

    @Test
    public void aSingleAddressIsUnaffectedBySplitting() {
        assertArrayEquals(new String[]{"oficina@example.com"},
                Methods.buildEmailRecipients("oficina@example.com"));
    }

    /** A separator-only cell must not produce recipients. */
    @Test
    public void separatorOnlyCellYieldsNothing() {
        assertEquals(0, Methods.buildEmailRecipients(",;").length);
    }

    // ----- getCorreoOficina -------------------------------------------------------------

    @Test
    public void officeAddressIsReadFromUserData() {
        Map<String, String> userData = new HashMap<>();
        userData.put("correoOficina", "ventas@example.com");
        assertEquals("ventas@example.com", Methods.getCorreoOficina(userData));
    }

    @Test
    public void officeAddressIsTrimmed() {
        Map<String, String> userData = new HashMap<>();
        userData.put("correoOficina", "  ventas@example.com  ");
        assertEquals("ventas@example.com", Methods.getCorreoOficina(userData));
    }

    /** A técnico whose cell is blank on the server: must fall back, not crash. */
    @Test
    public void blankValueYieldsEmptyString() {
        Map<String, String> userData = new HashMap<>();
        userData.put("correoOficina", "");
        assertEquals("", Methods.getCorreoOficina(userData));
    }

    @Test
    public void whitespaceValueYieldsEmptyString() {
        Map<String, String> userData = new HashMap<>();
        userData.put("correoOficina", "   ");
        assertEquals("", Methods.getCorreoOficina(userData));
    }

    /** A database saved before the column existed never puts the key in the map at all. */
    @Test
    public void missingKeyYieldsEmptyString() {
        Map<String, String> userData = new HashMap<>();
        userData.put("numero", "7");
        assertEquals("", Methods.getCorreoOficina(userData));
    }

    /** A HashMap permits a null value; it must read as "" rather than NPE. */
    @Test
    public void nullValueYieldsEmptyString() {
        Map<String, String> userData = new HashMap<>();
        userData.put("correoOficina", null);
        assertEquals("", Methods.getCorreoOficina(userData));
    }

    /** An activity that never received its userData extra still has to reach the fallback. */
    @Test
    public void nullUserDataYieldsEmptyString() {
        assertEquals("", Methods.getCorreoOficina(null));
    }
}
