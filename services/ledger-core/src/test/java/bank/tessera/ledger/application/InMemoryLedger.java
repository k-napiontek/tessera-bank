package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.AccountDates;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.AuditContext;
import bank.tessera.ledger.port.AuditEntry;
import bank.tessera.ledger.port.AuditLog;
import bank.tessera.ledger.port.EventOutbox;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.Movement;
import bank.tessera.ledger.port.ReferenceGenerator;
import bank.tessera.ledger.port.StatementPage;
import bank.tessera.ledger.port.TransferPostedEvent;
import bank.tessera.ledger.port.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every port, in memory.
 *
 * <p>The use cases exist in {@code ledger-core} so they can be driven like this: no database, no
 * container, no framework, milliseconds per test. What these fakes cannot prove is anything about
 * locking or isolation, so the tests written against them assert sequencing and decisions only, and
 * the concurrency claims are made in {@code ledger-persistence} against real PostgreSQL.
 */
final class InMemoryLedger {

    final Map<AccountRef, Account> accountsByRef = new LinkedHashMap<>();
    final Map<EntryRef, JournalEntry> entriesByRef = new LinkedHashMap<>();
    final Map<HoldRef, Hold> holdsByRef = new LinkedHashMap<>();
    final Map<AccountRef, AccountDates> datesByAccount = new LinkedHashMap<>();
    final Map<EntryRef, Instant> postedAtByEntry = new LinkedHashMap<>();
    final Map<EntryRef, String> referenceByEntry = new LinkedHashMap<>();

    /** Every set of accounts a use case asked to be locked, in call order. */
    final List<List<AccountRef>> lockRequests = new ArrayList<>();

    /** Every audit entry appended, in order. Nothing here rolls back, so a test asserting that a
     * failed use case wrote nothing is asserting that it never called append at all. */
    final List<AuditEntry> auditEntries = new ArrayList<>();

    final AuditLog auditLog = auditEntries::add;

    /** The context ledger-api will supply from the MDC, fixed here so hashes are reproducible. */
    static final String CORRELATION_ID = "5c2f0b1e-0000-4000-8000-000000000001";

    /** Every event enqueued, in order. Nothing here rolls back, so a test asserting that a failed
     * use case enqueued nothing is asserting that it never called publish at all. */
    final List<TransferPostedEvent> outboxEvents = new ArrayList<>();

    final EventOutbox outbox = outboxEvents::add;

    /** The audit trail every money-moving use case now requires. */
    AuditTrail auditTrail(java.time.Clock clock) {
        return new AuditTrail(auditLog, auditContext, clock);
    }

    /** The outbox every posting use case now requires. */
    TransferEvents transferEvents() {
        return new TransferEvents(outbox, auditContext);
    }

    final AuditContext auditContext = new AuditContext() {
        @Override
        public String actor() {
            return "ledger-api";
        }

        @Override
        public Optional<String> correlationId() {
            return Optional.of(CORRELATION_ID);
        }
    };

    /** How many transactions were opened, so a test can assert reads happen inside exactly one. */
    int transactions;

    final UnitOfWork unitOfWork = new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            transactions++;
            return work.get();
        }

        @Override
        public <T> T inTransactionLocking(Collection<AccountRef> accounts, Supplier<T> work) {
            transactions++;
            lockRequests.add(accounts.stream()
                    .distinct()
                    .sorted(Comparator.comparing(AccountRef::value))
                    .toList());
            return work.get();
        }
    };

    final AccountRepository accounts = new AccountRepository() {
        @Override
        public Optional<Account> findByReference(AccountRef reference) {
            return Optional.ofNullable(accountsByRef.get(reference));
        }

        @Override
        public Optional<Account> findForUpdate(AccountRef reference) {
            return findByReference(reference);
        }

        @Override
        public Account save(Account account) {
            accountsByRef.put(account.reference(), account);
            return account;
        }
    };

    final HoldRepository holds = new HoldRepository() {
        @Override
        public Hold save(Hold hold) {
            holdsByRef.put(hold.reference(), hold);
            return hold;
        }

        @Override
        public Optional<Hold> findByReference(HoldRef reference) {
            return Optional.ofNullable(holdsByRef.get(reference));
        }

        @Override
        public List<Hold> findActiveFor(AccountRef account) {
            return findAllFor(account).stream().filter(Hold::isActive).toList();
        }

        @Override
        public List<Hold> findAllFor(AccountRef account) {
            return holdsByRef.values().stream()
                    .filter(hold -> hold.account().equals(account))
                    .toList();
        }
    };

    final JournalEntryRepository entries = new JournalEntryRepository() {
        @Override
        public JournalEntry append(JournalEntry entry) {
            entriesByRef.put(entry.reference(), entry);
            postedAtByEntry.putIfAbsent(entry.reference(), Instant.EPOCH);
            return entry;
        }

        @Override
        public Optional<JournalEntry> findByReference(EntryRef reference) {
            return Optional.ofNullable(entriesByRef.get(reference));
        }

        @Override
        public List<JournalEntry> findByAccount(AccountRef account, LocalDate from, LocalDate to) {
            return entriesByRef.values().stream()
                    .filter(entry -> !entry.valueDate().isBefore(from) && !entry.valueDate().isAfter(to))
                    .filter(entry -> entry.postings().stream()
                            .anyMatch(posting -> posting.account().equals(account)))
                    .toList();
        }

        @Override
        public Balance balanceOf(AccountRef account) {
            Account owner = accountsByRef.get(account);
            AccountType type = owner.type();
            Money booked = Money.zero(owner.currency());
            for (JournalEntry entry : entriesByRef.values()) {
                for (Posting posting : entry.postings()) {
                    if (posting.account().equals(account)) {
                        booked = booked.plus(posting.effectOn(type));
                    }
                }
            }
            return Balance.of(account, booked, holds.findActiveFor(account));
        }
    };

    final LedgerReadModel readModel = new LedgerReadModel() {
        @Override
        public Optional<AccountDates> accountDates(AccountRef account) {
            return Optional.ofNullable(datesByAccount.get(account));
        }

        @Override
        public void recordAccountOpened(AccountRef account, LocalDate openedDate) {
            if (!accountsByRef.containsKey(account)) {
                throw new IllegalStateException("No such account: " + account);
            }
            datesByAccount.put(account, AccountDates.of(openedDate, null));
        }

        @Override
        public Optional<Instant> entryPostedAt(EntryRef entry) {
            return Optional.ofNullable(postedAtByEntry.get(entry));
        }

        @Override
        public Optional<EntryRef> reversedBy(EntryRef entry) {
            return entriesByRef.values().stream()
                    .filter(candidate -> candidate.reverses().filter(entry::equals).isPresent())
                    .map(JournalEntry::reference)
                    .findFirst();
        }

        @Override
        public Optional<String> entryReference(EntryRef entry) {
            return Optional.ofNullable(referenceByEntry.get(entry));
        }

        @Override
        public void recordEntryReference(EntryRef entry, String reference) {
            if (!entriesByRef.containsKey(entry)) {
                throw new IllegalStateException("No such entry: " + entry);
            }
            referenceByEntry.put(entry, reference);
        }

        @Override
        public StatementPage statementPage(
                AccountRef account, LocalDate from, LocalDate to, String cursor, int limit) {
            List<Movement> everything = movementsFor(account);
            List<Movement> inRange = everything.stream()
                    .filter(movement -> !movement.valueDate().isBefore(from))
                    .filter(movement -> !movement.valueDate().isAfter(to))
                    .toList();

            int start = 0;
            if (cursor != null) {
                String resumeAfter = decodeCursor(cursor);
                start = indexOf(inRange, resumeAfter) + 1;
                if (start == 0) {
                    throw new IllegalArgumentException("Statement cursor was not issued by this ledger.");
                }
            }

            List<Movement> page = inRange.subList(start, Math.min(start + limit, inRange.size()));
            boolean hasMore = start + limit < inRange.size();

            Money opening = Money.zero(accountsByRef.get(account).currency());
            String boundary = page.isEmpty()
                    ? (cursor == null ? null : decodeCursor(cursor))
                    : page.get(0).movementReference();
            for (Movement movement : everything) {
                if (boundary != null && movement.movementReference().equals(boundary)) {
                    if (page.isEmpty()) {
                        opening = opening.plus(effectOf(account, movement));
                    }
                    break;
                }
                if (boundary == null && !movement.valueDate().isBefore(from)) {
                    break;
                }
                opening = opening.plus(effectOf(account, movement));
            }

            String next = hasMore
                    ? encodeCursor(page.get(page.size() - 1).movementReference())
                    : null;
            return StatementPage.of(page, opening, next);
        }
    };

    private Money effectOf(AccountRef account, Movement movement) {
        AccountType type = accountsByRef.get(account).type();
        return type.signedEffect(movement.direction(), movement.amount());
    }

    private static int indexOf(List<Movement> movements, String movementReference) {
        for (int i = 0; i < movements.size(); i++) {
            if (movements.get(i).movementReference().equals(movementReference)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Every movement touching the account, oldest first, over all time.
     *
     * <p>Ordered the way the adapter orders it - value date, posting instant, entry reference, leg -
     * so the fake and PostgreSQL agree about what "the next page" means. A fake that sorted
     * differently would let a paging bug pass here and fail only against the database.
     */
    List<Movement> movementsFor(AccountRef account) {
        List<Movement> movements = new ArrayList<>();
        for (JournalEntry entry : entriesByRef.values()) {
            List<Posting> postings = entry.postings();
            for (int i = 0; i < postings.size(); i++) {
                Posting posting = postings.get(i);
                if (posting.account().equals(account)) {
                    movements.add(Movement.of(
                            entry.reference(),
                            i + 1,
                            posting.account(),
                            posting.direction(),
                            posting.amount(),
                            entry.valueDate(),
                            postedAtByEntry.getOrDefault(entry.reference(), Instant.EPOCH),
                            referenceByEntry.get(entry.reference())));
                }
            }
        }
        movements.sort(Comparator.comparing(Movement::valueDate)
                .thenComparing(Movement::postedAt)
                .thenComparing(movement -> movement.entry().value())
                .thenComparingInt(Movement::legNo));
        return movements;
    }

    private static String encodeCursor(String movementReference) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(movementReference.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Statement cursor was not issued by this ledger.");
        }
    }

    /** Hands out references in order, so a test can name the one it expects. */
    static final class SequentialReferences implements ReferenceGenerator {

        private int entries;
        private int holds;

        @Override
        public EntryRef nextEntryReference() {
            return EntryRef.of(String.format("TB20260819%010d", ++entries));
        }

        @Override
        public HoldRef nextHoldReference() {
            return HoldRef.of(String.format("HL20260819%010d", ++holds));
        }
    }
}
