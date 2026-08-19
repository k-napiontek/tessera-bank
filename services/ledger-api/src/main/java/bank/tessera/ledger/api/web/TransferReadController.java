package bank.tessera.ledger.api.web;

import bank.tessera.ledger.api.dto.TransferDto;
import bank.tessera.ledger.application.GetTransfer;
import bank.tessera.ledger.application.TransferNotFoundException;
import bank.tessera.ledger.domain.EntryRef;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reading a transfer. The path variable the contract calls {@code transferRef} is an {@code EntryRef}. */
@RestController
@RequestMapping("/v1")
public class TransferReadController {

    private final GetTransfer getTransfer;

    public TransferReadController(GetTransfer getTransfer) {
        this.getTransfer = getTransfer;
    }

    @GetMapping("/transfers/{transferRef}")
    public TransferDto transfer(@PathVariable String transferRef) {
        EntryRef reference = EntryRef.of(transferRef);
        return getTransfer.byReference(reference)
                .map(TransferDto::from)
                .orElseThrow(() -> new TransferNotFoundException(reference));
    }
}
