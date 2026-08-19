package bank.tessera.ledger.api.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Liveness and readiness must answer differently, or neither is worth having.
 *
 * <p>A platform acts on the two probes in opposite ways: a failing liveness probe restarts the
 * process, a failing readiness probe takes it out of the load balancer. Spring's default readiness
 * group is `readinessState` alone - it reports ready as soon as the context has started - so a
 * ledger that cannot reach PostgreSQL is declared ready and is sent traffic it can only reject. This
 * test is what makes adding `db` to the readiness group a checked decision rather than a line in a
 * YAML file nobody has ever exercised.
 *
 * <p>Equally, the database must stay <em>out</em> of liveness. Restarting a pod does not fix a
 * database outage; it turns one outage into a crash-loop across every instance at once, and the
 * recovery then has to contend with a stampede of reconnecting pods.
 *
 * <p>This class owns its container and kills it, so it cannot share the module's. The Spring context
 * is separate too, because the datasource properties differ.
 */
@SpringBootTest(
        properties = {
            "tessera.outbox.relay-enabled=false",
            // Without this the health check waits the default thirty seconds for a connection that
            // is never coming, and the test looks hung rather than red.
            "spring.datasource.hikari.connection-timeout=1000",
            "spring.datasource.hikari.initialization-fail-timeout=-1"
        })
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthProbeTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tessera_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    static {
        POSTGRES.start();
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @Order(1)
    @DisplayName("a healthy service is both alive and ready")
    void bothProbesAreUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(2)
    @DisplayName("with the database gone the service is still alive but no longer ready")
    void readinessFallsAndLivenessDoesNot() throws Exception {
        // Stopped rather than paused: a stopped container refuses the connection immediately, while
        // a paused one leaves the socket hanging until a timeout expires. The failure being tested
        // is "the database is unreachable", and the fast version of it makes for a faster test.
        POSTGRES.stop();

        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // 503, not 200 with a body saying DOWN. A load balancer reads the status code.
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
