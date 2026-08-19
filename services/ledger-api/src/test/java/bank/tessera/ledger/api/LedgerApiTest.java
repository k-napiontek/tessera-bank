package bank.tessera.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The application, running against real PostgreSQL.
 *
 * <p>One container and one Spring context for every test that extends this, because starting either
 * costs far more than the tests do. The consequence is that the database is shared, so each test
 * must work on account references of its own - {@link #freshAccountReference()} exists for that, and
 * a test that hard-codes a reference will pass alone and fail beside its neighbours.
 *
 * <p><strong>Started in a static initialiser, not by {@code @Testcontainers}.</strong> That JUnit
 * extension stops a {@code @Container} when the class that declares it finishes, and Spring caches
 * its context - and the connection pool inside it - across every test class in the module. The
 * second class then holds a pool pointing at a container that no longer exists, and fails with
 * "Failed to obtain JDBC Connection" for a reason that has nothing to do with what it was testing.
 * Ryuk removes the container when the JVM exits, which is the same arrangement
 * {@code PostgresSupport} uses in {@code ledger-persistence}.
 */
// The outbox relay is off here. These tests are about HTTP and the ledger, there is no broker for
// the relay to reach, and a scheduled task retrying against one would add ten-second waits to every
// class. KafkaOutboxContractTest turns it on against a real broker.
@SpringBootTest(properties = "tessera.outbox.relay-enabled=false")
@AutoConfigureMockMvc
public abstract class LedgerApiTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tessera_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    static {
        POSTGRES.start();
    }

    private static int nextAccount = 1;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    /** An account reference no other test has used. */
    protected static synchronized String freshAccountReference() {
        return String.format("TB%014d", nextAccount++);
    }

    /** An idempotency key of its own. The contract requires at least sixteen characters. */
    protected static String freshIdempotencyKey() {
        return "key-" + java.util.UUID.randomUUID();
    }
}
