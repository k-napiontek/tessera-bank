package bank.tessera.ledger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The application, running against real PostgreSQL.
 *
 * <p>One container and one Spring context for every test that extends this, because starting either
 * costs far more than the tests do. The consequence is that the database is shared, so each test
 * must work on account references of its own - {@link #freshAccountReference()} exists for that, and
 * a test that hard-codes a reference will pass alone and fail beside its neighbours.
 *
 * <p>Flyway runs on startup, so the schema under test is the one the migrations produce rather than
 * one a test helper built to match.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(SpringExtension.class)
public abstract class LedgerApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tessera_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

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
