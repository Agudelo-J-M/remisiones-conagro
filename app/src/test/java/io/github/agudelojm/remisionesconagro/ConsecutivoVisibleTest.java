package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * R8 §6.0 — the split between the visible consecutivo ("3-000045", what the office reads) and
 * the internal id ("03000045", which is the storage key).
 *
 * The load-bearing cases are the invariants at the bottom: if anyone ever "simplifies" the two
 * formats into one, {@code reconcileConsecutivo} stops recognising its own filenames and the
 * 14-day self-heal dies silently on every phone that already has saved remisiones.
 */
public class ConsecutivoVisibleTest {


    // ---- formatConsecutivoVisible -----------------------------------------

    @Test
    public void visible_usesHyphenAndSixDigits() {
        assertEquals("3-000045", Methods.formatConsecutivoVisible("3", 45));
    }

    @Test
    public void visible_doesNotPadThePrefix() {
        // The server seeded "3-000001", NOT "03-000001". This is the whole point of the format.
        assertEquals("3-000001", Methods.formatConsecutivoVisible("3", 1));
    }

    @Test
    public void visible_twoDigitNumeroIsLeftAlone() {
        assertEquals("13-000045", Methods.formatConsecutivoVisible("13", 45));
    }

    @Test
    public void visible_trimsTheNumero() {
        assertEquals("3-000045", Methods.formatConsecutivoVisible("  3  ", 45));
    }

    @Test
    public void visible_sequenceBounds() {
        assertEquals("3-000000", Methods.formatConsecutivoVisible("3", 0));
        assertEquals("3-999999", Methods.formatConsecutivoVisible("3", 999999));
    }

    // ---- seqDe ------------------------------------------------------------

    @Test
    public void seqDe_readsBothShapes() {
        assertEquals(45, Methods.seqDe("03000045"));
        assertEquals(45, Methods.seqDe("3-000045"));
    }

    @Test
    public void seqDe_isTheLastSixDigitsRule() {
        assertEquals(45, Methods.seqDe("000045"));
        assertEquals(1, Methods.seqDe("13-000001"));
    }

    @Test
    public void seqDe_toleratesSurroundingWhitespace() {
        // The server column is a human-maintained spreadsheet cell.
        assertEquals(45, Methods.seqDe("  3-000045  "));
    }

    @Test
    public void seqDe_rejectsWhatItCannotParse() {
        assertEquals(-1, Methods.seqDe(null));
        assertEquals(-1, Methods.seqDe(""));
        assertEquals(-1, Methods.seqDe("12345"));      // too short
        assertEquals(-1, Methods.seqDe("3-00004a"));   // not digits
        assertEquals(-1, Methods.seqDe("3-"));
    }

    // ---- visibleDeConsecutivo --------------------------------------------

    @Test
    public void visibleDe_convertsAnInternalId() {
        assertEquals("3-000045", Methods.visibleDeConsecutivo("03000045"));
        assertEquals("13-000045", Methods.visibleDeConsecutivo("13000045"));
    }

    @Test
    public void visibleDe_isIdempotentOnAnAlreadyVisibleString() {
        assertEquals("3-000045", Methods.visibleDeConsecutivo("3-000045"));
    }

    @Test
    public void visibleDe_fallsBackToTheInputWhenItCannot() {
        // Every display path must survive bad input by printing the old shape, never "" or null.
        assertEquals("basura", Methods.visibleDeConsecutivo("basura"));
        assertNull(Methods.visibleDeConsecutivo(null));
        assertEquals("000045", Methods.visibleDeConsecutivo("000045"));   // no prefix to show
        assertEquals("A000045", Methods.visibleDeConsecutivo("A000045")); // non-numeric prefix
    }

    // ---- the invariants that protect reconcileConsecutivo ----------------

    @Test
    public void internalIdIsUnchanged_andStillTwoPlusSixDigits() {
        // reconcileConsecutivo requires id.length() == prefix.length() + 6 on <2-digit><6-digit>.
        assertEquals("03000045", Methods.formatConsecutivo("3", 45));
        assertEquals(8, Methods.formatConsecutivo("3", 45).length());
        assertEquals(8, Methods.formatConsecutivo("13", 45).length());
    }

    @Test
    public void theTwoFormatsAreDeliberatelyDifferent() {
        assertNotEquals(Methods.formatConsecutivo("3", 45), Methods.formatConsecutivoVisible("3", 45));
    }

    @Test
    public void seqDeRoundTripsBothFormatters() {
        for (int seq : new int[]{1, 45, 999, 123456}) {
            assertEquals(seq, Methods.seqDe(Methods.formatConsecutivo("3", seq)));
            assertEquals(seq, Methods.seqDe(Methods.formatConsecutivoVisible("3", seq)));
        }
    }

    // ---- getSeqServidor (R8 §4 read side) --------------------------------

    private static Map<String, String> sesion(String numero, String consecutivoServidor) {
        Map<String, String> m = new HashMap<>();
        m.put("numero", numero);
        if (consecutivoServidor != null) m.put("consecutivoServidor", consecutivoServidor);
        return m;
    }

    @Test
    public void servidor_readsBothShapes() {
        assertEquals(45, Methods.getSeqServidor(sesion("3", "3-000045")));
        assertEquals(45, Methods.getSeqServidor(sesion("3", "03000045")));
    }

    @Test
    public void servidor_blankIsNeverClaimed_notAnError() {
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "")));
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "   ")));
    }

    @Test
    public void servidor_absentColumnDegrades() {
        // validateCredentials omits the key when the column is missing: local-only numbering.
        assertEquals(-1, Methods.getSeqServidor(sesion("3", null)));
        assertEquals(-1, Methods.getSeqServidor(null));
    }

    @Test
    public void servidor_rejectsAnotherTecnicosSeries() {
        // The anti-inheritance guard: técnico 3 must never adopt técnico 7's counter, which would
        // hand both of them the same numbers from then on.
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "7-000045")));
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "07000045")));
    }

    @Test
    public void servidor_acceptsEitherPrefixPaddingForTheOwner() {
        // "1-000001" and "01000001" are the same thing for técnico 1.
        assertEquals(1, Methods.getSeqServidor(sesion("1", "1-000001")));
        assertEquals(1, Methods.getSeqServidor(sesion("1", "01000001")));
        assertEquals(1, Methods.getSeqServidor(sesion("13", "13-000001")));
    }

    @Test
    public void servidor_rejectsMalformedValues() {
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "basura")));
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "3-00004a")));
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "000045")));  // no prefix to verify
        assertEquals(-1, Methods.getSeqServidor(sesion("3", "-000045")));
    }

    @Test
    public void servidor_zeroIsAUsableValue() {
        // "never sent" seeded as 0 is a real answer, not a missing one: it must not read as -1,
        // because isConsecutivoInicializado treats >= 0 as "the server has a value".
        assertEquals(0, Methods.getSeqServidor(sesion("3", "3-000000")));
    }

    @Test
    public void servidor_toleratesWhitespaceInTheCell() {
        assertEquals(45, Methods.getSeqServidor(sesion("3", "  3-000045 ")));
        assertEquals(45, Methods.getSeqServidor(sesion(" 3 ", "3-000045")));
    }
}
