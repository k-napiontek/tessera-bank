package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.PlaceHold;
import bank.tessera.ledger.domain.AccountRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** A reservation against available balance. Moves no money. */
public record HoldRequestDto(
        @NotNull @Valid MoneyDto amount, Instant expiresAt, @Size(max = 35) String reference) {

    public PlaceHold.Command toCommand(String accountRef) {
        return new PlaceHold.Command(
                AccountRef.of(accountRef), amount.toDomain(), expiresAt, reference);
    }
}
