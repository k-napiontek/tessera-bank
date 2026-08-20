package bank.tessera.esb;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * ISO 4217 minor-unit scales, and the reason this component refuses some of them.
 *
 * <p>Stratum 0 stores an amount as {@code PIC S9(13)V99 COMP-3}, which hard-codes <em>two</em>
 * decimal places. A JPY amount has none and a BHD amount has three, so neither can be carried into
 * the mainframe without being wrong by a factor of a hundred or a thousand - and wrong in a way that
 * prints as an ordinary-looking number.
 *
 * <p>The estate's answer is defence in depth, stated in the decision log since WP-03: the
 * integration tier rejects such a movement before it arrives, and the mainframe validates it again,
 * because a 1995 core does not trust its feeds. This is the first half of that. WP-11b asserts the
 * same rule again at the encoder, where the bytes are actually produced.
 *
 * <p>Rejecting an <strong>unknown</strong> currency matters as much as rejecting a known bad one.
 * Defaulting to two would let a currency nobody has thought about through, and it would be right
 * most of the time - which is exactly what makes the failures hard to find.
 *
 * <p>The same table as {@code batch/reporting/money.py} and {@code edge/web-banking/money.ts}, in
 * the third language to need it. That it is copied rather than shared is a property of an estate
 * with seven build systems, and is why each copy carries the zero-scale and three-scale entries: a
 * table holding only two-scale currencies passes every test a hard-coded 2 would.
 */
public final class CurrencyScales {

    private static final Map<String, Integer> SCALES;

    static {
        Map<String, Integer> scales = new HashMap<>();
        scales.put("PLN", 2);
        scales.put("EUR", 2);
        scales.put("USD", 2);
        scales.put("GBP", 2);
        scales.put("CHF", 2);
        // Zero-scale, present so that a hard-coded 2 fails a test rather than passing quietly.
        scales.put("JPY", 0);
        scales.put("KRW", 0);
        // Three-scale, carried for the same reason from the other side.
        scales.put("BHD", 3);
        scales.put("KWD", 3);
        scales.put("TND", 3);
        SCALES = Collections.unmodifiableMap(scales);
    }

    /** What stratum 0's packed decimal can represent, and nothing else. */
    private static final int MAINFRAME_SCALE = 2;

    private CurrencyScales() {
    }

    /**
     * @throws TransferHandlingException permanently, at stage ENCODE, when the mainframe could not
     *     represent this amount. Permanent because the currency will not change on redelivery.
     */
    public static void requireRepresentableOnTheMainframe(String currency) {
        Integer scale = SCALES.get(currency);
        if (scale == null) {
            throw TransferHandlingException.permanent(FailureStage.ENCODE,
                    "currency " + currency + " has no scale in this estate's table, and assuming"
                            + " two decimal places is how an amount ends up wrong by a factor of"
                            + " a hundred");
        }
        if (scale != MAINFRAME_SCALE) {
            throw TransferHandlingException.permanent(FailureStage.ENCODE,
                    "currency " + currency + " has an ISO 4217 scale of " + scale
                            + "; the account master stores PIC S9(13)V99 COMP-3, which can only"
                            + " represent 2");
        }
    }

    public static Integer scaleOf(String currency) {
        return SCALES.get(currency);
    }
}
