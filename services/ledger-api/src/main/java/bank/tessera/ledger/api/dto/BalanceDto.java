package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.domain.Balance;
import java.time.Instant;

/** Booked and available balance as at the moment of reading. */
public record BalanceDto(String accountRef, MoneyDto booked, MoneyDto available, String asOf) {

    public static BalanceDto from(Balance balance, Instant asOf) {
        return new BalanceDto(
                balance.account().value(),
                MoneyDto.from(balance.booked()),
                MoneyDto.from(balance.available()),
                Timestamps.format(asOf));
    }
}
