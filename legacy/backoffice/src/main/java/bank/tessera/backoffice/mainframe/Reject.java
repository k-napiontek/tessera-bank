package bank.tessera.backoffice.mainframe;

import java.math.BigDecimal;

/**
 * One {@code REJREC}: a movement the overnight cycle refused, and why.
 *
 * <p>The first 120 bytes of a reject are a whole {@code MOVEREC}, which is the useful part - an
 * operator needs to know which account and which amount, not only that something failed. Both the
 * reason code and the reason text are carried: a code an operator has to look up is a code they
 * will guess at, and the text alone cannot be filtered on.
 */
public final class Reject {

    private final String transferRef;
    private final int legNo;
    private final String accountRef;
    private final String direction;
    private final String currency;
    private final long amountMinor;
    private final String valueDate;
    private final String reasonCode;
    private final String reasonText;
    private final String detectedAt;

    Reject(String transferRef, int legNo, String accountRef, String direction, String currency,
            long amountMinor, String valueDate, String reasonCode, String reasonText,
            String detectedAt) {
        this.transferRef = transferRef;
        this.legNo = legNo;
        this.accountRef = accountRef;
        this.direction = direction;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.valueDate = valueDate;
        this.reasonCode = reasonCode;
        this.reasonText = reasonText;
        this.detectedAt = detectedAt;
    }

    public String getTransferRef() {
        return transferRef;
    }

    public int getLegNo() {
        return legNo;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public String getDirection() {
        return direction;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    /** The amount as a scale-2 decimal, for the screen. Never a double. */
    public BigDecimal getAmount() {
        return Comp3.toAmount(amountMinor);
    }

    public String getValueDate() {
        return valueDate;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonText() {
        return reasonText;
    }

    public String getDetectedAt() {
        return detectedAt;
    }

    /** The key an annotation is filed under: a leg, not a transfer. */
    public String getKey() {
        return transferRef + "/" + legNo;
    }
}
