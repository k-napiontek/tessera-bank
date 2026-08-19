package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Money on the wire: a signed count of minor units and the currency that gives them scale.
 *
 * <p>{@code amountMinor} is a {@code long}, never a decimal and never a floating-point number. The
 * decimal position is not transmitted at all - it is resolved from the ISO 4217 table at presentation
 * time, and {@code Money.toPlainString()} is the only place in the estate that applies it.
 */
public record MoneyDto(
        @NotNull Long amountMinor,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 alpha-3 code")
                String currency) {

    public static MoneyDto from(Money money) {
        return money == null ? null : new MoneyDto(money.amountMinor(), money.currency().code());
    }

    /** @throws IllegalArgumentException if the currency is not one the estate carries */
    public Money toDomain() {
        return Money.of(amountMinor, CurrencyCode.of(currency));
    }
}
