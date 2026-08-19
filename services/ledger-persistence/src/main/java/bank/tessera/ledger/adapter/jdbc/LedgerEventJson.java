package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.Money;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

/**
 * The wire format of an outbox payload, and the only place that decides it.
 *
 * <p>Not the application's {@code ObjectMapper}. That one serves the REST API and answers to
 * {@code contracts/openapi/ledger-core.yaml}; this one answers to
 * {@code contracts/asyncapi/ledger-events.yaml}. Sharing a mapper between two contracts means a
 * setting changed for one silently changes the other, and the change surfaces as a consumer failing
 * to parse an event days later.
 *
 * <p>Three decisions, all forced by the contract:
 *
 * <ul>
 *   <li><strong>{@link Money} is an object, never a number.</strong> Minor units plus an ISO 4217
 *       code, exactly as the canonical model defines it. Jackson's default treatment of a value
 *       class is not this, and a bare number would lose the currency entirely.
 *   <li><strong>Instants and dates are ISO-8601 strings</strong>, not epoch numbers. The contract
 *       declares {@code format: date-time}, and a consumer written in Go or Python reads a string.
 *   <li><strong>Nulls are omitted.</strong> {@code reversesTransferRef} is present only on a
 *       reversal, and the schema sets {@code additionalProperties: false} with these fields simply
 *       absent - an explicit null would be a value where the contract expects no field at all.
 * </ul>
 */
public final class LedgerEventJson {

    private LedgerEventJson() {}

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

        SimpleModule money = new SimpleModule();
        money.addSerializer(Money.class, new MoneySerializer());
        mapper.registerModule(money);
        return mapper;
    }

    private static final class MoneySerializer extends StdSerializer<Money> {

        private static final long serialVersionUID = 1L;

        private MoneySerializer() {
            super(Money.class);
        }

        @Override
        public void serialize(Money value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeStartObject();
            generator.writeNumberField("amountMinor", value.amountMinor());
            generator.writeStringField("currency", value.currency().code());
            generator.writeEndObject();
        }
    }
}
