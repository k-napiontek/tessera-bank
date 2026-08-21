package bank.tessera.ledger.loader;

/**
 * What a reader hands a consumer, in the order the stream carries it: the header, then every
 * account, then every action in business-date order.
 */
public interface DatasetVisitor {

    void population(Header header);

    void open(OpenAccount account);

    void action(DrawnAction action);
}
