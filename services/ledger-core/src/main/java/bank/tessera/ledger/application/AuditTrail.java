package bank.tessera.ledger.application;

import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditContext;
import bank.tessera.ledger.port.AuditEntry;
import bank.tessera.ledger.port.AuditLog;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * What every use case records, and the one place that decides how.
 *
 * <p>Each money-moving use case takes one of these rather than three collaborators, so adding the
 * audit trail cost each of them a single constructor argument. It is required, never optional: a use
 * case that can be constructed without an audit trail is one that can run without leaving a record,
 * and the whole point of REQ-AUD-001 is that none of them can.
 *
 * <p><strong>The append joins the caller's transaction.</strong> Nothing here opens one. A use case
 * calls this from inside the transaction it is already in, so a rolled-back transfer leaves no audit
 * row and a committed one cannot lack it.
 *
 * <p><strong>What must never be recorded.</strong> The state maps carry references, statuses,
 * amounts in minor units, currency codes and dates. Not the remittance {@code reference}: the
 * canonical model classifies that free text as restricted if misused, it is the one field a paying
 * customer can put anything into, and an audit row is retained for years. What a transfer moved and
 * between which accounts is what an auditor asks for; what the payer typed in the message box is not.
 *
 * <p>A reversal's {@code reason} is recorded, and the difference is not arbitrary: the contract calls
 * it operator-supplied and forbids personal data in it, and "why was this reversed" is the first
 * question anyone asks of a reversal.
 */
public final class AuditTrail {

    private final AuditLog log;
    private final AuditContext context;
    private final Clock clock;

    public AuditTrail(AuditLog log, AuditContext context, Clock clock) {
        this.log = Objects.requireNonNull(log, "log");
        this.context = Objects.requireNonNull(context, "context");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param subject the account, transfer or hold reference the action was about
     * @param before the subject's state beforehand, empty when the action created it
     * @param after the subject's state afterwards
     */
    public void record(
            AuditAction action, String subject, Map<String, String> before, Map<String, String> after) {
        log.append(AuditEntry.of(
                clock.instant(),
                context.actor(),
                action,
                subject,
                context.correlationId().orElse(null),
                before,
                after));
    }
}
