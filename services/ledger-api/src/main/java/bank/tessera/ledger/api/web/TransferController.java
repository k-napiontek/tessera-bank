package bank.tessera.ledger.api.web;

import bank.tessera.ledger.api.dto.ReversalRequestDto;
import bank.tessera.ledger.api.dto.TransferDto;
import bank.tessera.ledger.api.dto.TransferRequestDto;
import bank.tessera.ledger.application.ReverseTransfer;
import bank.tessera.ledger.application.Transfer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money movement.
 *
 * <p>Both operations here take a required {@code Idempotency-Key}. The header is declared on the
 * method rather than only enforced in a filter so that an absent or badly sized one is a
 * {@code 400} raised by Spring's own binding, with a Problem document, before any use case runs.
 * {@code IdempotencyFilter} does the replaying; this declaration is what makes the requirement
 * visible in the signature and in the contract test.
 */
@RestController
@RequestMapping("/v1")
@Validated
public class TransferController {

    private final Transfer transfer;
    private final ReverseTransfer reverseTransfer;

    public TransferController(Transfer transfer, ReverseTransfer reverseTransfer) {
        this.transfer = transfer;
        this.reverseTransfer = reverseTransfer;
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferDto create(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody TransferRequestDto request) {
        return TransferDto.from(transfer.execute(request.toCommand()));
    }

    @PostMapping("/transfers/{transferRef}/reversals")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferDto reverse(
            @PathVariable String transferRef,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody ReversalRequestDto request) {
        return TransferDto.from(reverseTransfer.execute(request.toCommand(transferRef)));
    }
}
