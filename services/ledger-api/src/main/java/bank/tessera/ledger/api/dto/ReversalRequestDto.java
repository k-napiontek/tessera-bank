package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.ReverseTransfer;
import bank.tessera.ledger.domain.EntryRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a transfer is being reversed. Operator-supplied, and never personal data. */
public record ReversalRequestDto(
        @NotBlank @Size(max = 140) String reason, @Size(max = 35) String reference) {

    public ReverseTransfer.Command toCommand(String transferRef) {
        return new ReverseTransfer.Command(EntryRef.of(transferRef), reason, reference);
    }
}
