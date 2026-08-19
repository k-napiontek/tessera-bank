package bank.tessera.customer.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The script index, checked against the scripts that actually exist.
 *
 * <p>A migration that is present on disk and absent from the index applies in nobody's environment
 * and fails in nobody's build. It is discovered later, by the environment that needed it.
 */
public class SchemaApplierTest {

    private static final File MIGRATION_DIR = new File("src/main/resources/db/migration");

    @Test
    public void everyScriptOnDiskIsRegisteredInTheIndex() {
        List<String> onDisk = new ArrayList<String>();
        File[] files = MIGRATION_DIR.listFiles();
        assertTrue("migration directory not found at " + MIGRATION_DIR.getAbsolutePath(),
                files != null);
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            if (name.endsWith(".sql")) {
                onDisk.add(name);
            }
        }
        java.util.Collections.sort(onDisk);

        List<String> registered = new ArrayList<String>(SchemaApplier.scripts());
        java.util.Collections.sort(registered);

        assertEquals("scripts.list has drifted from db/migration", onDisk, registered);
    }

    @Test
    public void theIndexIsOrderedByVersion() {
        List<String> registered = SchemaApplier.scripts();
        List<String> sorted = new ArrayList<String>(registered);
        java.util.Collections.sort(sorted, new java.util.Comparator<String>() {
            public int compare(String left, String right) {
                return versionOf(left) - versionOf(right);
            }
        });
        assertEquals("scripts must be listed in the order they apply", sorted, registered);
    }

    @Test
    public void everyRegisteredScriptCanBeReadAndParsed() throws Exception {
        List<String> scripts = SchemaApplier.scripts();
        assertTrue("no scripts registered at all", scripts.size() > 0);
        for (int i = 0; i < scripts.size(); i++) {
            List<String> statements = SchemaApplier.statementsOf(scripts.get(i));
            assertTrue(scripts.get(i) + " parsed to no statements", statements.size() > 0);
        }
    }

    private static int versionOf(String scriptName) {
        String digits = scriptName.substring(1, scriptName.indexOf("__"));
        return Integer.parseInt(digits);
    }

    @Test
    public void scriptNamesFollowTheVersionConvention() {
        List<String> scripts = SchemaApplier.scripts();
        for (int i = 0; i < scripts.size(); i++) {
            assertTrue(scripts.get(i) + " does not match V<n>__<name>.sql",
                    scripts.get(i).matches("^V[0-9]+__[a-z0-9_]+\\.sql$"));
        }
    }
}
