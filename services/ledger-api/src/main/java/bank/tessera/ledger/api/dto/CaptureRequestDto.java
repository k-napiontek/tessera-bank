package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.CaptureHold;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.HoldRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Turning a hold into a transfer. The amount must not exceed what was reserved. */
public record CaptureRequestDto(
        @NotBlank @Pattern(regexp = "^TB[0-9A-Z]{14}$") String creditAccountRef,
        @NotNull @Valid MoneyDto amount,
        @Size(max = 35) String reference) {

    public CaptureHold.Command toCommand(String holdRef) {
        return new CaptureHold.Command(
                HoldRef.of(holdRef), AccountRef.of(creditAccountRef), amount.toDomain(), reference);
    }
}
