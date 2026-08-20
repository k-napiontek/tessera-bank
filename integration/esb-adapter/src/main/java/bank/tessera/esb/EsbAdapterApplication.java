package bank.tessera.esb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The bridge between eras.
 *
 * <p>Consumes a 2023 Kafka event, transforms it to canonical XML by XSLT, and calls a 2011 SOAP
 * service. WP-11b adds the last hop: a fixed-width record in COMP-3 packed decimal for a COBOL
 * batch program that has been running since 1995.
 *
 * <p>Java 8 with Spring Boot 2.7 - and unlike stratum 1, lambdas and constructor injection are the
 * idiom here rather than an anachronism. The eras are supposed to read differently.
 */
@SpringBootApplication
public class EsbAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsbAdapterApplication.class, args);
    }
}
