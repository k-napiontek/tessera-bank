package bank.tessera.backoffice;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletContext;

/**
 * Where this WAR's two input directories are, read from the deployment descriptor.
 *
 * <p>No path appears anywhere in this module's code. A 2011 application declared what it needed in
 * {@code web.xml} and the operations team bound it to the environment in the container's
 * configuration, which is why one artefact deploys to test and to production unchanged. The same
 * reasoning as {@code customer-master}'s {@code resource-ref} for its database.
 *
 * <p><strong>Every problem is reported at once.</strong> This screen is deployed by somebody who is
 * not watching a log, and a loader that stopped at the first missing parameter would cost one
 * deployment per parameter. The same rule {@code batch/reporting}'s configuration follows and for
 * the same reason.
 *
 * <p><strong>A directory that is absent is a configuration error, not an empty screen.</strong> An
 * operations team that mistypes a path should be told at deployment; a break list that renders
 * "no breaks" because it is pointed at nothing is the most dangerous screen in this estate.
 */
public final class BackofficeConfiguration {

    /** The directory {@code batch/recon} writes {@code BREAKS-CCYYMMDD.json} into. */
    public static final String BREAKS_DIR = "tessera.breaks.dir";

    /** The directory the overnight cycle leaves {@code REJECTS.DAT} in, one per business date. */
    public static final String REJECTS_DIR = "tessera.rejects.dir";

    private final File breaksDir;
    private final File rejectsDir;

    private BackofficeConfiguration(File breaksDir, File rejectsDir) {
        this.breaksDir = breaksDir;
        this.rejectsDir = rejectsDir;
    }

    /** Read and validate both parameters, reporting everything wrong in one message. */
    public static BackofficeConfiguration from(ServletContext context) {
        List<String> problems = new ArrayList<String>();
        File breaks = directory(context.getInitParameter(BREAKS_DIR), BREAKS_DIR, problems);
        File rejects = directory(context.getInitParameter(REJECTS_DIR), REJECTS_DIR, problems);
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        return new BackofficeConfiguration(breaks, rejects);
    }

    private static File directory(String value, String name, List<String> problems) {
        if (value == null || value.trim().length() == 0) {
            problems.add(name + " is not set in web.xml");
            return null;
        }
        File candidate = new File(value.trim());
        if (!candidate.isDirectory()) {
            problems.add(name + " is " + value.trim() + ", which is not a directory");
            return null;
        }
        return candidate;
    }

    public File breaksDir() {
        return breaksDir;
    }

    public File rejectsDir() {
        return rejectsDir;
    }

    /** Everything wrong with the configuration, in one message. */
    public static final class ConfigurationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final List<String> problems;

        ConfigurationException(List<String> problems) {
            super(message(problems));
            this.problems = problems;
        }

        public List<String> problems() {
            return problems;
        }

        private static String message(List<String> problems) {
            StringBuilder text = new StringBuilder("the backoffice configuration is not usable:");
            for (int i = 0; i < problems.size(); i++) {
                text.append("\n  - ").append(problems.get(i));
            }
            return text.toString();
        }
    }
}
