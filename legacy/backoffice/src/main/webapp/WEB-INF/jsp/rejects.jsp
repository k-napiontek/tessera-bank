<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%--
  The overnight cycle's rejects. Both the reason code and the reason text are shown: a code an
  operator has to look up is a code they will guess at, and the text alone cannot be filtered on.

  The movement is decoded - account, direction, amount - because the alternative is asking an
  operator to read packed decimal by eye.
--%>
<html>
<head>
  <title>Rejects<c:if test="${not empty businessDate}"> - ${businessDate}</c:if></title>
  <link rel="stylesheet" type="text/css" href="<c:url value='/static/backoffice.css'/>"/>
  <script type="text/javascript" src="<c:url value='/static/jquery-1.7.2.min.js'/>"></script>
</head>
<body>
<%@ include file="banner.jspf" %>

<div id="content">

  <form method="get" action="<c:url value='/rejects'/>">
    <label for="businessDate">Business date</label>
    <select id="businessDate" name="businessDate" onchange="this.form.submit()">
      <c:forEach var="date" items="${availableDates}">
        <option value="${date}" <c:if test="${date eq businessDate}">selected="selected"</c:if>>${date}</option>
      </c:forEach>
    </select>
    <noscript><input type="submit" value="Show"/></noscript>
  </form>

  <c:choose>
    <c:when test="${empty businessDate}">
      <p class="notice">
        <strong>No overnight cycle output is present.</strong>
        The cycle has not run, or this screen is pointed at the wrong directory. That is not the
        same as a night with no rejects.
      </p>
    </c:when>
    <c:when test="${empty rejectsFile}">
      <p class="notice">
        <strong>The cycle ran for <c:out value="${businessDate}"/> and left no rejects file.</strong>
        Check the cycle's own output before treating this as a clean night.
      </p>
    </c:when>
    <c:when test="${empty rejects}">
      <p class="none">
        <strong>No rejects.</strong> Every movement in <c:out value="${businessDate}"/>'s file was
        applied to the account master.
      </p>
    </c:when>
    <c:otherwise>

      <p><strong>${fn:length(rejects)}</strong> rejected movements in
         <c:out value="${rejectsFile}"/>.</p>

      <table class="grid" id="rejects">
        <tr>
          <th>Transfer</th>
          <th>Leg</th>
          <th>Account</th>
          <th>D/C</th>
          <th>Ccy</th>
          <th>Amount</th>
          <th>Value date</th>
          <th>Code</th>
          <th>Reason</th>
        </tr>
        <c:forEach var="r" items="${rejects}" varStatus="row">
          <tr class="${row.index % 2 == 1 ? 'alt' : ''}">
            <td><c:out value="${r.transferRef}"/></td>
            <td><c:out value="${r.legNo}"/></td>
            <td><c:out value="${r.accountRef}"/></td>
            <td><c:out value="${r.direction}"/></td>
            <td><c:out value="${r.currency}"/></td>
            <td class="num"><fmt:formatNumber value="${r.amount}"
                minFractionDigits="2" maxFractionDigits="2"/></td>
            <td><c:out value="${r.valueDate}"/></td>
            <td><strong><c:out value="${r.reasonCode}"/></strong></td>
            <td><c:out value="${r.reasonText}"/></td>
          </tr>
        </c:forEach>
      </table>

    </c:otherwise>
  </c:choose>

</div>
</body>
</html>
