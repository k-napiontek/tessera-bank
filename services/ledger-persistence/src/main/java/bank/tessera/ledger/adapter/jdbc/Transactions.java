package bank.tessera.ledger.adapter.jdbc;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction template over a plain {@link DataSource}.
 *
 * <p>There is no Spring Boot application in this module - WP-08 brings one - so nothing is
 * autoconfiguring a transaction manager. The adapters still need real transactions: appending an entry
 * writes postings and moves balances, and a half-applied entry is exactly the corruption double-entry
 * bookkeeping exists to prevent.
 *
 * <p>WP-08 can pass a container-managed {@link TransactionTemplate} instead. The adapters take one as a
 * constructor argument for that reason rather than building their own.
 */
public final class Transactions {

    private Transactions() {}

    public static TransactionTemplate of(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
