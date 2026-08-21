package bank.tessera.ledger.api.config;

import bank.tessera.ledger.adapter.jdbc.AccountLocks;
import bank.tessera.ledger.adapter.jdbc.JdbcAccountRepository;
import bank.tessera.ledger.adapter.jdbc.LedgerEventJson;
import bank.tessera.ledger.adapter.outbox.EventPublisher;
import bank.tessera.ledger.adapter.outbox.OutboxRelay;
import bank.tessera.ledger.adapter.jdbc.JdbcAuditLog;
import bank.tessera.ledger.adapter.jdbc.LockWaits;
import bank.tessera.ledger.adapter.jdbc.JdbcEventOutbox;
import bank.tessera.ledger.adapter.jdbc.JdbcHoldRepository;
import bank.tessera.ledger.adapter.jdbc.JdbcIdempotencyStore;
import bank.tessera.ledger.adapter.jdbc.JdbcJournalEntryRepository;
import bank.tessera.ledger.adapter.jdbc.JdbcLedgerReadModel;
import bank.tessera.ledger.adapter.jdbc.JdbcReferenceGenerator;
import bank.tessera.ledger.adapter.jdbc.JdbcUnitOfWork;
import bank.tessera.ledger.api.audit.HttpAuditContext;
import bank.tessera.ledger.api.correlation.CorrelationIdFilter;
import bank.tessera.ledger.api.metrics.DatabaseSignals;
import bank.tessera.ledger.api.metrics.MicrometerLockWaits;
import bank.tessera.ledger.api.metrics.MoneyMovementMetricsFilter;
import bank.tessera.ledger.api.outbox.KafkaEventPublisher;
import bank.tessera.ledger.api.outbox.OutboxRelayScheduler;
import bank.tessera.ledger.application.AuditTrail;
import bank.tessera.ledger.application.CaptureHold;
import bank.tessera.ledger.application.TransferEvents;
import bank.tessera.ledger.application.GetAccount;
import bank.tessera.ledger.application.GetBalance;
import bank.tessera.ledger.application.GetStatement;
import bank.tessera.ledger.application.GetTransfer;
import bank.tessera.ledger.application.ListHolds;
import bank.tessera.ledger.application.OpenAccount;
import bank.tessera.ledger.application.PlaceHold;
import bank.tessera.ledger.application.ReleaseHold;
import bank.tessera.ledger.application.ReverseTransfer;
import bank.tessera.ledger.api.idempotency.IdempotencyFilter;
import bank.tessera.ledger.api.idempotency.RequestFingerprint;
import bank.tessera.ledger.api.problem.ProblemWriter;
import bank.tessera.ledger.application.Transfer;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.AuditContext;
import bank.tessera.ledger.port.AuditLog;
import bank.tessera.ledger.port.EventOutbox;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.IdempotencyStore;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.ReferenceGenerator;
import bank.tessera.ledger.port.UnitOfWork;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the ledger by hand.
 *
 * <p>Not a single {@code @Component} or {@code @Repository} appears in {@code ledger-core} or
 * {@code ledger-persistence}, and that is the point rather than an oversight: neither module may
 * carry a framework annotation, so component scanning has nothing to find and the wiring is written
 * out here. The cost is this file. The benefit is that the domain and its adapters can be
 * constructed in a unit test with {@code new}, which is why {@code ledger-core}'s tests take
 * milliseconds and need no container.
 *
 * <p>The {@link TransactionTemplate} comes from Boot's transaction manager, exactly as
 * {@code Transactions} anticipated: <em>"WP-08 can pass a container-managed TransactionTemplate
 * instead. The adapters take one as a constructor argument for that reason rather than building
 * their own."</em>
 */
@EnableScheduling
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

    @Bean
    Clock clock() {
        // Injected everywhere rather than called statically, so a test can fix time and a business
        // date is never whatever the machine happened to think when the code ran.
        return Clock.systemUTC();
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    AccountRepository accountRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcAccountRepository(jdbc);
    }

    @Bean
    HoldRepository holdRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcHoldRepository(jdbc);
    }

    @Bean
    JournalEntryRepository journalEntryRepository(
            NamedParameterJdbcTemplate jdbc, HoldRepository holds, TransactionTemplate transactions) {
        return new JdbcJournalEntryRepository(jdbc, holds, transactions);
    }

    @Bean
    LedgerReadModel ledgerReadModel(NamedParameterJdbcTemplate jdbc) {
        return new JdbcLedgerReadModel(jdbc);
    }

    @Bean
    ReferenceGenerator referenceGenerator(NamedParameterJdbcTemplate jdbc, Clock clock) {
        return new JdbcReferenceGenerator(jdbc, clock);
    }

    @Bean
    LockWaits lockWaits(MeterRegistry registry) {
        return new MicrometerLockWaits(registry);
    }

    @Bean
    AuditLog auditLog(NamedParameterJdbcTemplate jdbc, ObjectMapper json, LockWaits lockWaits) {
        return new JdbcAuditLog(jdbc, json, lockWaits);
    }

    @Bean
    AuditContext auditContext() {
        return new HttpAuditContext();
    }

    @Bean
    AuditTrail auditTrail(AuditLog auditLog, AuditContext auditContext, Clock clock) {
        return new AuditTrail(auditLog, auditContext, clock);
    }

    @Bean
    EventOutbox eventOutbox(NamedParameterJdbcTemplate jdbc) {
        // Its own ObjectMapper, not the application's. The application's serves the REST contract and
        // this one serves the AsyncAPI contract; sharing one means a setting changed for one silently
        // changes the other, and the change surfaces as a consumer failing to parse days later.
        return new JdbcEventOutbox(jdbc, LedgerEventJson.mapper());
    }

    @Bean
    TransferEvents transferEvents(EventOutbox outbox, AuditContext auditContext) {
        return new TransferEvents(outbox, auditContext);
    }

    @Bean
    EventPublisher eventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${tessera.outbox.publish-timeout-ms:10000}") long timeoutMillis) {
        return new KafkaEventPublisher(kafka, Duration.ofMillis(timeoutMillis));
    }

    @Bean
    OutboxRelay outboxRelay(
            NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, EventPublisher publisher) {
        return new OutboxRelay(jdbc, transactions, publisher);
    }

    @Bean
    @ConditionalOnProperty(name = "tessera.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelayScheduler outboxRelayScheduler(
            OutboxRelay relay, @Value("${tessera.outbox.batch-size:100}") int batchSize) {
        // Switchable because relaying and serving requests are separable jobs: an operator may want
        // the relay on a subset of instances, and a test that is not about Kafka should not be
        // hammering a broker that is not there.
        return new OutboxRelayScheduler(relay, batchSize);
    }

    @Bean
    MoneyMovementMetricsFilter moneyMovementMetricsFilter(MeterRegistry registry) {
        return new MoneyMovementMetricsFilter(registry);
    }

    @Bean
    OutboxGauges outboxGauges(OutboxRelay relay, MeterRegistry registry) {
        // Gauges, not counters: both answer "how bad is it right now", and both are read from the
        // table rather than accumulated in memory - so a restarted instance reports the truth
        // immediately instead of starting again from zero.
        Gauge.builder("ledger.outbox.pending", relay, OutboxRelay::pending)
                .description("Events written but not yet published to the broker")
                .register(registry);
        Gauge.builder("ledger.outbox.lag", relay, OutboxRelay::oldestPendingSeconds)
                .description("Age of the oldest unpublished event")
                .baseUnit("seconds")
                .register(registry);
        // The lag is the one to alert on. A backlog of ten thousand draining steadily is a busy
        // afternoon; a backlog of one that has not moved in ten minutes is an incident, and a count
        // cannot tell the two apart.
        return new OutboxGauges();
    }

    /** Nothing but a handle, so the registration above happens exactly once at startup. */
    public static final class OutboxGauges {}

    @Bean
    DatabaseSignals databaseSignals(
            NamedParameterJdbcTemplate jdbc,
            MeterRegistry registry,
            @Value("${tessera.db-signals.refresh:PT15S}") Duration refresh) {
        // Cached for a scrape interval rather than read per gauge: forty-eight series against one
        // pooled connection, every scrape, would make the pool utilisation this measures worse.
        return DatabaseSignals.register(jdbc, registry, refresh);
    }

    @Bean
    IdempotencyStore idempotencyStore(NamedParameterJdbcTemplate jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    UnitOfWork unitOfWork(
            TransactionTemplate transactions, AccountRepository accounts, LockWaits lockWaits) {
        // AccountLocks, not repeated findForUpdate calls. The deterministic order it imposes is what
        // keeps two transfers over the same pair of accounts from deadlocking.
        return new JdbcUnitOfWork(transactions, new AccountLocks(accounts, lockWaits));
    }

    @Bean
    OpenAccount openAccount(
            AccountRepository accounts,
            LedgerReadModel readModel,
            UnitOfWork unitOfWork,
            AuditTrail audit,
            Clock clock) {
        return new OpenAccount(accounts, readModel, unitOfWork, audit, clock);
    }

    @Bean
    GetAccount getAccount(
            AccountRepository accounts,
            JournalEntryRepository entries,
            LedgerReadModel readModel,
            UnitOfWork unitOfWork) {
        return new GetAccount(accounts, entries, readModel, unitOfWork);
    }

    @Bean
    GetBalance getBalance(
            AccountRepository accounts, JournalEntryRepository entries, UnitOfWork unitOfWork) {
        return new GetBalance(accounts, entries, unitOfWork);
    }

    @Bean
    GetStatement getStatement(
            AccountRepository accounts, LedgerReadModel readModel, UnitOfWork unitOfWork) {
        return new GetStatement(accounts, readModel, unitOfWork);
    }

    @Bean
    GetTransfer getTransfer(
            JournalEntryRepository entries, LedgerReadModel readModel, UnitOfWork unitOfWork) {
        return new GetTransfer(entries, readModel, unitOfWork);
    }

    @Bean
    ListHolds listHolds(AccountRepository accounts, HoldRepository holds, UnitOfWork unitOfWork) {
        return new ListHolds(accounts, holds, unitOfWork);
    }

    @Bean
    Transfer transfer(
            AccountRepository accounts,
            JournalEntryRepository entries,
            LedgerReadModel readModel,
            ReferenceGenerator references,
            UnitOfWork unitOfWork,
            AuditTrail audit,
            TransferEvents events,
            Clock clock) {
        return new Transfer(accounts, entries, readModel, references, unitOfWork, audit, events, clock);
    }

    @Bean
    ReverseTransfer reverseTransfer(
            AccountRepository accounts,
            JournalEntryRepository entries,
            LedgerReadModel readModel,
            ReferenceGenerator references,
            UnitOfWork unitOfWork,
            AuditTrail audit,
            TransferEvents events,
            Clock clock) {
        return new ReverseTransfer(
                accounts, entries, readModel, references, unitOfWork, audit, events, clock);
    }

    @Bean
    PlaceHold placeHold(
            AccountRepository accounts,
            HoldRepository holds,
            ReferenceGenerator references,
            UnitOfWork unitOfWork,
            AuditTrail audit,
            Clock clock) {
        return new PlaceHold(accounts, holds, references, unitOfWork, audit, clock);
    }

    @Bean
    CaptureHold captureHold(
            HoldRepository holds,
            Transfer transfer,
            UnitOfWork unitOfWork,
            AuditTrail audit,
            Clock clock) {
        return new CaptureHold(holds, transfer, unitOfWork, audit, clock);
    }

    @Bean
    ReleaseHold releaseHold(
            HoldRepository holds, UnitOfWork unitOfWork, AuditTrail audit, Clock clock) {
        return new ReleaseHold(holds, unitOfWork, audit, clock);
    }

    @Bean
    CorrelationIdFilter correlationIdFilter() {
        // Ordered ahead of the idempotency filter by the Ordered interface it implements, so that a
        // request rejected before any controller runs still carries an id.
        return new CorrelationIdFilter();
    }

    @Bean
    RequestFingerprint requestFingerprint(ObjectMapper json) {
        return new RequestFingerprint(json);
    }

    @Bean
    ProblemWriter problemWriter(ObjectMapper json) {
        return new ProblemWriter(json);
    }

    @Bean
    IdempotencyFilter idempotencyFilter(
            IdempotencyStore store,
            RequestFingerprint fingerprints,
            TransactionTemplate transactions,
            ProblemWriter problems) {
        return new IdempotencyFilter(store, fingerprints, transactions, problems);
    }
}
