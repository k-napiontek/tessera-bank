package bank.tessera.backoffice.recon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The reader, held to a document shaped exactly as {@code contracts/recon/break-report-v1.md}
 * defines it - the same document {@code batch/recon}'s own tests hold its writer to.
 *
 * <p>What is being proved is not that Jackson parses JSON. It is that this screen refuses anything
 * it has not checked: a wrong format id, control totals that do not balance, a classification the
 * contract has gained since this module was written. A screen that renders whatever it is handed
 * will one day render last week's file, or a truncated one, and say nothing at all.
 */
public class BreakReportReaderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String VALID =
            "{\n"
            + "  \"formatId\": \"TB-RECON-BREAKS-V1\",\n"
            + "  \"businessDate\": \"20260818\",\n"
            + "  \"ledgerPosition\": 4711,\n"
            + "  \"ledgerChainHash\": \"" + repeat("a", 64) + "\",\n"
            + "  \"cutOff\": { \"movementFile\": \"MOVEMENT.DAT\", \"transferRefCount\": 3 },\n"
            + "  \"masterFile\": { \"name\": \"ACCTNEW.DAT\", \"recordCount\": 200 },\n"
            + "  \"breaks\": [\n"
            + "    { \"accountRef\": \"TB00000000000002\", \"classification\": \"VALUE_DRIFT\",\n"
            + "      \"currency\": \"PLN\", \"masterBookedMinor\": -25000,\n"
            + "      \"ledgerBookedMinor\": -24000, \"differenceMinor\": -1000 },\n"
            + "    { \"accountRef\": \"TB00000000000003\", \"classification\": \"TIMING\",\n"
            + "      \"currency\": \"PLN\", \"masterBookedMinor\": 10000,\n"
            + "      \"ledgerBookedMinor\": 17000, \"differenceMinor\": -7000 },\n"
            + "    { \"accountRef\": \"TB00000000000004\", \"classification\": \"MISSING_ON_MASTER\",\n"
            + "      \"currency\": \"PLN\", \"masterBookedMinor\": null,\n"
            + "      \"ledgerBookedMinor\": 500, \"differenceMinor\": null }\n"
            + "  ],\n"
            + "  \"totals\": { \"accountsCompared\": 200, \"accountsMatched\": 197,\n"
            + "               \"accountsBroken\": 3, \"totalAbsoluteDriftMinor\": 8000 }\n"
            + "}\n";

    @Test
    public void readsTheReportTheReconciliationWrites() throws Exception {
        BreakReport report = BreakReportReader.read(write(VALID));

        assertEquals("20260818", report.getBusinessDate());
        assertEquals(4711L, report.getLedgerPosition());
        assertEquals("MOVEMENT.DAT", report.getMovementFile());
        assertEquals(3, report.getTransferRefCount());
        assertEquals(200, report.getMasterRecordCount());
        assertEquals(3, report.getBreaks().size());
        assertEquals(new BigDecimal("80.00"), report.getTotalAbsoluteDrift());
    }

    /** Timing is listed and is not an operator's to work - the whole point of ADR 0015. */
    @Test
    public void timingIsCarriedButIsNotActionable() throws Exception {
        BreakReport report = BreakReportReader.read(write(VALID));

        assertEquals("every break is listed, including the expected ones", 3,
                report.getBreaks().size());
        assertEquals("only three classifications need an operator", 2, report.getActionableCount());
        Break timing = report.getBreaks().get(1);
        assertEquals(Classification.TIMING, timing.getClassification());
        assertTrue(!timing.isActionable());
    }

    @Test
    public void aMissingSideIsNullRatherThanZero() throws Exception {
        Break missing = BreakReportReader.read(write(VALID)).getBreaks().get(2);
        assertNull(missing.getMasterBooked());
        assertNull("one figure is not a difference", missing.getDifference());
        assertEquals(new BigDecimal("5.00"), missing.getLedgerBooked());
    }

    @Test
    public void amountsAreExactDecimalsNeverDoubles() throws Exception {
        Break drift = BreakReportReader.read(write(VALID)).getBreaks().get(0);
        assertEquals(new BigDecimal("-250.00"), drift.getMasterBooked());
        assertEquals(new BigDecimal("-10.00"), drift.getDifference());
    }

    @Test
    public void aDocumentOfAnotherFormatIsRefused() throws Exception {
        try {
            BreakReportReader.read(write(VALID.replace("TB-RECON-BREAKS-V1", "TB-SOMETHING-ELSE")));
            fail("a document of an unknown format was rendered");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("TB-RECON-BREAKS-V1"));
        }
    }

    @Test
    public void controlTotalsThatDoNotBalanceAreRefused() throws Exception {
        try {
            BreakReportReader.read(write(VALID.replace("\"accountsMatched\": 197",
                    "\"accountsMatched\": 190")));
            fail("a report with unbalanced totals was rendered");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("do not balance"));
        }
    }

    @Test
    public void aBreakCountThatDisagreesWithTheListIsRefused() throws Exception {
        try {
            BreakReportReader.read(write(VALID.replace("\"accountsBroken\": 3",
                    "\"accountsBroken\": 2").replace("\"accountsMatched\": 197",
                    "\"accountsMatched\": 198")));
            fail("a report whose totals disagree with its own list was rendered");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("carries"));
        }
    }

    /** A classification the contract has gained and this module has not is a defect, not an "other". */
    @Test
    public void anUnknownClassificationIsRefused() throws Exception {
        try {
            BreakReportReader.read(write(VALID.replace("\"VALUE_DRIFT\"", "\"CURRENCY_DRIFT\"")));
            fail("an unknown classification was rendered");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("contract has changed"));
        }
    }

    @Test
    public void aDateWithNoReportIsNullRatherThanAnError() throws Exception {
        assertNull(BreakReportReader.readFor(folder.newFolder("recon"), "20260101"));
    }

    @Test
    public void aReportIsFoundByItsBusinessDate() throws Exception {
        File directory = folder.newFolder("recon");
        FileWriter writer = new FileWriter(new File(directory, "BREAKS-20260818.json"));
        try {
            writer.write(VALID);
        } finally {
            writer.close();
        }
        assertEquals("20260818",
                BreakReportReader.readFor(directory, "20260818").getBusinessDate());
    }

    private File write(String json) throws IOException {
        File file = folder.newFile("BREAKS-" + System.nanoTime() + ".json");
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(json);
        } finally {
            writer.close();
        }
        return file;
    }

    private static String repeat(String value, int times) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < times; i++) {
            text.append(value);
        }
        return text.toString();
    }
}
