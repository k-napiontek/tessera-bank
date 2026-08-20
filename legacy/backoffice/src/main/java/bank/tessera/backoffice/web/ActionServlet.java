package bank.tessera.backoffice.web;

import bank.tessera.backoffice.dao.OperatorDao;
import bank.tessera.backoffice.dao.OperatorException;
import java.io.IOException;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

/**
 * The operator's two actions: acknowledge a break, annotate a reject.
 *
 * <p><strong>POST, then redirect.</strong> An action that answered with a page would be repeated by
 * every refresh, and an audit trail full of accidental duplicates is one nobody can read. The
 * redirect also means the browser's back button lands on a list rather than on a resubmission.
 *
 * <p><strong>The acting user comes from the container, never from the request.</strong>
 * {@code getRemoteUser()} is what the security constraint established; a hidden field naming the
 * operator would be a field anybody could edit, and an audit trail that records who the browser
 * said it was is not attributable at all.
 */
public class ActionServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final String DATA_SOURCE = "java:comp/env/jdbc/customerMaster";

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String actor = request.getRemoteUser();
        if (actor == null) {
            // Only reachable if the security constraint is removed. Refusing here means the trail
            // cannot acquire an anonymous row even then.
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "an operator action must be attributable");
            return;
        }

        String action = request.getParameter("action");
        String businessDate = request.getParameter("businessDate");
        if (!BusinessDates.isBusinessDate(businessDate)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "not a business date");
            return;
        }

        OperatorDao dao = new OperatorDao(dataSource());
        try {
            if ("acknowledge".equals(action)) {
                dao.acknowledgeBreak(businessDate, request.getParameter("accountRef"),
                        request.getParameter("classification"), actor,
                        request.getParameter("note"));
                redirect(request, response, "/breaks", businessDate, null);
            } else if ("annotate".equals(action)) {
                dao.annotateReject(businessDate, request.getParameter("transferRef"),
                        legNo(request.getParameter("legNo")), actor, request.getParameter("note"));
                redirect(request, response, "/rejects", businessDate, null);
            } else {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "unknown action");
            }
        } catch (OperatorException refused) {
            // A refusal is the database enforcing a rule and is shown to the operator. A failure is
            // not theirs to fix, and is not dressed up as though it were.
            if (refused.isRefusal()) {
                redirect(request, response,
                        "acknowledge".equals(action) ? "/breaks" : "/rejects",
                        businessDate, refused.getMessage());
            } else {
                throw new ServletException(refused);
            }
        }
    }

    private static int legNo(String value) throws ServletException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw new ServletException("not a leg number: " + value, notANumber);
        }
    }

    private void redirect(HttpServletRequest request, HttpServletResponse response, String path,
            String businessDate, String refusal) throws IOException {
        StringBuilder target = new StringBuilder(request.getContextPath())
                .append(path)
                .append("?businessDate=")
                .append(businessDate);
        if (refusal != null) {
            target.append("&refused=")
                    .append(java.net.URLEncoder.encode(refusal, "UTF-8"));
        }
        response.sendRedirect(target.toString());
    }

    /** Looked up per request rather than cached: the container owns the pool, not this servlet. */
    private DataSource dataSource() throws ServletException {
        try {
            return (DataSource) new InitialContext().lookup(DATA_SOURCE);
        } catch (NamingException notBound) {
            throw new ServletException(DATA_SOURCE + " is not bound; see web.xml's resource-ref",
                    notBound);
        }
    }
}
