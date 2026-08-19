package bank.tessera.customer.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an Oracle SQL script and splits it into the statements JDBC will accept.
 *
 * <p>Two rules, and the second is the one that matters. A plain statement ends at a semicolon, which
 * is stripped, because the Oracle driver refuses a trailing semicolon on DDL. A PL/SQL block ends at
 * a line holding nothing but a solidus, and every semicolon inside it belongs to the block - a
 * package body split on semicolons becomes a dozen fragments, each of which fails with a syntax
 * error describing the fragment rather than the mistake.
 *
 * <p>Deliberately not a migration framework. WP-10 asks for versioned scripts applied by script, and
 * a 2011 team had exactly this class in it somewhere.
 */
public final class SqlScript {

    private SqlScript() {
    }

    /** Reads a script from the classpath, in UTF-8. */
    public static List<String> statementsOf(InputStream script) throws IOException {
        StringBuilder text = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(script, "UTF-8"));
        try {
            String line = reader.readLine();
            while (line != null) {
                text.append(line).append('\n');
                line = reader.readLine();
            }
        } finally {
            reader.close();
        }
        return statementsOf(text.toString());
    }

    public static List<String> statementsOf(String script) {
        List<String> statements = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inPlSqlBlock = false;

        String[] lines = script.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (current.length() == 0 && (trimmed.isEmpty() || trimmed.startsWith("--"))) {
                continue;
            }

            if (current.length() == 0) {
                inPlSqlBlock = opensAPlSqlBlock(trimmed);
            }

            if (inPlSqlBlock) {
                if (trimmed.equals("/")) {
                    statements.add(current.toString().trim());
                    current.setLength(0);
                    inPlSqlBlock = false;
                } else {
                    current.append(line).append('\n');
                }
                continue;
            }

            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String statement = current.toString().trim();
                statements.add(statement.substring(0, statement.length() - 1).trim());
                current.setLength(0);
            }
        }

        if (current.toString().trim().length() > 0) {
            throw new IllegalArgumentException(
                    "unterminated statement at the end of the script - the file is truncated, and"
                            + " applying what arrived would leave a half-built schema reporting"
                            + " success: " + current.toString().trim());
        }
        return statements;
    }

    /**
     * A statement whose semicolons belong to it rather than terminate it. {@code CREATE TABLE} is
     * not one; {@code CREATE PACKAGE BODY}, a standalone procedure, a trigger, a type body and an
     * anonymous block all are.
     */
    private static boolean opensAPlSqlBlock(String firstLine) {
        String upper = firstLine.toUpperCase();
        if (upper.startsWith("BEGIN") || upper.startsWith("DECLARE")) {
            return true;
        }
        if (!upper.startsWith("CREATE")) {
            return false;
        }
        return upper.contains(" PACKAGE ")
                || upper.contains(" PROCEDURE ")
                || upper.contains(" FUNCTION ")
                || upper.contains(" TRIGGER ")
                || upper.contains(" TYPE ");
    }
}
