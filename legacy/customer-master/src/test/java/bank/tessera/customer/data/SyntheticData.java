package bank.tessera.customer.data;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * Generates the customers and accounts this tier is tested against.
 *
 * <p>The rule this repository works to is that no personal data exists anywhere, and customer-master
 * is the component that appears to contradict it: it holds a name, a date of birth and a national
 * identifier because the GDPR data map has to describe something real. The contradiction is
 * resolved here rather than by promising care. Every value is CONSTRUCTED - a name carries its
 * ordinal, so no bare surname can occur however the pool grows, and an identifier is prefixed
 * {@code SYN-}, a shape no authority issues. Neither is a value that happens not to match anybody;
 * both are values that cannot.
 *
 * <p>Deterministic from a seed, like {@code mainframe/data/generate.py}, so a failing test can be
 * re-run against the same data.
 */
public final class SyntheticData {

    /**
     * Deliberately invented bases. They are combined with an ordinal before use, so what lands in
     * the database is never one of these on its own.
     */
    private static final String[] FAMILY_BASES = {
        "TESSERA", "MUSIVUM", "LAPIDIS", "CALCULUS", "NUMMUS", "AERARIUM", "FISCUS", "DENARIUS"
    };

    private static final String[] GIVEN_BASES = {
        "PRIMA", "SECUNDA", "TERTIA", "QUARTA", "QUINTA", "SEXTA", "SEPTIMA", "OCTAVA"
    };

    /**
     * Scale-2 currencies only. Stratum 0 stores money as PIC S9(13)V99 COMP-3, which hard-codes two
     * decimals - an account in JPY or BHD would be a balance the mainframe misstates by a factor of
     * a hundred. The constraint is stratum 0's and the account master is where it has to be honoured.
     */
    private static final String[] CURRENCIES = {"PLN", "PLN", "PLN", "EUR", "USD", "GBP", "CHF"};

    private static final String[] STATUSES = {
        "OPEN", "OPEN", "OPEN", "OPEN", "OPEN", "OPEN", "BLOCKED", "CLOSED"
    };

    private SyntheticData() {
    }

    public static List<SyntheticCustomer> customers(int count, long seed) {
        Random random = new Random(seed);
        List<SyntheticCustomer> customers = new ArrayList<SyntheticCustomer>();
        for (int i = 1; i <= count; i++) {
            String ordinal = pad(i, 4);
            customers.add(new SyntheticCustomer(
                    "CU" + pad(i, 10),
                    FAMILY_BASES[random.nextInt(FAMILY_BASES.length)] + "-" + ordinal,
                    GIVEN_BASES[random.nextInt(GIVEN_BASES.length)] + "-" + ordinal,
                    dateOfBirth(random),
                    "SYN-" + pad(random.nextInt(100000000), 8),
                    onboardedDate(random)));
        }
        return customers;
    }

    /**
     * One or two accounts per customer, so GetAccountsByCustomer has both cases to answer, and one
     * customer with none at all - because an empty list is an answer and not a fault, and a fixture
     * in which every customer has an account never exercises that.
     */
    public static List<SyntheticAccount> accountsFor(List<SyntheticCustomer> customers, long seed) {
        Random random = new Random(seed);
        List<SyntheticAccount> accounts = new ArrayList<SyntheticAccount>();
        int sequence = 0;
        for (int i = 0; i < customers.size(); i++) {
            SyntheticCustomer customer = customers.get(i);
            int howMany = i == 0 ? 0 : 1 + random.nextInt(2);
            for (int n = 0; n < howMany; n++) {
                sequence++;
                Date opened = openedDate(random, customer.onboardedDate());
                boolean hasMoved = random.nextInt(10) > 1;
                accounts.add(new SyntheticAccount(
                        "TB" + pad(sequence, 14),
                        customer.customerRef(),
                        "LIABILITY",
                        CURRENCIES[random.nextInt(CURRENCIES.length)],
                        STATUSES[random.nextInt(STATUSES.length)],
                        balanceMinor(random),
                        opened,
                        hasMoved ? movementDate(random, opened) : null));
            }
        }
        return accounts;
    }

    /** Writes the generated rows into an already-migrated schema. */
    public static void seed(Connection connection, List<SyntheticCustomer> customers,
            List<SyntheticAccount> accounts) throws SQLException {
        PreparedStatement insertCustomer = connection.prepareStatement(
                "INSERT INTO customer (customer_ref, family_name, given_name, date_of_birth,"
                        + " national_id, onboarded_date) VALUES (?, ?, ?, ?, ?, ?)");
        try {
            for (int i = 0; i < customers.size(); i++) {
                SyntheticCustomer customer = customers.get(i);
                insertCustomer.setString(1, customer.customerRef());
                insertCustomer.setString(2, customer.familyName());
                insertCustomer.setString(3, customer.givenName());
                insertCustomer.setDate(4, customer.dateOfBirth());
                insertCustomer.setString(5, customer.nationalId());
                insertCustomer.setDate(6, customer.onboardedDate());
                insertCustomer.executeUpdate();
            }
        } finally {
            insertCustomer.close();
        }

        PreparedStatement insertAccount = connection.prepareStatement(
                "INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                        + " booked_balance, opened_date, last_movement_date)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        try {
            for (int i = 0; i < accounts.size(); i++) {
                SyntheticAccount account = accounts.get(i);
                insertAccount.setString(1, account.accountRef());
                insertAccount.setString(2, account.customerRef());
                insertAccount.setString(3, account.accountType());
                insertAccount.setString(4, account.currency());
                insertAccount.setString(5, account.status());
                insertAccount.setLong(6, account.bookedBalanceMinor());
                insertAccount.setDate(7, account.openedDate());
                insertAccount.setDate(8, account.lastMovementDate());
                insertAccount.executeUpdate();
            }
        } finally {
            insertAccount.close();
        }
    }

    private static long balanceMinor(Random random) {
        long magnitude = (long) random.nextInt(500000000);
        return random.nextInt(10) == 0 ? -magnitude : magnitude;
    }

    private static Date dateOfBirth(Random random) {
        return dateOf(1950 + random.nextInt(50), random.nextInt(12), 1 + random.nextInt(28));
    }

    private static Date onboardedDate(Random random) {
        return dateOf(2005 + random.nextInt(15), random.nextInt(12), 1 + random.nextInt(28));
    }

    private static Date openedDate(Random random, Date notBefore) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(notBefore);
        calendar.add(Calendar.DAY_OF_MONTH, random.nextInt(400));
        return new Date(calendar.getTimeInMillis());
    }

    private static Date movementDate(Random random, Date openedDate) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(openedDate);
        calendar.add(Calendar.DAY_OF_MONTH, random.nextInt(2000));
        return new Date(calendar.getTimeInMillis());
    }

    private static Date dateOf(int year, int monthIndex, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, monthIndex, day);
        return new Date(calendar.getTimeInMillis());
    }

    private static String pad(int value, int width) {
        StringBuilder text = new StringBuilder(Integer.toString(value));
        while (text.length() < width) {
            text.insert(0, '0');
        }
        return text.toString();
    }
}
