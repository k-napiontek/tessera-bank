package bank.tessera.esb;

import java.io.File;
import java.net.MalformedURLException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The wiring. Constructor injection throughout, which is the 2019 idiom and not the 2011 one -
 * stratum 1 gets its DataSource from JNDI, and the difference is supposed to be visible.
 *
 * <p>Nothing here holds an address or a path as a constant. The contracts directory, the endpoint
 * and the topics are all configuration, because a deployment decides them - see ADR 0001.
 */
@Configuration
public class EsbConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CanonicalTransformer canonicalTransformer(
            @Value("${tessera.contracts.dir}") String contractsDir) {
        return new CanonicalTransformer(
                new File(new File(contractsDir, "xsd"), "canonical-v1.xsd"));
    }

    @Bean
    public CustomerMasterClient customerMasterClient(
            @Value("${tessera.contracts.dir}") String contractsDir,
            @Value("${tessera.esb.customer-master-endpoint}") String endpoint)
            throws MalformedURLException {
        // The WSDL is read from the contracts directory in place, because it imports the canonical
        // schema by a relative path. Reading it from the running endpoint's ?wsdl instead would
        // make this component's idea of the interface depend on the thing it is calling.
        File wsdl = new File(new File(contractsDir, "wsdl"), "customer-master-v1.wsdl");
        return new CustomerMasterClient(wsdl.toURI().toURL(), endpoint);
    }

    @Bean
    public TransferHandler transferHandler(CanonicalTransformer transformer,
            CustomerMasterClient customerMaster) {
        return new TransferBridge(transformer, customerMaster);
    }

    @Bean
    public DeadLetterRecorder deadLetterRecorder(KafkaTemplate<String, String> kafka,
            @Value("${tessera.esb.dead-letter-topic}") String deadLetterTopic, Clock clock) {
        return new DeadLetterRecorder(kafka, deadLetterTopic, clock);
    }

    @Bean
    public TransferPostedListener transferPostedListener(TransferHandler handler,
            DeadLetterRecorder deadLetters) {
        return new TransferPostedListener(handler, deadLetters);
    }
}
