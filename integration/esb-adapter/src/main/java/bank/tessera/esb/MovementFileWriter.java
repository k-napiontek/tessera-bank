package bank.tessera.esb;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The last hop: 2011 becomes 1995, as bytes appended to the file tonight's COBOL cycle will read.
 *
 * <p>Two properties, and the overnight cycle depends on both.
 *
 * <h3>Written once per transfer</h3>
 *
 * <p>Delivery is at-least-once, so the same transfer arrives more than once as a matter of course.
 * WP-11a could hand that problem to the system of record, whose unique constraint answers
 * {@code alreadyApplied}; a file has no such constraint to hand it to.
 *
 * <p>So <strong>the file is its own constraint</strong>: before appending, the writer looks for the
 * transfer reference among the records already there. That is a seek rather than a parse - fixed
 * width means {@code MOV-TRANSFER-REF} is always the first twenty bytes of every hundred-and-twenty
 * - and it is done under the same lock as the append, so the answer cannot go stale in between.
 *
 * <p>Trusting {@code alreadyApplied} instead would have been one line and would have been wrong. If
 * this process dies after the SOAP call returns and before the record lands, the redelivery is told
 * the transfer was already applied, skips the write, and the mainframe is short by exactly that
 * transfer for ever. Asking the file is the only question whose answer survives the crash.
 *
 * <h3>Never left half-written</h3>
 *
 * <p>Both legs go out in one write and are forced to disk together. Any failure truncates the file
 * back to the length it had before the attempt, because {@code sortrec.py} abends {@code STEP010}
 * with RC 12 on a file that is not a whole number of records - so a partial record does not fail
 * here, where the cause is visible. It fails in another tier, in the middle of the night, naming
 * something else.
 *
 * <p>For the same reason a file that is <em>already</em> not a multiple of 120 is refused rather
 * than appended to. Something else corrupted it, and adding good records to a bad file only makes
 * the abend harder to explain.
 *
 * <h3>Not sorted, deliberately</h3>
 *
 * <p>The copybook says the file is ascending by {@code MOV-ACCT-REF} and this class writes in the
 * order events arrive. {@code STEP010} of the cycle is what puts it in that order, and that is the
 * entire reason that step exists. Sorting here would duplicate it and would break the moment the
 * file grew past what one process holds in memory.
 */
public class MovementFileWriter {

    private static final Logger LOG = LoggerFactory.getLogger(MovementFileWriter.class);

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /**
     * One monitor per file, for the writers inside this JVM.
     *
     * <p>{@link FileChannel#lock()} keeps two <em>processes</em> apart, which is what matters when
     * the platform repositories run more than one instance of this component. It does not keep two
     * threads in one process apart: a second lock attempt on the same file from the same JVM throws
     * {@code OverlappingFileLockException} rather than waiting. So both are needed, and they nest in
     * this order.
     */
    private static final ConcurrentMap<String, Object> MONITORS =
            new ConcurrentHashMap<String, Object>();

    private final File file;
    private final Object monitor;

    public MovementFileWriter(File file) {
        this.file = file;
        this.monitor = monitorFor(file);
    }

    /**
     * Append one transfer's legs, unless the file already holds that transfer.
     *
     * @return true when the legs were written, false when the transfer was already there
     * @throws TransferHandlingException at stage {@code WRITE} - permanently when the file is not a
     *     whole number of records, transiently when the write itself failed
     */
    public boolean append(String transferRef, List<byte[]> legs) {
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(file.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {

                // Released before the channel closes, and held across both the look-up and the
                // append - the two together are what makes "write it once" true.
                FileLock lock = channel.lock();
                try {
                    long before = channel.size();
                    requireWholeRecords(before);

                    if (holds(channel, before, transferRef)) {
                        LOG.info("transfer {} is already in the movement file; nothing appended",
                                transferRef);
                        return false;
                    }

                    appendOrUndo(channel, before, legs, transferRef);
                    LOG.info("transfer {} written to the movement file as {} legs at offset {}",
                            transferRef, legs.size(), before);
                    return true;
                } finally {
                    lock.release();
                }

            } catch (TransferHandlingException refused) {
                throw refused;
            } catch (IOException unwritable) {
                // The file could not even be opened or locked. A disk that is briefly unavailable is
                // not a reason to discard a payment, so the message is not acknowledged and the
                // broker redelivers.
                throw TransferHandlingException.transient_(FailureStage.WRITE,
                        "the movement file could not be opened for append", unwritable);
            }
        }
    }

    private void requireWholeRecords(long length) {
        if (length % MovementRecord.LENGTH != 0) {
            throw TransferHandlingException.permanent(FailureStage.WRITE,
                    "the movement file is " + length + " bytes, which is not a whole number of "
                            + MovementRecord.LENGTH + "-byte records; appending to it would abend"
                            + " STEP010 rather than fail here");
        }
    }

    /**
     * Whether this transfer is already in the file.
     *
     * <p>A seek over the key field, never a search for the text anywhere: a remittance reference is
     * free text and may perfectly well quote a transfer reference, and a substring match would then
     * drop a real movement on the floor.
     *
     * <p>Linear in the size of the file, which is correct for a bank day's worth of records and
     * unmeasured for anything larger.
     */
    private boolean holds(FileChannel channel, long length, String transferRef) throws IOException {
        ByteBuffer key = ByteBuffer.allocate(MovementRecord.TRANSFER_REF_SIZE);
        byte[] wanted = transferRef.getBytes(ASCII);

        for (long at = 0; at < length; at += MovementRecord.LENGTH) {
            key.clear();
            long position = at + MovementRecord.TRANSFER_REF_AT;
            while (key.hasRemaining()) {
                int read = channel.read(key, position + key.position());
                if (read < 0) {
                    // The file shrank under the lock, which should not be possible. Treat what is
                    // left as not containing the transfer rather than guessing.
                    return false;
                }
            }
            if (matches(key.array(), wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] fromFile, byte[] wanted) {
        if (fromFile.length != wanted.length) {
            return false;
        }
        for (int i = 0; i < fromFile.length; i++) {
            if (fromFile[i] != wanted[i]) {
                return false;
            }
        }
        return true;
    }

    private void appendOrUndo(FileChannel channel, long before, List<byte[]> legs,
            String transferRef) {
        ByteBuffer all = ByteBuffer.allocate(legs.size() * MovementRecord.LENGTH);
        for (byte[] leg : legs) {
            all.put(leg);
        }
        all.flip();

        try {
            channel.position(before);
            write(channel, all);
        } catch (IOException partial) {
            undo(channel, before, transferRef, partial);
            throw TransferHandlingException.transient_(FailureStage.WRITE,
                    "the movement file could not be appended to", partial);
        }
    }

    /**
     * The write itself, forced to disk before the lock is released.
     *
     * <p>Package-private and overridable because there is no other way to reach the recovery path
     * above from a test, and an untested recovery path is a recovery path that does not work.
     */
    void write(FileChannel channel, ByteBuffer legs) throws IOException {
        while (legs.hasRemaining()) {
            channel.write(legs);
        }
        channel.force(true);
    }

    private void undo(FileChannel channel, long before, String transferRef, IOException cause) {
        try {
            channel.truncate(before);
            channel.force(true);
        } catch (IOException hopeless) {
            // Nothing further can be done from here, and it must not be quiet: the file may now be
            // a partial record, and STEP010 will abend on it tonight. No payload in the message.
            LOG.error("transfer {} failed to append and the movement file could not be truncated"
                    + " back to {} bytes; it may no longer be a whole number of records",
                    transferRef, before, hopeless);
            return;
        }
        LOG.warn("transfer {} failed to append; the movement file was returned to {} bytes: {}",
                transferRef, before, cause.getMessage());
    }

    private static Object monitorFor(File file) {
        String path;
        try {
            path = file.getCanonicalPath();
        } catch (IOException notYetThere) {
            // The file need not exist yet, and on this platform getCanonicalPath rarely fails for
            // that reason. The absolute path is a good enough key when it does.
            path = file.getAbsolutePath();
        }
        Object existing = MONITORS.get(path);
        if (existing != null) {
            return existing;
        }
        Object created = new Object();
        Object raced = MONITORS.putIfAbsent(path, created);
        return raced != null ? raced : created;
    }
}
