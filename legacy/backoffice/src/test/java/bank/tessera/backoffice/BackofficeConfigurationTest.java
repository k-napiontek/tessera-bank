package bank.tessera.backoffice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BackofficeConfigurationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsBothDirectoriesFromTheDescriptor() throws Exception {
        File breaks = folder.newFolder("recon");
        File rejects = folder.newFolder("eod");

        BackofficeConfiguration configuration = BackofficeConfiguration.from(
                context(breaks.getAbsolutePath(), rejects.getAbsolutePath()));

        assertEquals(breaks, configuration.breaksDir());
        assertEquals(rejects, configuration.rejectsDir());
    }

    @Test
    public void reportsEveryProblemAtOnce() {
        try {
            BackofficeConfiguration.from(context(null, "/no/such/directory"));
            fail("a configuration naming no breaks directory was accepted");
        } catch (BackofficeConfiguration.ConfigurationException expected) {
            assertEquals(
                    "a deployment should be told everything that is wrong, not the first thing",
                    2,
                    expected.problems().size());
        }
    }

    /**
     * The most dangerous screen in this estate is a break list that renders "no breaks" because it
     * was pointed at a directory that does not exist.
     */
    @Test
    public void refusesADirectoryThatIsNotThere() throws Exception {
        try {
            BackofficeConfiguration.from(
                    context(folder.newFolder("recon").getAbsolutePath(), "/no/such/directory"));
            fail("a missing rejects directory was accepted");
        } catch (BackofficeConfiguration.ConfigurationException expected) {
            assertTrue(expected.getMessage().contains("not a directory"));
        }
    }

    @Test
    public void refusesABlankParameterRatherThanTreatingItAsUnset() throws Exception {
        try {
            BackofficeConfiguration.from(context("   ", folder.newFolder("eod").getAbsolutePath()));
            fail("a blank breaks directory was accepted");
        } catch (BackofficeConfiguration.ConfigurationException expected) {
            assertTrue(expected.getMessage().contains("is not set"));
        }
    }

    private static ServletContext context(String breaks, String rejects) {
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put(BackofficeConfiguration.BREAKS_DIR, breaks);
        parameters.put(BackofficeConfiguration.REJECTS_DIR, rejects);
        return StubServletContext.withInitParameters(parameters);
    }
}
