package bank.tessera.customer.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applies the versioned schema scripts, in order, from the classpath.
 *
 * <p>Not a migration framework and not trying to be one. It records no history, computes no
 * checksum and cannot go backwards; WP-10 asks for versioned scripts applied by a script, which is
 * what a 2011 team had. What it does do is refuse to guess: the scripts it applies are the ones
 * named in {@code scripts.list}, in the order that file gives, because a directory scan behaves
 * differently inside a WAR than in a build directory and the difference shows up as a migration
 * that silently did not run.
 */
public final class SchemaApplier {

    private static final String MIGRATION_PATH = "db/migration/";
    private static final String INDEX = MIGRATION_PATH + "scripts.list";

    private SchemaApplier() {
    }

    /** The scripts to apply, in application order. */
    public static List<String> scripts() {
        InputStream index = open(INDEX);
        List<String> names = new ArrayList<String>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(index, "UTF-8"));
            try {
                String line = reader.readLine();
                while (line != null) {
                    String trimmed = line.trim();
                    if (trimmed.length() > 0 && !trimmed.startsWith("#")) {
                        names.add(trimmed);
                    }
                    line = reader.readLine();
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + INDEX, e);
        }
        return Collections.unmodifiableList(names);
    }

    /** The statements of one script, parsed but not executed. */
    public static List<String> statementsOf(String scriptName) {
        InputStream script = open(MIGRATION_PATH + scriptName);
        try {
            return SqlScript.statementsOf(script);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + scriptName, e);
        }
    }

    /**
     * Applies every script to the connection. Each statement runs on its own; Oracle commits DDL
     * implicitly, so there is no transaction here to roll back and pretending otherwise would be
     * the more dangerous lie.
     */
    public static void applyTo(Connection connection) throws SQLException {
        List<String> scripts = scripts();
        for (int i = 0; i < scripts.size(); i++) {
            String scriptName = scripts.get(i);
            List<String> statements = statementsOf(scriptName);
            for (int j = 0; j < statements.size(); j++) {
                String sql = statements.get(j);
                Statement statement = connection.createStatement();
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException(
                            scriptName + " failed at statement " + (j + 1) + ": " + firstLine(sql)
                                    + " - " + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                } finally {
                    statement.close();
                }
            }
        }
    }

    private static String firstLine(String sql) {
        int newline = sql.indexOf('\n');
        return newline < 0 ? sql : sql.substring(0, newline);
    }

    private static InputStream open(String resource) {
        InputStream stream = SchemaApplier.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("not on the classpath: " + resource);
        }
        return stream;
    }
}
