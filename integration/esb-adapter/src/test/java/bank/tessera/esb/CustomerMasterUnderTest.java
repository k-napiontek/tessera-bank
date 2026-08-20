package bank.tessera.esb;

import java.io.File;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.codehaus.cargo.container.ContainerType;
import org.codehaus.cargo.container.InstalledLocalContainer;
import org.codehaus.cargo.container.configuration.ConfigurationType;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.deployable.Deployable;
import org.codehaus.cargo.container.deployable.DeployableType;
import org.codehaus.cargo.container.installer.ZipURLInstaller;
import org.codehaus.cargo.container.property.DatasourcePropertySet;
import org.codehaus.cargo.container.property.GeneralPropertySet;
import org.codehaus.cargo.container.property.LoggingLevel;
import org.codehaus.cargo.container.property.ServletPropertySet;
import org.codehaus.cargo.generic.DefaultContainerFactory;
import org.codehaus.cargo.generic.configuration.DefaultConfigurationFactory;
import org.codehaus.cargo.generic.deployable.DefaultDeployableFactory;
import org.testcontainers.oracle.OracleContainer;

/**
 * The far end of the SOAP hop, brought up for real: Oracle 23ai Free, Tomcat 8.5, and
 * customer-master's own WAR.
 *
 * <p>Deliberately not a stub. Two independently generated clients meet at this hop - stratum 1
 * generated its server interface from {@code customer-master-v1.wsdl} and this module generated a
 * client from the same document - and nothing guarantees they agree until a real call is made. The
 * precedent is WP-14's live walkthrough, which found three defects that a passing hermetic suite of
 * 120 tests had missed.
 *
 * <p><strong>Two duplications live here, and both are deliberate.</strong> Stratum 1 has an
 * equivalent Tomcat helper and an equivalent script applier in its own test tree. Sharing them would
 * mean either publishing stratum 1's test classes as an artefact or making stratum 2 depend on
 * stratum 1's build - and WP-11's Out of scope is explicit that this package adapts what it sits
 * between without modifying it. Copied test scaffolding is the cheaper mistake. Logged as a
 * follow-up rather than left to be discovered.
 */
final class CustomerMasterUnderTest {

    private static final String TOMCAT_URL =
            "https://archive.apache.org/dist/tomcat/tomcat-8/v8.5.100/bin/apache-tomcat-8.5.100.zip";

    private static final String CONTAINER_ID = "tomcat8x";

    /** Stratum 1's own source of truth for its schema. Read, never copied. */
    private static final File MIGRATIONS = new File(
            "../../legacy/customer-master/src/main/resources/db/migration");

    private static final File WAR =
            new File("../../legacy/customer-master/target/customer-master.war");

    private final OracleContainer oracle;
    private final InstalledLocalContainer tomcat;
    private final int port;

    private CustomerMasterUnderTest(OracleContainer oracle, InstalledLocalContainer tomcat,
            int port) {
        this.oracle = oracle;
        this.tomcat = tomcat;
        this.port = port;
    }

    static CustomerMasterUnderTest start(File workDirectory) throws Exception {
        if (!WAR.isFile()) {
            throw new IllegalStateException(
                    "customer-master has not been packaged. This test deploys the real WAR rather"
                            + " than a stub, so build it first:\n    make build-legacy\n"
                            + "expected at " + WAR.getAbsolutePath());
        }

        OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withUsername("tessera")
                .withPassword("tessera")
                .withReuse(false);
        oracle.start();
        applySchema(oracle);

        int port = aFreePort();
        LocalConfiguration configuration = (LocalConfiguration)
                new DefaultConfigurationFactory().createConfiguration(
                        CONTAINER_ID, ContainerType.INSTALLED, ConfigurationType.STANDALONE,
                        new File(workDirectory, "configuration").getAbsolutePath());
        configuration.setProperty(ServletPropertySet.PORT, String.valueOf(port));
        configuration.setProperty(GeneralPropertySet.LOGGING, LoggingLevel.LOW.getLevel());
        configuration.setProperty(DatasourcePropertySet.DATASOURCE,
                "cargo.datasource.driver=oracle.jdbc.OracleDriver|"
                        + "cargo.datasource.url=" + oracle.getJdbcUrl() + "|"
                        + "cargo.datasource.jndi=jdbc/customerMaster|"
                        + "cargo.datasource.username=" + oracle.getUsername() + "|"
                        + "cargo.datasource.password=" + oracle.getPassword() + "|"
                        + "cargo.datasource.type=java.sql.Driver");

        Deployable war = new DefaultDeployableFactory()
                .createDeployable(CONTAINER_ID, WAR.getAbsolutePath(), DeployableType.WAR);
        configuration.addDeployable(war);

        InstalledLocalContainer tomcat = (InstalledLocalContainer)
                new DefaultContainerFactory().createContainer(
                        CONTAINER_ID, ContainerType.INSTALLED, configuration);

        ZipURLInstaller installer = new ZipURLInstaller(new URL(TOMCAT_URL),
                new File(workDirectory, "download").getAbsolutePath(),
                new File(workDirectory, "install").getAbsolutePath());
        installer.install();
        tomcat.setHome(installer.getHome());
        // ojdbc8 is not in the WAR - the container binds the DataSource, so the container needs the
        // driver. This is where an operations team would have dropped the jar into CATALINA_HOME/lib.
        tomcat.setExtraClasspath(new String[] {driverJar().getAbsolutePath()});
        tomcat.setTimeout(300000L);
        tomcat.start();

        return new CustomerMasterUnderTest(oracle, tomcat, port);
    }

    String endpoint() {
        return "http://localhost:" + port + "/customer-master/services/CustomerMasterService";
    }

    Connection connection() throws SQLException {
        return DriverManager.getConnection(
                oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword());
    }

    void stop() {
        if (tomcat != null) {
            tomcat.stop();
        }
        if (oracle != null) {
            oracle.stop();
        }
    }

    /**
     * Applies stratum 1's own versioned scripts, read from its source tree in the order its
     * scripts.list gives. The scripts are the contract; this only executes them.
     */
    private static void applySchema(OracleContainer oracle) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword())) {
            for (String script : scriptNames()) {
                for (String statement : statementsOf(new File(MIGRATIONS, script))) {
                    try (Statement handle = connection.createStatement()) {
                        handle.execute(statement);
                    }
                }
            }
        }
    }

    private static List<String> scriptNames() throws Exception {
        List<String> names = new ArrayList<>();
        for (String line : Files.readAllLines(new File(MIGRATIONS, "scripts.list").toPath(),
                StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * Oracle's own statement separators, which JDBC does not understand. A line containing only
     * {@code /} ends a PL/SQL block, and everything a block contains - including its semicolons -
     * belongs to that one statement. Splitting a package body on {@code ;} produces fragments that
     * each fail, and the first error names a line nobody wrote.
     */
    private static List<String> statementsOf(File script) throws Exception {
        String text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();

        for (String chunk : text.split("(?m)^\\s*/\\s*$")) {
            String block = stripComments(chunk).trim();
            if (block.isEmpty()) {
                continue;
            }
            if (opensAPlSqlBlock(block.split("\n", 2)[0].trim())) {
                statements.add(block);
            } else {
                for (String plain : block.split(";")) {
                    if (!plain.trim().isEmpty()) {
                        statements.add(plain.trim());
                    }
                }
            }
        }
        return statements;
    }

    /**
     * A statement whose semicolons belong to it rather than terminate it. Deliberately the same
     * five openers, spelled the same way, as {@code SqlScript.opensAPlSqlBlock} at stratum 1 - the
     * authority this helper was copied from. The copy knew only about packages, so WP-15's audit
     * migration, which adds a trigger, split it on its own semicolons and Oracle answered
     * {@code ORA-00900} naming a fragment nobody wrote. F-61 predicted exactly this.
     */
    private static boolean opensAPlSqlBlock(String firstLine) {
        String upper = firstLine.toUpperCase();
        if (upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) {
            return true;
        }
        if (!upper.startsWith("CREATE")) {
            return false;
        }
        return upper.contains(" PACKAGE ")
                || upper.contains(" PROCEDURE ")
                || upper.contains(" FUNCTION ")
                || upper.contains(" TRIGGER ")
                || upper.contains(" TYPE ");
    }

    /** Only whole-line comments. A -- inside a PL/SQL body is that body's business. */
    private static String stripComments(String chunk) {
        StringBuilder kept = new StringBuilder();
        for (String line : chunk.split("\n", -1)) {
            if (!line.trim().startsWith("--")) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    private static File driverJar() throws Exception {
        return new File(Class.forName("oracle.jdbc.OracleDriver")
                .getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static int aFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
