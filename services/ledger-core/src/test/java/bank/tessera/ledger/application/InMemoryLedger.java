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
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.ReferenceGenerator;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /** Every set of accounts a use case asked to be locked, in call order. */
    final List<List<AccountRef>> lockRequests = new ArrayList<>();

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
    };

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
