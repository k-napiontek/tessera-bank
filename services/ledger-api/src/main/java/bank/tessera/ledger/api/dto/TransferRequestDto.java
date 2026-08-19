package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.Transfer;
import bank.tessera.ledger.domain.AccountRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** An internal transfer: one debit and one credit, of the same amount, in the same currency. */
public record TransferRequestDto(
        @NotBlank @Pattern(regexp = "^TB[0-9A-Z]{14}$") String debitAccountRef,
        @NotBlank @Pattern(regexp = "^TB[0-9A-Z]{14}$") String creditAccountRef,
        @NotNull @Valid MoneyDto amount,
        @Size(max = 35) String reference,
        LocalDate valueDate) {

    public Transfer.Command toCommand() {
        // The two accounts differing, and the amount being positive, are checked by the use case
        // rather than here. A schema cannot express the first, and both are business rules the
        // domain tests already reach - restating them in an annotation would give two places to
        // change and one of them would be forgotten.
        return new Transfer.Command(
                AccountRef.of(debitAccountRef),
                AccountRef.of(creditAccountRef),
                amount.toDomain(),
                valueDate,
                reference);
    }
}
