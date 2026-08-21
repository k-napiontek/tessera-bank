package bank.tessera.ledger.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.MediaType;

/**
 * Every objective the catalogue states about this ledger is stated over a metric a scrape carries.
 *
 * <p>This is the half of the contract that {@code contracts/check-slo-catalogue.py} deliberately
 * cannot check. That checker matches each {@code meterName} against the component's source, which
 * proves the meter is registered - but the name an operator has to type is Micrometer's *rendering*
 * of it: dots become underscores, a counter gains {@code _total}, a base unit is appended, and a
 * timer becomes three series. Restating those rules in the checker would be a second copy of
 * somebody else's convention, and a wrong copy would agree with itself.
 *
 * <p>So the exposed name is asserted here, against a real scrape of the real endpoint, driven from
 * the committed catalogue rather than from a list transcribed beside it. A catalogue entry naming a
 * series this ledger does not expose fails, in either direction, whether the mistake was made in the
 * contract or in the code.
 *
 * <p>It also covers the meters that no source match could reach: Boot's Hikari binder registers
 * {@code hikaricp.connections.*} and nothing in this repository names them, so the catalogue records
 * them as framework-emitted and this is the only thing standing behind that claim.
 */
@AutoConfigureObservability
class CatalogueScrapeTest extends LedgerApiTest {

    private static final String COMPONENT = "ledger-api";

    /** Micrometer renders a timer as three series and a histogram as four. All are the same meter. */
    private static final List<String> SUFFIXES = List.of("", "_count", "_sum", "_max", "_bucket");

    private static List<String> catalogued() throws Exception {
        Path contracts = Path.of(System.getProperty(
                "tessera.contracts.dir", Path.of("..", "..", "contracts").toString()));
        JsonNode document = new ObjectMapper()
                .readTree(Files.readAllBytes(contracts.resolve("slo").resolve("tessera-slo-v1.json")));

        List<String> names = new ArrayList<>();
        for (JsonNode component : document.get("components")) {
            if (!COMPONENT.equals(component.get("componentId").asText())) {
                continue;
            }
            for (JsonNode objective : component.get("objectives")) {
                names.add(objective.get("sli").get("exposedName").asText());
            }
            for (JsonNode signal : component.get("signals")) {
                names.add(signal.get("exposedName").asText());
            }
        }
        return names;
    }

    private String open(String reference, String type) throws Exception {
        mvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountRef": "%s",
                                  "customerRef": "CU0000000001",
                                  "accountType": "%s",
                                  "currency": "PLN"
                                }
                                """.formatted(reference, type)))
                .andExpect(status().isCreated());
        return reference;
    }

    @Test
    @DisplayName("every metric the catalogue states an objective over is in a real scrape")
    void theCatalogueDescribesThisLedger() throws Exception {
        // Micrometer registers a counter or a timer the first time it is used, so a ledger that has
        // moved no money exposes neither. Moving some is what makes this assertion about the
        // catalogue rather than about how far through startup the test happened to run.
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "debitAccountRef": "%s",
                                  "creditAccountRef": "%s",
                                  "amount": { "amountMinor": 1000, "currency": "PLN" }
                                }
                                """.formatted(vault, alice)))
                .andExpect(status().isCreated());

        List<String> samples = Arrays.stream(
                        mvc.perform(get("/actuator/prometheus"))
                                .andReturn().getResponse().getContentAsString().split("\n"))
                .filter(line -> !line.startsWith("#"))
                .toList();

        List<String> catalogued = catalogued();
        assertThat(catalogued).as("the catalogue has an entry for this component").isNotEmpty();

        for (String exposed : catalogued) {
            assertThat(samples)
                    .as("the catalogue names %s and the scrape carries it", exposed)
                    .anyMatch(line -> SUFFIXES.stream()
                            .anyMatch(suffix -> line.startsWith(exposed + suffix + "{")));
        }
    }
}
