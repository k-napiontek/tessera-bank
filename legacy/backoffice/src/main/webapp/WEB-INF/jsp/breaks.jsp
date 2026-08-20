<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%--
  The morning's reconciliation breaks.

  JSTL only - no scriptlets. A 2011 team that still wrote scriptlets was already behind; JSTL had
  been the correct answer since 2004. Every value goes through c:out, which escapes: the reason
  texts and account references come out of files this screen does not control.
--%>
<html>
<head>
  <title>Recon breaks<c:if test="${not empty businessDate}"> - ${businessDate}</c:if></title>
  <link rel="stylesheet" type="text/css" href="<c:url value='/static/backoffice.css'/>"/>
  <script type="text/javascript" src="<c:url value='/static/jquery-1.7.2.min.js'/>"></script>
  <script type="text/javascript" src="<c:url value='/static/breaks.js'/>"></script>
</head>
<body>
<%@ include file="banner.jspf" %>

<div id="content">

  <form method="get" action="<c:url value='/breaks'/>">
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
      <%-- Not the same as "no breaks", and the difference is the whole point of the control. --%>
      <p class="notice">
        <strong>No reconciliation report is present.</strong>
        The morning reconciliation has not run, or this screen is pointed at the wrong directory.
        That is not the same as a clean night, and it should be raised rather than waited out.
      </p>
    </c:when>
    <c:otherwise>

      <fieldset class="totals">
        <legend>Control totals &mdash; <c:out value="${businessDate}"/></legend>
        <span>Compared: <strong>${report.accountsCompared}</strong></span>
        <span>Matched: <strong>${report.accountsMatched}</strong></span>
        <span>Broken: <strong>${report.accountsBroken}</strong></span>
        <span>Needing an operator: <strong>${report.actionableCount}</strong></span>
        <span>Absolute drift: <strong><fmt:formatNumber value="${report.totalAbsoluteDrift}"
              minFractionDigits="2" maxFractionDigits="2"/></strong></span>
        <div class="cut">
          Ledger cut at position <c:out value="${report.ledgerPosition}"/>,
          chain <c:out value="${report.ledgerChainHash}"/>.
          Master <c:out value="${report.masterFileName}"/> (${report.masterRecordCount} records).
          Cut-off <c:out value="${report.movementFile}"/>,
          <strong>${report.transferRefCount}</strong> transfers.
          <em>If that transfer count does not look like a night's work, check which movement file
          was passed before reading anything else.</em>
        </div>
      </fieldset>

      <c:choose>
        <c:when test="${empty report.breaks}">
          <p class="none"><strong>No breaks.</strong> The two cores agree on every account compared.</p>
        </c:when>
        <c:otherwise>

          <p>
            Show:
            <label><input type="checkbox" class="filter" value="VALUE_DRIFT" checked="checked"/> value drift</label>
            <label><input type="checkbox" class="filter" value="MISSING_ON_MASTER" checked="checked"/> missing on master</label>
            <label><input type="checkbox" class="filter" value="MISSING_IN_LEDGER" checked="checked"/> missing in ledger</label>
            <label><input type="checkbox" class="filter" value="TIMING" checked="checked"/> timing (expected)</label>
          </p>

          <table class="grid" id="breaks">
            <tr>
              <th>Account</th>
              <th>Classification</th>
              <th>Ccy</th>
              <th>Master</th>
              <th>Ledger</th>
              <th>Difference</th>
              <th>Action</th>
            </tr>
            <c:forEach var="b" items="${report.breaks}" varStatus="row">
              <tr class="brk ${row.index % 2 == 1 ? 'alt' : ''}" data-classification="${b.classification}">
                <td><c:out value="${b.accountRef}"/></td>
                <td class="cls cls-${b.classification}"
                    title="<c:out value='${b.classification.meaning}'/>">${b.classification}</td>
                <td><c:out value="${b.currency}"/></td>
                <td class="num">
                  <c:choose>
                    <c:when test="${empty b.masterBooked}">&mdash;</c:when>
                    <c:otherwise><fmt:formatNumber value="${b.masterBooked}"
                        minFractionDigits="2" maxFractionDigits="2"/></c:otherwise>
                  </c:choose>
                </td>
                <td class="num">
                  <c:choose>
                    <c:when test="${empty b.ledgerBooked}">&mdash;</c:when>
                    <c:otherwise><fmt:formatNumber value="${b.ledgerBooked}"
                        minFractionDigits="2" maxFractionDigits="2"/></c:otherwise>
                  </c:choose>
                </td>
                <td class="num">
                  <c:choose>
                    <c:when test="${empty b.difference}">&mdash;</c:when>
                    <c:otherwise><fmt:formatNumber value="${b.difference}"
                        minFractionDigits="2" maxFractionDigits="2"/></c:otherwise>
                  </c:choose>
                </td>
                <td>
                  <%-- A TIMING break is expected and offers no action. Inviting an operator to work
                       one undoes what ADR 0015 was for. --%>
                  <c:if test="${not b.actionable}"><em>expected</em></c:if>
                </td>
              </tr>
            </c:forEach>
          </table>

        </c:otherwise>
      </c:choose>
    </c:otherwise>
  </c:choose>

</div>
</body>
</html>
