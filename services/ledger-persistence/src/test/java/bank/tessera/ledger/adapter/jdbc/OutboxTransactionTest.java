package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.application.AuditTrail;
import bank.tessera.ledger.application.OpenAccount;
import bank.tessera.ledger.application.Transfer;
import bank.tessera.ledger.application.TransferEvents;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftNotPermittedException;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.AuditContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The claim the outbox pattern exists to make: the event and the postings share one fate.
 *
 * <p>Both halves are asserted, because either alone is satisfied by a broken implementation. An
 * outbox that never wrote anything would pass "a rejected transfer leaves no row"; one that wrote
 * outside the transaction would pass "a posted transfer leaves a row". Only the pair says the row
 * commits with the postings and rolls back with them.
 */
class OutboxTransactionTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef VAULT = AccountRef.of("TB00000000000801");
    private static final AccountRef ALICE = AccountRef.of("TB00000000000802");
    private static final AccountRef BOB = AccountRef.of("TB00000000000803");

    private static NamedParameterJdbcTemplate jdbc;
    private static Transfer transfer;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("outbox_transaction");
        jdbc = new NamedParameterJdbcTemplate(dataSource);

        JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);
        JdbcLedgerReadModel readModel = new JdbcLedgerReadModel(jdbc);
        JdbcJournalEntryRepository entries =
                new JdbcJournalEntryRepository(jdbc, new JdbcHoldRepository(jdbc), Transactions.of(dataSource));
        JdbcUnitOfWork unitOfWork =
                new JdbcUnitOfWork(Transactions.of(dataSource), new AccountLocks(accounts));
        AuditContext context = new AuditContext() {
            @Override
            public String actor() {
                return "test";
            }

            @Override
            public Optional<String> correlationId() {
                return Optional.empty();
            }
        };
        AuditTrail audit = new AuditTrail(
                new JdbcAuditLog(jdbc, new ObjectMapper()), context, Clock.systemUTC());
        TransferEvents events =
                new TransferEvents(new JdbcEventOutbox(jdbc, LedgerEventJson.mapper()), context);

        OpenAccount openAccount =
                new OpenAccount(accounts, readModel, unitOfWork, audit, Clock.systemUTC());
        transfer = new Transfer(
                accounts,
                entries,
                readModel,
                new JdbcReferenceGenerator(jdbc, Clock.systemUTC()),
                unitOfWork,
                audit,
                events,
                Clock.systemUTC());

        open(openAccount, VAULT, AccountType.ASSET);
        open(openAccount, ALICE, AccountType.LIABILITY);
        open(openAccount, BOB, AccountType.LIABILITY);
        transfer.execute(new Transfer.Command(VAULT, ALICE, Money.of(100_00, PLN), null, null));
    }

    private static void open(OpenAccount openAccount, AccountRef reference, AccountType type) {
        openAccount.open(new OpenAccount.Command(
                reference, CustomerRef.of("CU0000000001"), type, PLN, null, OverdraftPolicy.forbidden()));
    }

    private static long outboxRows() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_record", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    private static long postingRows() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM posting", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("a posted transfer leaves an undispatched row keyed by the transfer reference")
    void aPostedTransferIsEnqueued() {
        var posted = transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(30_00, PLN), null, null));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT topic, message_key, dispatched_at, attempts FROM outbox_record"
                        + " WHERE message_key = :key",
                Map.of("key", posted.transferReference().value()));

        assertThat(row.get("topic")).isEqualTo("tessera.ledger.transfer-posted.v1");
        assertThat(row.get("message_key")).isEqualTo(posted.transferReference().value());
        assertThat(row.get("dispatched_at")).as("nothing has published it yet").isNull();
        assertThat(row.get("attempts")).isEqualTo(0);
    }

    @Test
    @DisplayName("a rolled-back transfer leaves neither a posting nor an outbox row")
    void rollbackLeavesNothing() {
        long outboxBefore = outboxRows();
        long postingsBefore = postingRows();

        // Refused by the overdraft policy inside the transaction, after both accounts are locked and
        // the balance has been read - so the transaction has already done work when it aborts.
        assertThatThrownBy(() -> transfer.execute(
                        new Transfer.Command(BOB, ALICE, Money.of(1_000_00, PLN), null, null)))
                .isInstanceOf(OverdraftNotPermittedException.class);

        assertThat(outboxRows()).isEqualTo(outboxBefore);
        assertThat(postingRows()).isEqualTo(postingsBefore);
    }

    @Test
    @DisplayName("the payload is the whole event, movements included")
    void thePayloadCarriesTheEvent() throws Exception {
        var posted =
                transfer.execute(new Transfer.Command(ALICE, BOB, Money.of(11_00, PLN), null, "INV-4471"));

        // Parsed, not string-matched. A jsonb column stores a parsed document rather than the bytes
        // it was given: keys come back in another order and whitespace is normalised, so asserting on
        // the text would be asserting on PostgreSQL's formatter.
        JsonNode payload = new ObjectMapper()
                .readTree(jdbc.queryForObject(
                        "SELECT payload::text FROM outbox_record WHERE message_key = :key",
                        Map.of("key", posted.transferReference().value()),
                        String.class));

        assertThat(payload.get("transferRef").asText()).isEqualTo(posted.transferReference().value());
        assertThat(payload.get("debitAccountRef").asText()).isEqualTo(ALICE.value());
        assertThat(payload.get("creditAccountRef").asText()).isEqualTo(BOB.value());
        assertThat(payload.get("amount").get("amountMinor").asLong()).isEqualTo(1100L);
        assertThat(payload.get("amount").get("currency").asText()).isEqualTo("PLN");
        // A string, never an epoch number: the contract declares format date-time, and a consumer
        // written in Go or Python reads a string.
        assertThat(payload.get("postedAt").isTextual()).isTrue();

        // The remittance reference is carried here and withheld from the audit trail. The ESB
        // encodes it into a MOVEREC and the mainframe prints it; an audit row has no such consumer
        // and outlives every other copy of it.
        assertThat(payload.get("reference").asText()).isEqualTo("INV-4471");

        // Absent, not null. The schema sets additionalProperties: false and simply omits the field
        // on a transfer that reverses nothing.
        assertThat(payload.has("reversesTransferRef")).isFalse();

        assertThat(payload.get("movements")).hasSize(2);
        assertThat(payload.get("movements").get(0).get("legNo").asInt()).isEqualTo(1);
        assertThat(payload.get("movements").get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(payload.get("movements").get(0).get("movementRef").asText())
                .isEqualTo(posted.transferReference().value() + "-01");
        assertThat(payload.get("movements").get(1).get("direction").asText()).isEqualTo("CREDIT");
    }
}
