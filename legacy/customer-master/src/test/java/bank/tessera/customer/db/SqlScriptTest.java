package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * The script reader, tested without a database.
 *
 * <p>Splitting an Oracle script is not splitting on semicolons. A PL/SQL package body is full of
 * them and is terminated by a lone {@code /}, so a naive splitter tears every procedure into
 * fragments that each fail with a syntax error - and the failure arrives from Oracle, describing the
 * fragment rather than the cause. That is the whole reason this class exists and is tested on its
 * own.
 */
public class SqlScriptTest {

    @Test
    public void splitsPlainStatementsOnSemicolons() {
        List<String> statements = SqlScript.statementsOf(
                "CREATE TABLE a (x NUMBER);\n"
                        + "CREATE TABLE b (y NUMBER);\n");

        assertEquals(2, statements.size());
        assertEquals("CREATE TABLE a (x NUMBER)", statements.get(0));
        assertEquals("CREATE TABLE b (y NUMBER)", statements.get(1));
    }

    @Test
    public void keepsAPlSqlBlockWholeAndStripsItsTerminator() {
        List<String> statements = SqlScript.statementsOf(
                "CREATE OR REPLACE PACKAGE BODY pkg AS\n"
                        + "  PROCEDURE p IS\n"
                        + "  BEGIN\n"
                        + "    UPDATE t SET x = 1;\n"
                        + "    COMMIT;\n"
                        + "  END p;\n"
                        + "END pkg;\n"
                        + "/\n");

        assertEquals(1, statements.size());
        String block = statements.get(0);
        assertTrue("the body must survive whole: " + block, block.contains("UPDATE t SET x = 1;"));
        assertTrue(block.endsWith("END pkg;"));
    }

    @Test
    public void separatesAPlSqlBlockFromTheStatementAfterIt() {
        List<String> statements = SqlScript.statementsOf(
                "CREATE OR REPLACE PROCEDURE p IS\n"
                        + "BEGIN\n"
                        + "  NULL;\n"
                        + "END;\n"
                        + "/\n"
                        + "CREATE TABLE t (x NUMBER);\n");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE OR REPLACE PROCEDURE"));
        assertEquals("CREATE TABLE t (x NUMBER)", statements.get(1));
    }

    /**
     * An anonymous block opens with BEGIN or DECLARE rather than CREATE, and the schema scripts use
     * one to make a migration re-runnable.
     */
    @Test
    public void recognisesAnAnonymousBlock() {
        List<String> statements = SqlScript.statementsOf(
                "BEGIN\n"
                        + "  EXECUTE IMMEDIATE 'DROP TABLE t';\n"
                        + "EXCEPTION WHEN OTHERS THEN NULL;\n"
                        + "END;\n"
                        + "/\n");

        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("EXECUTE IMMEDIATE"));
    }

    @Test
    public void ignoresCommentsAndBlankLines() {
        List<String> statements = SqlScript.statementsOf(
                "-- a comment\n"
                        + "\n"
                        + "CREATE TABLE a (x NUMBER);\n"
                        + "-- another\n");

        assertEquals(1, statements.size());
        assertEquals("CREATE TABLE a (x NUMBER)", statements.get(0));
    }

    /**
     * A script whose last statement is missing its terminator is a truncated file. Executing what
     * arrived would apply half a schema and report success.
     */
    @Test
    public void rejectsAnUnterminatedTrailingStatement() {
        try {
            SqlScript.statementsOf("CREATE TABLE a (x NUMBER)\n");
            org.junit.Assert.fail("expected an unterminated statement to be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unterminated"));
        }
    }
}
