package bank.tessera.ledger.loader;

import bank.tessera.ledger.loader.LedgerRows.AccountRow;
import bank.tessera.ledger.loader.LedgerRows.AuditRow;
import bank.tessera.ledger.loader.LedgerRows.BalanceRow;
import bank.tessera.ledger.loader.LedgerRows.EntryRow;
import bank.tessera.ledger.loader.LedgerRows.HoldRow;
import bank.tessera.ledger.loader.LedgerRows.PostingRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Digests every row on its way past, so that "the same seed produces the same dataset" is a
 * statement somebody can check.
 *
 * <p>Row counts are not enough and the Definition of Done says so. Two loads can write the same
 * number of rows and disagree about every amount in them, which is exactly the failure a
 * reproducibility claim is supposed to exclude - the same argument WP-20 makes for comparing a
 * schedule as bytes rather than as structs.
 *
 * <p><strong>The identity columns are not in it.</strong> {@code posting.id} and
 * {@code audit_record.seq} are the database's answers rather than the dataset's, so they are absent
 * from the row records and therefore from the digest. What is in it is every reference, every amount,
 * every timestamp and every hash - which is the whole of what a load decided.
 */
public final class DigestingSink implements RowSink {

    private final RowSink delegate;
    private final MessageDigest digest;

    public DigestingSink(RowSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available on this JVM.", impossible);
        }
    }

    /**
     * Sixty-four lowercase hex characters over everything written so far.
     *
     * <p>Taken from a clone, so that asking what the digest is does not end it. A load reports the
     * figure at the end and a test asks twice.
     */
    public String hex() {
        byte[] bytes;
        try {
            bytes = ((MessageDigest) digest.clone()).digest();
        } catch (CloneNotSupportedException unsupported) {
            throw new IllegalStateException("This JVM's SHA-256 cannot be cloned.", unsupported);
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Override
    public void account(AccountRow row) {
        absorb("account", row);
        delegate.account(row);
    }

    @Override
    public void entry(EntryRow row) {
        absorb("entry", row);
        delegate.entry(row);
    }

    @Override
    public void posting(PostingRow row) {
        absorb("posting", row);
        delegate.posting(row);
    }

    @Override
    public void hold(HoldRow row) {
        absorb("hold", row);
        delegate.hold(row);
    }

    @Override
    public void balance(BalanceRow row) {
        absorb("balance", row);
        delegate.balance(row);
    }

    @Override
    public void audit(AuditRow row) {
        absorb("audit", row);
        delegate.audit(row);
    }

    @Override
    public void checkpoint(LocalDate businessDate) {
        // Not digested. Where a load chose to commit is a property of the run, not of the dataset:
        // the same seed loaded with a different checkpoint size is the same ledger.
        delegate.checkpoint(businessDate);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * Absorbs one row, prefixed by which table it belongs to.
     *
     * <p>The table name is part of the input because the records render as text, and without it a row
     * written into the wrong table - the exact mistake a positional bulk loader makes - could leave
     * the digest unchanged.
     */
    private void absorb(String table, Record row) {
        digest.update(table.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ' ');
        digest.update(row.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 10);
    }
}
