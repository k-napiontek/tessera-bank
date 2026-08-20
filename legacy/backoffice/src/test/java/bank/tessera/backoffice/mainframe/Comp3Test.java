package bank.tessera.backoffice.mainframe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import org.junit.Test;

/**
 * The decoder, held to bytes it did not produce.
 *
 * <p>The literals below are the worked examples in {@code docs/architecture/canonical-data-model.md}
 * section 2, transcribed by hand. The canonical model is the arbiter for what a packed amount means
 * across this estate, and a decoder checked against its own arithmetic proves nothing.
 */
public class Comp3Test {

    private static final byte[] PLN_1_234_567_89 =
            {0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78, (byte) 0x9C};
    private static final byte[] PLN_MINUS_1_234_567_89 =
            {0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78, (byte) 0x9D};
    private static final byte[] ZERO = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C};
    private static final byte[] MAXIMUM = {(byte) 0x99, (byte) 0x99, (byte) 0x99, (byte) 0x99,
            (byte) 0x99, (byte) 0x99, (byte) 0x99, (byte) 0x9C};

    @Test
    public void aPositiveAmount() {
        assertEquals(123456789L, Comp3.decode(PLN_1_234_567_89, 0));
    }

    @Test
    public void aNegativeAmountDiffersOnlyInTheSignNibble() {
        assertEquals(-123456789L, Comp3.decode(PLN_MINUS_1_234_567_89, 0));
        for (int i = 0; i < Comp3.SIZE - 1; i++) {
            assertEquals(PLN_1_234_567_89[i], PLN_MINUS_1_234_567_89[i]);
        }
    }

    @Test
    public void zero() {
        assertEquals(0L, Comp3.decode(ZERO, 0));
    }

    @Test
    public void theMaximumRepresentableAmount() {
        assertEquals(999999999999999L, Comp3.decode(MAXIMUM, 0));
    }

    @Test
    public void anUnsignedFieldReadsAsPositive() {
        byte[] unsigned = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F};
        assertEquals(1L, Comp3.decode(unsigned, 0));
    }

    @Test
    public void itDecodesAtAnOffsetInsideALargerRecord() {
        byte[] record = new byte[200];
        System.arraycopy(PLN_1_234_567_89, 0, record, 42, Comp3.SIZE);
        assertEquals(123456789L, Comp3.decode(record, 42));
    }

    /** Money is never a floating-point number, on the screen least of all. */
    @Test
    public void theDisplayAmountIsAnExactScaleTwoDecimal() {
        assertEquals(new BigDecimal("1234567.89"), Comp3.toAmount(123456789L));
        assertEquals(new BigDecimal("-0.01"), Comp3.toAmount(-1L));
        assertEquals(new BigDecimal("0.00"), Comp3.toAmount(0L));
    }

    @Test
    public void anUnrecognisedSignNibbleIsRefused() {
        byte[] broken = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A};
        try {
            Comp3.decode(broken, 0);
            fail("a sign nibble of 0xA was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("sign nibble"));
        }
    }

    @Test
    public void aDigitNibbleAboveNineIsRefused() {
        byte[] broken = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xAB, 0x0C};
        try {
            Comp3.decode(broken, 0);
            fail("a digit nibble of 0xA was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("digit nibble"));
        }
    }

    @Test
    public void tooFewBytesIsRefused() {
        try {
            Comp3.decode(new byte[] {0x00, 0x0C}, 0);
            fail("two bytes were accepted as COMP-3");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("COMP-3"));
        }
    }
}
