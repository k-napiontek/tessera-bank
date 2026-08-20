package bank.tessera.backoffice.web;

import bank.tessera.backoffice.BackofficeConfiguration;
import bank.tessera.backoffice.mainframe.Reject;
import bank.tessera.backoffice.mainframe.RejectFile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The rejects queue: every movement last night's cycle could not apply.
 *
 * <p>The file lives under the cycle's work directory, one per business date - the same layout
 * {@code run-eod.sh} writes and the EOD runbook describes. A date with no file is a night the cycle
 * did not run for, which the page says rather than showing an empty queue.
 *
 * <p><strong>A reject with no rejects file is not zero rejects.</strong> The same distinction the
 * break list makes, and for the same reason: the two states an operator must never confuse are
 * "nothing went wrong" and "nothing checked".
 */
public class RejectsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String VIEW = "/WEB-INF/jsp/rejects.jsp";

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        BackofficeConfiguration configuration = BackofficeConfiguration.from(getServletContext());
        File root = configuration.rejectsDir();

        List<String> available = availableDates(root);
        String requested = request.getParameter("businessDate");
        String businessDate = null;
        if (requested != null && BusinessDates.isBusinessDate(requested)
                && available.contains(requested)) {
            businessDate = requested;
        } else if (!available.isEmpty()) {
            businessDate = available.get(0);
        }

        request.setAttribute("availableDates", available);
        request.setAttribute("businessDate", businessDate);

        if (businessDate != null) {
            File file = new File(new File(root, businessDate), "REJECTS.DAT");
            if (file.isFile()) {
                List<Reject> rejects = RejectFile.read(file);
                request.setAttribute("rejects", rejects);
                request.setAttribute("rejectsFile", file.getName());
            }
        }
        request.getRequestDispatcher(VIEW).forward(request, response);
    }

    /**
     * Business dates the cycle has a work directory for, newest first.
     *
     * <p>Only well-formed names are considered, and the name is never used to build a path until it
     * has been. The parameter arrives from a query string, so it is whatever anybody typed.
     */
    private static List<String> availableDates(File root) {
        String[] names = root.list();
        List<String> dates = new ArrayList<String>();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (BusinessDates.isBusinessDate(names[i])
                        && new File(root, names[i]).isDirectory()) {
                    dates.add(names[i]);
                }
            }
        }
        Collections.sort(dates, Collections.reverseOrder());
        return dates;
    }
}
