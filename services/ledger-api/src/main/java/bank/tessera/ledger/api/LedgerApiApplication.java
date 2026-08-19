package bank.tessera.ledger.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The ledger's HTTP surface.
 *
 * <p>Reached through {@code edge/api-gateway}, never directly by a customer. This application does
 * not authenticate anyone and holds no customer identity: the gateway owns authentication and rate
 * limiting (WP-12), and the join from an account reference to a person happens in
 * {@code customer-master} (WP-10). The OpenAPI contract still declares what it expects rather than
 * trusting the network to have arranged it.
 */
@SpringBootApplication
public class LedgerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApiApplication.class, args);
    }
}
