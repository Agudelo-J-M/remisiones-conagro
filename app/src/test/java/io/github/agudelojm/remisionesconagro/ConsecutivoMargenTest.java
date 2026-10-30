package io.github.agudelojm.remisionesconagro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * R8 §8 — the reinstall margin. A device with no local history cannot know what it consumed
 * offline before being wiped, so it jumps to the next multiple of
 * {@link Methods#MARGEN_REINSTALACION} instead of asking for {@code floor + 1}.
 *
 * <p>The property that matters is at the bottom: the result is always <b>strictly greater</b> than
 * the floor. If it could ever equal the floor, a reinstalled phone would re-mint a number the
 * office already has — the one unrecoverable outcome in this whole feature.
 */
public class ConsecutivoMargenTest {

    @Test
    public void freshDeviceJumpsToTheFirstRoundHundred() {
        assertEquals(100, Methods.seqConMargen(0));
    }

    @Test
    public void jumpsToTheNextHundredNotTheNearest() {
        assertEquals(100, Methods.seqConMargen(1));
        assertEquals(100, Methods.seqConMargen(45));
        assertEquals(100, Methods.seqConMargen(99));
        assertEquals(200, Methods.seqConMargen(100));
        assertEquals(200, Methods.seqConMargen(101));
        assertEquals(500, Methods.seqConMargen(450));
    }

    @Test
    public void aFloorExactlyOnTheBoundaryStillMovesUp() {
        // The dangerous off-by-one: floor 100 must not resolve to 100, or the number the office
        // already holds gets handed out a second time.
        assertEquals(200, Methods.seqConMargen(100));
        assertEquals(1000, Methods.seqConMargen(900));
    }

    @Test
    public void refusesToClampOntoTheFloor() {
        // Falling back to floor+1 while it still fits, and -1 once the series is spent —
        // NEVER the floor itself, which would re-mint a number the office already holds.
        assertEquals(999951, Methods.seqConMargen(999950));
        assertEquals(999999, Methods.seqConMargen(999998));
        assertEquals(-1, Methods.seqConMargen(999999));
        assertEquals(-1, Methods.seqConMargen(1000000));
    }

    @Test
    public void negativeFloorIsTreatedAsNoHistory() {
        // getSeqServidor returns -1 for "nothing usable"; it must never leak into the arithmetic.
        assertEquals(100, Methods.seqConMargen(-1));
        assertEquals(100, Methods.seqConMargen(Integer.MIN_VALUE));
    }

    @Test
    public void theMarginIsAPolicyConstantOfAtLeastOne() {
        // The owner may lower it (see the locked decision). Anything < 1 would break the
        // "strictly greater" property below, so the constant itself is asserted.
        assertTrue(Methods.MARGEN_REINSTALACION >= 1);
    }

    @Test
    public void resultIsAlwaysStrictlyGreaterThanTheFloor() {
        for (int floor = 0; floor < 1000; floor++) {
            assertTrue("floor " + floor, Methods.seqConMargen(floor) > floor);
        }
        // The ceiling is excluded on purpose: there seqConMargen reports -1 (series spent)
        // rather than returning a number, which the test above pins down.
        for (int floor : new int[]{4999, 5000, 5001, 123456, 999998}) {
            assertTrue("floor " + floor, Methods.seqConMargen(floor) > floor);
        }
    }
}
