package bank.tessera.backoffice.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BusinessDatesTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void findsEveryReportNewestFirst() throws Exception {
        File directory = reportsFor("20260816", "20260818", "20260817");
        assertEquals(java.util.Arrays.asList("20260818", "20260817", "20260816"),
                BusinessDates.available(directory));
    }

    @Test
    public void ignoresAnythingThatIsNotAReport() throws Exception {
        File directory = reportsFor("20260818");
        new File(directory, "BREAKS-2026081.json").createNewFile();
        new File(directory, "notes.txt").createNewFile();
        new File(directory, "BREAKS-20260818.json.bak").createNewFile();
        List<String> found = BusinessDates.available(directory);
        assertEquals(1, found.size());
        assertEquals("20260818", found.get(0));
    }

    /**
     * An operator arrives at 07:00 to work last night's reconciliation. Defaulting to today would
     * show an empty page every morning until the job ran, which looks exactly like a clean night.
     */
    @Test
    public void defaultsToTheNewestReportRatherThanToday() throws Exception {
        File directory = reportsFor("20260817", "20260818");
        assertEquals("20260818", BusinessDates.resolve(directory, null));
    }

    @Test
    public void honoursARequestedDateThatExists() throws Exception {
        File directory = reportsFor("20260817", "20260818");
        assertEquals("20260817", BusinessDates.resolve(directory, "20260817"));
    }

    /** A date nobody has a report for falls back rather than showing an empty list as a clean night. */
    @Test
    public void fallsBackWhenTheRequestedDateHasNoReport() throws Exception {
        File directory = reportsFor("20260818");
        assertEquals("20260818", BusinessDates.resolve(directory, "20250101"));
    }

    @Test
    public void aDirectoryWithNoReportsResolvesToNothing() throws Exception {
        assertNull(BusinessDates.resolve(folder.newFolder("empty"), null));
    }

    /** The parameter arrives from a query string, so it is whatever anybody typed. */
    @Test
    public void refusesAnythingThatIsNotACcyymmdd() {
        assertTrue(!BusinessDates.isBusinessDate("../../etc/passwd"));
        assertTrue(!BusinessDates.isBusinessDate("2026-08-18"));
        assertTrue(!BusinessDates.isBusinessDate(null));
        assertTrue(BusinessDates.isBusinessDate("20260818"));
    }

    private File reportsFor(String... dates) throws Exception {
        File directory = folder.newFolder("recon" + System.nanoTime());
        for (int i = 0; i < dates.length; i++) {
            new File(directory, "BREAKS-" + dates[i] + ".json").createNewFile();
        }
        return directory;
    }
}
