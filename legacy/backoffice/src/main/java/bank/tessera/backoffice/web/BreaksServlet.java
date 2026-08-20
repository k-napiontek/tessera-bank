package bank.tessera.backoffice.web;

import bank.tessera.backoffice.BackofficeConfiguration;
import bank.tessera.backoffice.dao.OperatorDao;
import bank.tessera.backoffice.dao.OperatorException;
import bank.tessera.backoffice.recon.BreakReport;
import bank.tessera.backoffice.recon.BreakReportReader;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

/**
 * The break list: what the morning's reconciliation found.
 *
 * <p>Server-rendered, as the work package's Constraints require. The JSP receives objects and loops
 * over them; jQuery filters what is already on the page. Nothing here returns JSON to the browser.
 *
 * <p><strong>A directory with no report for the date is not an empty list.</strong> The page says
 * the reconciliation has not run, because "no breaks" and "no reconciliation" are the two states an
 * operator must never confuse - and the second is the one that means nobody is checking.
 */
public class BreaksServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static final String VIEW = "/WEB-INF/jsp/breaks.jsp";

    private static final String DATA_SOURCE = "java:comp/env/jdbc/customerMaster";

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        BackofficeConfiguration configuration = BackofficeConfiguration.from(getServletContext());
        File directory = configuration.breaksDir();

        List<String> available = BusinessDates.available(directory);
        String businessDate = BusinessDates.resolve(directory, request.getParameter("businessDate"));

        request.setAttribute("availableDates", available);
        request.setAttribute("businessDate", businessDate);

        if (businessDate != null) {
            BreakReport report = BreakReportReader.readFor(directory, businessDate);
            request.setAttribute("report", report);
            request.setAttribute("acknowledged", acknowledgements(businessDate));
        }
        request.getRequestDispatcher(VIEW).forward(request, response);
    }

    /**
     * Which breaks already carry an acknowledgement, so the screen does not offer to make one
     * twice.
     *
     * <p>Courtesy rather than a control: {@code PKG_OPERATOR.acknowledge_break} is idempotent, so
     * the rule holds for every caller and not only for the one that reads this map. Read in a single
     * query - a screen that asked the database once per break would be two hundred round trips on
     * exactly the morning nobody has time for them.
     */
    private Map<String, String> acknowledgements(String businessDate) throws ServletException {
        try {
            DataSource dataSource = (DataSource) new InitialContext().lookup(DATA_SOURCE);
            return new OperatorDao(dataSource).acknowledgementsFor(businessDate);
        } catch (NamingException notBound) {
            throw new ServletException(DATA_SOURCE + " is not bound; see web.xml", notBound);
        } catch (OperatorException problem) {
            throw new ServletException(problem);
        }
    }
}
