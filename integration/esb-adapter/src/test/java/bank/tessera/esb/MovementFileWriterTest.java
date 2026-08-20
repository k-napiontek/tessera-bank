package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The movement file, and the two properties the overnight cycle depends on.
 *
 * <p><strong>Written once per transfer.</strong> WP-11a could delegate idempotency to the system of
 * record's unique constraint; a file has none to delegate to, so the file is its own constraint. The
 * writer looks for the transfer reference in what is already there before it appends.
 *
 * <p><strong>Never left half-written.</strong> {@code sortrec.py} abends {@code STEP010} with RC 12
 * on a file whose length is not a multiple of 120, so a partial record does not fail here where it
 * happened - it fails at 02:00, in another tier, naming something else.
 */
class MovementFileWriterTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static final String DEBIT_ACCOUNT = "TB00000000000009";
    private static final String CREDIT_ACCOUNT = "TB00000000000001";

    @TempDir
    File directory;

    private File file;

    @BeforeEach
    void nameTheFile() {
        file = new File(directory, "MOVEMENT.DAT");
    }

    @Test
    void theFileIsCreatedOnTheFirstTransferAndHoldsBothLegs() throws Exception {
        assertFalse(file.exists());

        boolean written = new MovementFileWriter(file).append("TB000000000000000101", legsOf(
                "TB000000000000000101", 2_500L));

        assertTrue(written);
        assertEquals(240, file.length(), "two 120-byte legs, and nothing else");
        assertEquals("TB000000000000000101", transferRefAt(0));
        assertEquals("01", legNoAt(0));
        assertEquals("02", legNoAt(1));
    }

    /**
     * The copybook says the file is ascending by {@code MOV-ACCT-REF}, and this tier deliberately
     * does not put it in that order: {@code STEP010} of the cycle sorts, which is the whole reason
     * that step exists. The debit leg here lands on a higher account reference than the credit leg,
     * so a writer that helpfully sorted would swap them.
     */
    @Test
    void theLegsStayInTheOrderTheyArrived() throws Exception {
        new MovementFileWriter(file).append("TB000000000000000101", legsOf(
                "TB000000000000000101", 2_500L));

        assertEquals(DEBIT_ACCOUNT, accountRefAt(0), "the writer sorted, and STEP010 exists to");
        assertEquals(CREDIT_ACCOUNT, accountRefAt(1));
    }

    @Test
    void aTransferAlreadyInTheFileIsNotAppendedASecondTime() throws Exception {
        MovementFileWriter writer = new MovementFileWriter(file);
        writer.append("TB000000000000000101", legsOf("TB000000000000000101", 2_500L));
        byte[] afterFirst = Files.readAllBytes(file.toPath());

        boolean written = writer.append("TB000000000000000101",
                legsOf("TB000000000000000101", 2_500L));

        assertFalse(written, "the writer claimed to have appended a transfer already in the file");
        assertArrayEquals(afterFirst, Files.readAllBytes(file.toPath()),
                "a redelivery changed the movement file");
    }

    @Test
    void aDifferentTransferIsAppendedAfterTheFirst() throws Exception {
        MovementFileWriter writer = new MovementFileWriter(file);
        writer.append("TB000000000000000101", legsOf("TB000000000000000101", 2_500L));
        writer.append("TB000000000000000102", legsOf("TB000000000000000102", 1_337L));

        assertEquals(480, file.length());
        assertEquals("TB000000000000000101", transferRefAt(0));
        assertEquals("TB000000000000000102", transferRefAt(2));
    }

    /**
     * A transfer reference is 20 bytes at offset 0 of a 120-byte record. Looking for it as a
     * substring anywhere in the file would match a remittance reference that happened to contain
     * one, and would then silently drop a real movement.
     */
    @Test
    void theSearchIsForTheKeyFieldRatherThanForTheTextAnywhere() throws Exception {
        MovementFileWriter writer = new MovementFileWriter(file);
        writer.append("TB000000000000000101", legsOf("TB000000000000000101", 2_500L,
                "SEE TB000000000000000102"));

        boolean written = writer.append("TB000000000000000102",
                legsOf("TB000000000000000102", 1_337L));

        assertTrue(written, "a reference quoted in a remittance field suppressed a real transfer");
        assertEquals(480, file.length());
    }

    // -- the file stays readable by the batch --------------------------------------------------

    @Test
    void aFileThatIsNotAWholeNumberOfRecordsIsRefusedRatherThanAppendedTo() throws Exception {
        Files.write(file.toPath(), new byte[121]);

        TransferHandlingException refused = assertThrows(TransferHandlingException.class,
                () -> new MovementFileWriter(file).append("TB000000000000000101",
                        legsOf("TB000000000000000101", 2_500L)));

        assertEquals(FailureStage.WRITE, refused.stage());
        assertTrue(refused.isPermanent(), "redelivering the message will not repair the file");
        assertEquals(121, file.length(), "the writer appended to a file it had just called corrupt");
    }

    @Test
    void aWriteThatFailsPartWayLeavesTheFileExactlyAsItWas() throws Exception {
        MovementFileWriter writer = new MovementFileWriter(file);
        writer.append("TB000000000000000101", legsOf("TB000000000000000101", 2_500L));
        byte[] before = Files.readAllBytes(file.toPath());

        MovementFileWriter failing = new HalfWritingWriter(file);

        assertThrows(TransferHandlingException.class,
                () -> failing.append("TB000000000000000102", legsOf("TB000000000000000102", 1_337L)));

        assertArrayEquals(before, Files.readAllBytes(file.toPath()),
                "a failed append left bytes behind, and STEP010 abends on a partial record");
        assertEquals(0, file.length() % 120);
    }

    /**
     * The reason the duplicate check reads the file rather than trusting {@code alreadyApplied}: a
     * write that died leaves the far end holding the transfer and the file not holding it, and only
     * a redelivery that looks at the file gets the movement to the mainframe at all.
     */
    @Test
    void aRedeliveryAfterAFailedWriteCompletesTheTransferRatherThanSkippingIt() throws Exception {
        MovementFileWriter failing = new HalfWritingWriter(file);
        assertThrows(TransferHandlingException.class,
                () -> failing.append("TB000000000000000101", legsOf("TB000000000000000101", 2_500L)));

        boolean written = new MovementFileWriter(file).append("TB000000000000000101",
                legsOf("TB000000000000000101", 2_500L));

        assertTrue(written, "the retry treated a transfer that never landed as already written");
        assertEquals(240, file.length());
    }

    @Test
    void aTransientWriteFailureIsNotDeadLettered() throws Exception {
        TransferHandlingException failure = assertThrows(TransferHandlingException.class,
                () -> new HalfWritingWriter(file).append("TB000000000000000101",
                        legsOf("TB000000000000000101", 2_500L)));

        assertEquals(FailureStage.WRITE, failure.stage());
        assertFalse(failure.isPermanent(),
                "a disk that was briefly unwritable is not a reason to discard a payment");
    }

    // -- more than one writer at a time ---------------------------------------------------------

    /**
     * Eight transfers delivered at once. Every one has to land exactly once and the file has to stay
     * a whole number of records throughout, or the cycle abends on a file this tier produced.
     */
    @Test
    void concurrentDeliveriesAllLandAndTheFileStaysWellFormed() throws Exception {
        int writers = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        List<Throwable> failures = new ArrayList<Throwable>();

        for (int i = 0; i < writers; i++) {
            final String transferRef = String.format("TB0000000000000001%02d", i);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        new MovementFileWriter(file).append(transferRef, legsOf(transferRef, 100L));
                    } catch (Throwable problem) {
                        synchronized (failures) {
                            failures.add(problem);
                        }
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }

        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the writers did not finish");
        assertTrue(failures.isEmpty(), "a concurrent delivery failed: " + failures);

        assertEquals(writers * 240, file.length());
        assertEquals(0, file.length() % 120);
        for (int i = 0; i < writers; i++) {
            String transferRef = String.format("TB0000000000000001%02d", i);
            assertEquals(2, legCountOf(transferRef), "wrong number of legs for " + transferRef);
        }
    }

    /** The same transfer delivered twice at once still lands once - the case a sequential test misses. */
    @Test
    void oneTransferDeliveredTwiceAtOnceIsStillWrittenOnce() throws Exception {
        int writers = 6;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);

        for (int i = 0; i < writers; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        new MovementFileWriter(file).append("TB000000000000000101",
                                legsOf("TB000000000000000101", 2_500L));
                    } catch (Exception ignored) {
                        // Counted by the file's contents below, which is the only claim that matters.
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }

        go.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertEquals(240, file.length(), "the same transfer was written more than once");
        assertEquals(2, legCountOf("TB000000000000000101"));
    }

    // -- helpers -------------------------------------------------------------------------------

    /** A writer whose append dies after the first leg, leaving 120 bytes of a 240-byte write. */
    private static final class HalfWritingWriter extends MovementFileWriter {
        HalfWritingWriter(File file) {
            super(file);
        }

        @Override
        void write(FileChannel channel, ByteBuffer legs) throws IOException {
            ByteBuffer half = legs.duplicate();
            half.limit(half.position() + 120);
            channel.write(half);
            throw new IOException("the disk went away half way through");
        }
    }

    private static List<byte[]> legsOf(String transferRef, long amountMinor) {
        return legsOf(transferRef, amountMinor, "SALARY");
    }

    private static List<byte[]> legsOf(String transferRef, long amountMinor, String reference) {
        return Arrays.asList(
                MovementRecord.of(transferRef, 1, DEBIT_ACCOUNT, "DEBIT", "PLN", amountMinor,
                        "20260820", "20260820093000", reference),
                MovementRecord.of(transferRef, 2, CREDIT_ACCOUNT, "CREDIT", "PLN", amountMinor,
                        "20260820", "20260820093000", reference));
    }

    private String transferRefAt(int record) throws IOException {
        return fieldAt(record, 0, 20);
    }

    private String legNoAt(int record) throws IOException {
        return fieldAt(record, 20, 2);
    }

    private String accountRefAt(int record) throws IOException {
        return fieldAt(record, 22, 16);
    }

    private String fieldAt(int record, int offset, int size) throws IOException {
        byte[] all = Files.readAllBytes(file.toPath());
        return new String(all, record * 120 + offset, size, ASCII);
    }

    private int legCountOf(String transferRef) throws IOException {
        byte[] all = Files.readAllBytes(file.toPath());
        int found = 0;
        for (int at = 0; at < all.length; at += 120) {
            if (transferRef.equals(new String(all, at, 20, ASCII))) {
                found++;
            }
        }
        return found;
    }
}
