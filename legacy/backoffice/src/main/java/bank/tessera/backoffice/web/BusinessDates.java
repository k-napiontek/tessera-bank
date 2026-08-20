package bank.tessera.backoffice.web;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which business dates have a break report, newest first.
 *
 * <p>The screen defaults to the most recent rather than to "today". An operator arrives at 07:00 to
 * work last night's reconciliation, and a page that defaulted to today's date would be empty every
 * morning until the job ran - which is indistinguishable, on screen, from a clean night.
 */
public final class BusinessDates {

    private static final Pattern REPORT = Pattern.compile("^BREAKS-(\\d{8})\\.json$");

    private BusinessDates() {
    }

    /** Every business date with a report in the directory, descending. */
    public static List<String> available(File directory) {
        String[] names = directory.list(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return REPORT.matcher(name).matches();
            }
        });
        List<String> dates = new ArrayList<String>();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                dates.add(names[i].substring("BREAKS-".length(), "BREAKS-".length() + 8));
            }
        }
        Collections.sort(dates, Collections.reverseOrder());
        return dates;
    }

    /** The requested date if it is one, otherwise the newest available, otherwise null. */
    public static String resolve(File directory, String requested) {
        List<String> dates = available(directory);
        if (requested != null && isBusinessDate(requested) && dates.contains(requested)) {
            return requested;
        }
        return dates.isEmpty() ? null : dates.get(0);
    }

    /** CCYYMMDD, the form the JCL runner takes. One estate, one way of writing a business date. */
    public static boolean isBusinessDate(String value) {
        return value != null && value.matches("\\d{8}");
    }
}
