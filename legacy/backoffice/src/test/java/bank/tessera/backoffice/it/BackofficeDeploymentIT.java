package bank.tessera.backoffice.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The WAR, really deployed to a real Tomcat 8.5, over real Oracle, driven over real HTTP.
 *
 * <p>Everything else in this module tests a reader or a servlet as an object. That leaves a gap the
 * width of a deployment: a JNDI name nothing binds, a JSTL jar that is not in {@code WEB-INF/lib},
 * a security constraint that protects a URL pattern the servlet is not actually mapped to, a JSP
 * that compiles only when Jasper sees it. None of those fail a unit test. They fail at deployment,
 * in {@code catalina.out}, naming none of the files that had to agree.
 *
 * <p>It walks what an operator walks: log in, read the break list rendered from a report this test
 * wrote, acknowledge a break, and read the audit row back <strong>out of Oracle</strong> rather
 * than off the screen that claims to have written it.
 */
public class BackofficeDeploymentIT {

    private static final String OPERATOR = "ops01";
    private static final String PASSWORD = "letmein";
    private static final String BUSINESS_DATE = "20260818";

    private static final String DRIFT_ACCOUNT = "TB00000000000002";
    private static final String TIMING_ACCOUNT = "TB00000000000003";

    /**
     * Acknowledging mutates state, so it gets an account of its own. JUnit 4 gives no method
     * ordering, and a test that reads the button for an account another test has acknowledged
     * passes or fails depending on the order the JVM happened to choose.
     */
    private static final String ACK_ACCOUNT = "TB00000000000004";

    private static TomcatUnderTest tomcat;
    private static Connection connection;

    @BeforeClass
    public static void deployTheWarAgainstRealOracle() throws Exception {
        connection = OracleSupport.freshSchema();

        File work = new File(required("tessera.tomcat.work"));
        File breaks = new File(work, "recon");
        File rejects = new File(new File(work, "eod"), BUSINESS_DATE);
        assertTrue(breaks.mkdirs() || breaks.isDirectory());
        assertTrue(rejects.mkdirs() || rejects.isDirectory());

        writeBreakReport(new File(breaks, "BREAKS-" + BUSINESS_DATE + ".json"));
        writeRejects(new File(rejects, "REJECTS.DAT"));

        // The two directories the descriptor declares, overridden here the way an operations team
        // would in the environment's own configuration.
        System.setProperty("tessera.breaks.dir.override", breaks.getAbsolutePath());

        tomcat = TomcatUnderTest.deploy(
                warWithDirectories(breaks, rejects.getParentFile()),
                OracleSupport.jdbcUrl(),
                OracleSupport.username(),
                OracleSupport.password(),
                "jdbc/customerMaster",
                driverJar(),
                work,
                OPERATOR + ":" + PASSWORD + ":operator");
    }

    @AfterClass
    public static void stopEverything() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
        }
        if (connection != null) {
            connection.close();
        }
    }

    /** Without credentials there is no screen at all. The constraint is the whole control. */
    @Test
    public void theScreensRefuseAnUnauthenticatedCaller() throws Exception {
        HttpURLConnection call = (HttpURLConnection) url("/breaks").openConnection();
        try {
            assertEquals(401, call.getResponseCode());
        } finally {
            call.disconnect();
        }
    }

    @Test
    public void theBreakListRendersTheReconciliationsReport() throws Exception {
        String page = get("/breaks?businessDate=" + BUSINESS_DATE);

        assertTrue("the drifting account is not listed", page.contains(DRIFT_ACCOUNT));
        assertTrue("the timing break is not listed", page.contains(TIMING_ACCOUNT));
        assertTrue("the control totals are not shown", page.contains("Control totals"));
        assertTrue("the ledger cut is not shown", page.contains("4711"));
    }

    /**
     * The classification exists to say "expected". A screen that offered to work one would undo
     * what ADR 0015 was for.
     */
    @Test
    public void aTimingBreakOffersNoAction() throws Exception {
        String page = get("/breaks?businessDate=" + BUSINESS_DATE);

        String timingRow = rowFor(page, TIMING_ACCOUNT);
        assertTrue("a timing break was offered an Acknowledge button",
                !timingRow.contains("Acknowledge"));
        assertTrue(timingRow.contains("expected"));

        String driftRow = rowFor(page, DRIFT_ACCOUNT);
        assertTrue("an actionable break was not offered an action", driftRow.contains("Acknowledge"));
    }

    @Test
    public void therejectsQueueRendersTheCyclesRejects() throws Exception {
        String page = get("/rejects?businessDate=" + BUSINESS_DATE);

        assertTrue("the reject is not listed", page.contains("TB202608180000000009"));
        assertTrue("the reason code is not shown", page.contains("E003"));
        assertTrue("the reason text is not shown", page.contains("ACCOUNT NOT ON MASTER"));
        // Decoded from COMP-3, because the alternative is asking an operator to read packed bytes.
        assertTrue("the amount was not decoded", page.contains("250.00"));
    }

    /**
     * The act, and the trail. The audit row is read out of Oracle rather than off the page that
     * claims to have written it - the screen is not a witness to its own effects.
     */
    @Test
    public void acknowledgingABreakIsRecordedAndAudited() throws Exception {
        post("/action", "action=acknowledge"
                + "&businessDate=" + BUSINESS_DATE
                + "&accountRef=" + ACK_ACCOUNT
                + "&classification=VALUE_DRIFT"
                + "&note=" + URLEncoder.encode("checked against the movement file", "UTF-8"));

        assertEquals(1, count("SELECT COUNT(*) FROM break_acknowledgement WHERE account_ref = '"
                + ACK_ACCOUNT + "'"));
        assertEquals(OPERATOR, single("SELECT acknowledged_by FROM break_acknowledgement"
                + " WHERE account_ref = '" + ACK_ACCOUNT + "'"));
        assertEquals("the acting user must come from the container, not from the form",
                OPERATOR, single("SELECT actor FROM operator_audit"
                        + " WHERE action = 'BREAK_ACKNOWLEDGED'"));

        String page = get("/breaks?businessDate=" + BUSINESS_DATE);
        assertTrue("the screen does not show the acknowledgement it made",
                rowFor(page, ACK_ACCOUNT).contains("acknowledged by"));
    }

    @Test
    public void annotatingARejectIsRecordedAndAudited() throws Exception {
        post("/action", "action=annotate"
                + "&businessDate=" + BUSINESS_DATE
                + "&transferRef=TB202608180000000009"
                + "&legNo=1"
                + "&note=" + URLEncoder.encode("raised with the ledger team", "UTF-8"));

        assertEquals("raised with the ledger team",
                single("SELECT note FROM reject_annotation"));
        assertEquals(1, count("SELECT COUNT(*) FROM operator_audit"
                + " WHERE action = 'REJECT_ANNOTATED'"));
    }

    // ---------------------------------------------------------------------------------------

    private static String rowFor(String page, String accountRef) {
        int at = page.indexOf(accountRef);
        assertTrue(accountRef + " is not on the page", at >= 0);
        int start = page.lastIndexOf("<tr", at);
        int end = page.indexOf("</tr>", at);
        return page.substring(start < 0 ? 0 : start, end < 0 ? page.length() : end);
    }

    private static String get(String path) throws Exception {
        HttpURLConnection call = (HttpURLConnection) url(path).openConnection();
        call.setRequestProperty("Authorization", basicAuth());
        try {
            assertEquals("GET " + path, 200, call.getResponseCode());
            return read(call.getInputStream());
        } finally {
            call.disconnect();
        }
    }

    private static void post(String path, String body) throws Exception {
        HttpURLConnection call = (HttpURLConnection) url(path).openConnection();
        call.setRequestMethod("POST");
        call.setDoOutput(true);
        call.setInstanceFollowRedirects(false);
        call.setRequestProperty("Authorization", basicAuth());
        call.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            Writer writer = new OutputStreamWriter(call.getOutputStream(), "UTF-8");
            try {
                writer.write(body);
            } finally {
                writer.close();
            }
            // POST-then-redirect: an action that answered with a page would be repeated by every
            // refresh, and an audit trail full of accidental duplicates is one nobody can read.
            int status = call.getResponseCode();
            if (status >= 400) {
                InputStream errors = call.getErrorStream();
                throw new AssertionError("POST " + path + " answered " + status + ":\n"
                        + (errors == null ? "(no body)" : read(errors)));
            }
            assertEquals("POST " + path + " should redirect", 302, status);
        } finally {
            call.disconnect();
        }
    }

    private static String basicAuth() throws Exception {
        String credentials = OPERATOR + ":" + PASSWORD;
        return "Basic " + javax.xml.bind.DatatypeConverter.printBase64Binary(
                credentials.getBytes("UTF-8"));
    }

    private static URL url(String path) throws Exception {
        return new URL(tomcat.baseUrl() + "backoffice" + path);
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int got;
            while ((got = stream.read(buffer)) != -1) {
                collected.write(buffer, 0, got);
            }
        } finally {
            stream.close();
        }
        return new String(collected.toByteArray(), "UTF-8");
    }

    private static int count(String sql) throws Exception {
        return Integer.parseInt(single(sql));
    }

    private static String single(String sql) throws Exception {
        Statement statement = connection.createStatement();
        try {
            ResultSet rows = statement.executeQuery(sql);
            try {
                assertTrue("no row for " + sql, rows.next());
                return rows.getString(1);
            } finally {
                rows.close();
            }
        } finally {
            statement.close();
        }
    }

    /**
     * The built WAR with its two directory parameters rewritten to this test's temporary paths.
     *
     * <p>The descriptor's defaults are production paths. Rewriting them in the artefact - rather
     * than adding a system-property fallback to the code - keeps the deployed WAR the thing that is
     * under test, and keeps a test-only branch out of {@code BackofficeConfiguration}.
     */
    private static File warWithDirectories(File breaks, File rejects) throws Exception {
        File source = new File(required("tessera.war"));
        // The file NAME is the context root - Tomcat deploys backoffice.war at /backoffice - so the
        // rewritten copy keeps the name and goes in a directory of its own. Calling it
        // backoffice-it.war deploys it at /backoffice-it, and every request 404s.
        File directory = new File(source.getParentFile(), "it-war");
        assertTrue(directory.mkdirs() || directory.isDirectory());
        File target = new File(directory, source.getName());

        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source);
        java.util.zip.ZipOutputStream out =
                new java.util.zip.ZipOutputStream(new FileOutputStream(target));
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if ("WEB-INF/web.xml".equals(entry.getName())) {
                    String descriptor = read(zip.getInputStream(entry));
                    descriptor = descriptor.replace("/var/tessera/recon", breaks.getAbsolutePath());
                    descriptor = descriptor.replace("/var/tessera/eod", rejects.getAbsolutePath());
                    out.write(descriptor.getBytes("UTF-8"));
                } else if (!entry.isDirectory()) {
                    copy(zip.getInputStream(entry), out);
                }
                out.closeEntry();
            }
        } finally {
            out.close();
            zip.close();
        }
        return target;
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        try {
            byte[] buffer = new byte[8192];
            int got;
            while ((got = from.read(buffer)) != -1) {
                to.write(buffer, 0, got);
            }
        } finally {
            from.close();
        }
    }

    /** A report shaped exactly as contracts/recon/break-report-v1.md defines it. */
    private static void writeBreakReport(File path) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"formatId\": \"TB-RECON-BREAKS-V1\",\n");
        json.append("  \"businessDate\": \"").append(BUSINESS_DATE).append("\",\n");
        json.append("  \"ledgerPosition\": 4711,\n");
        json.append("  \"ledgerChainHash\": \"");
        for (int i = 0; i < 64; i++) {
            json.append('a');
        }
        json.append("\",\n");
        json.append("  \"cutOff\": { \"movementFile\": \"MOVEMENT.DAT\", \"transferRefCount\": 3 },\n");
        json.append("  \"masterFile\": { \"name\": \"ACCTNEW.DAT\", \"recordCount\": 200 },\n");
        json.append("  \"breaks\": [\n");
        json.append("    { \"accountRef\": \"").append(DRIFT_ACCOUNT).append("\",")
            .append(" \"classification\": \"VALUE_DRIFT\", \"currency\": \"PLN\",")
            .append(" \"masterBookedMinor\": -25000, \"ledgerBookedMinor\": -24000,")
            .append(" \"differenceMinor\": -1000 },\n");
        json.append("    { \"accountRef\": \"").append(TIMING_ACCOUNT).append("\",")
            .append(" \"classification\": \"TIMING\", \"currency\": \"PLN\",")
            .append(" \"masterBookedMinor\": 10000, \"ledgerBookedMinor\": 17000,")
            .append(" \"differenceMinor\": -7000 },\n");
        json.append("    { \"accountRef\": \"").append(ACK_ACCOUNT).append("\",")
            .append(" \"classification\": \"MISSING_ON_MASTER\", \"currency\": \"PLN\",")
            .append(" \"masterBookedMinor\": null, \"ledgerBookedMinor\": 500,")
            .append(" \"differenceMinor\": null }\n");
        json.append("  ],\n");
        json.append("  \"totals\": { \"accountsCompared\": 200, \"accountsMatched\": 197,")
            .append(" \"accountsBroken\": 3, \"totalAbsoluteDriftMinor\": 8000 }\n");
        json.append("}\n");

        OutputStream out = new FileOutputStream(path);
        try {
            out.write(json.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /** One REJREC, built to the copybook, with a COMP-3 amount. */
    private static void writeRejects(File path) throws Exception {
        byte[] record = new byte[200];
        java.util.Arrays.fill(record, (byte) ' ');
        put(record, 0, "TB202608180000000009");
        put(record, 20, "01");
        put(record, 22, "TB00000000000099");
        put(record, 38, "D");
        put(record, 39, "PLN");
        System.arraycopy(comp3(25000L), 0, record, 42, 8);
        put(record, 50, BUSINESS_DATE);
        put(record, 58, BUSINESS_DATE + "030000");
        put(record, 120, "E003");
        put(record, 124, "ACCOUNT NOT ON MASTER");
        put(record, 164, BUSINESS_DATE + "031500");

        OutputStream out = new FileOutputStream(path);
        try {
            out.write(record);
        } finally {
            out.close();
        }
    }

    private static void put(byte[] record, int at, String value) throws Exception {
        byte[] bytes = value.getBytes("US-ASCII");
        System.arraycopy(bytes, 0, record, at, bytes.length);
    }

    private static byte[] comp3(long amountMinor) {
        String digits = String.format("%015d", Long.valueOf(Math.abs(amountMinor)));
        byte[] packed = new byte[8];
        for (int i = 0; i < 7; i++) {
            packed[i] = (byte) (((digits.charAt(i * 2) - '0') << 4) | (digits.charAt(i * 2 + 1) - '0'));
        }
        packed[7] = (byte) (((digits.charAt(14) - '0') << 4) | (amountMinor < 0 ? 0x0D : 0x0C));
        return packed;
    }

    private static File driverJar() throws Exception {
        return new File(Class.forName("oracle.jdbc.OracleDriver")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        assertTrue(property + " is not set; the failsafe configuration supplies it", value != null);
        return value;
    }
}
