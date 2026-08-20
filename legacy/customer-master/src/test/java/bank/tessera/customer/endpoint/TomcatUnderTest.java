package bank.tessera.customer.endpoint;

import java.io.File;
import java.net.ServerSocket;
import java.net.URL;
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

/**
 * A real Tomcat 8.5, fetched at test time and thrown away afterwards.
 *
 * <p>Tomcat 8.5 went out of community support in March 2024 and 8.5.100 is the last release there
 * will ever be, which is precisely why this stratum targets it - see TD-003. The archive is
 * downloaded into target/ on the first run and nothing container-shaped is committed, so ADR 0001
 * still holds: this repository is application source, and the runtime is something a test fetches
 * rather than something the repository ships.
 *
 * <p>The point of doing this at all is that "mvn package succeeded" is not the same statement as
 * "the WAR deploys". A servlet listener that cannot be loaded, a JNDI name that is not bound, a
 * jaxws-rt whose API disagrees with the one in rt.jar - none of those fail a build, and all of them
 * fail a deployment.
 */
final class TomcatUnderTest {

    /**
     * The final release of the 8.5 line, from the Apache archive rather than the mirrors: an
     * end-of-life version is not on a mirror, which is a thing worth knowing before an incident.
     */
    private static final String TOMCAT_URL =
            "https://archive.apache.org/dist/tomcat/tomcat-8/v8.5.100/bin/apache-tomcat-8.5.100.zip";

    private static final String CONTAINER_ID = "tomcat8x";

    private final InstalledLocalContainer container;
    private final int port;

    private TomcatUnderTest(InstalledLocalContainer container, int port) {
        this.container = container;
        this.port = port;
    }

    static TomcatUnderTest deploy(File war, String jdbcUrl, String username, String password,
            String jndiName, File driverJar, File workDirectory) throws Exception {
        int port = aFreePort();

        LocalConfiguration configuration = (LocalConfiguration)
                new DefaultConfigurationFactory().createConfiguration(
                        CONTAINER_ID, ContainerType.INSTALLED, ConfigurationType.STANDALONE,
                        new File(workDirectory, "configuration").getAbsolutePath());
        configuration.setProperty(ServletPropertySet.PORT, String.valueOf(port));
        configuration.setProperty(GeneralPropertySet.LOGGING, LoggingLevel.LOW.getLevel());

        // The container binds the DataSource, exactly as the operations team would have in an
        // environment's own configuration. The WAR carries no connection string, which is what lets
        // one artefact deploy to test and to production unchanged.
        configuration.setProperty(DatasourcePropertySet.DATASOURCE,
                "cargo.datasource.driver=oracle.jdbc.OracleDriver|"
                        + "cargo.datasource.url=" + jdbcUrl + "|"
                        + "cargo.datasource.jndi=" + jndiName + "|"
                        + "cargo.datasource.username=" + username + "|"
                        + "cargo.datasource.password=" + password + "|"
                        // java.sql.Driver, not javax.sql.DataSource. The type names how the
                        // container obtains a connection, not what it binds into JNDI: it wraps a
                        // plain JDBC driver in a pool and binds that as a DataSource. Naming the
                        // bound interface here is refused outright.
                        + "cargo.datasource.type=java.sql.Driver");

        Deployable deployable = new DefaultDeployableFactory().createDeployable(
                CONTAINER_ID, war.getAbsolutePath(), DeployableType.WAR);
        configuration.addDeployable(deployable);

        InstalledLocalContainer container = (InstalledLocalContainer)
                new DefaultContainerFactory().createContainer(
                        CONTAINER_ID, ContainerType.INSTALLED, configuration);

        ZipURLInstaller installer = new ZipURLInstaller(new URL(TOMCAT_URL),
                new File(workDirectory, "download").getAbsolutePath(),
                new File(workDirectory, "install").getAbsolutePath());
        installer.install();
        container.setHome(installer.getHome());

        // ojdbc8 is a provided dependency, so it is deliberately NOT in WEB-INF/lib - a container
        // that binds the DataSource is the container that needs the driver. This is where the
        // operations team would have dropped the jar into $CATALINA_HOME/lib.
        container.setExtraClasspath(new String[] {driverJar.getAbsolutePath()});
        container.setTimeout(300000L);

        container.start();
        return new TomcatUnderTest(container, port);
    }

    URL endpoint() throws Exception {
        return new URL(baseUrl());
    }

    URL publishedWsdl() throws Exception {
        return new URL(baseUrl() + "?wsdl");
    }

    URL publishedSchema(int number) throws Exception {
        return new URL(baseUrl() + "?xsd=" + number);
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/customer-master/services/CustomerMasterService";
    }

    void stop() {
        if (container != null) {
            container.stop();
        }
    }

    /**
     * A port the operating system says is free, rather than 8080. A fixed port turns "somebody
     * already has Tomcat running" into a test failure that names something else entirely.
     */
    private static int aFreePort() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }
}
