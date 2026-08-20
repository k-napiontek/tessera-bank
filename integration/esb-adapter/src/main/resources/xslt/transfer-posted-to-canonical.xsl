<?xml version="1.0" encoding="UTF-8"?>
<!--
  A 2023 Kafka event becomes the estate's canonical XML.

  A FILE, deliberately, and never a StringBuilder in Java. A transformation between two contracts is
  itself a contract artefact: it can be read by somebody who does not write Java, reviewed on its own,
  and diffed when either side changes. Assembling XML in code is how the mapping ends up scattered
  across the class that happens to do the sending.

  XSLT 1.0, which the JDK runs without any dependency. Nothing here needs more: the dates arrive as
  ISO strings the canonical schema accepts unchanged and the amounts are integers, so there is no
  reformatting to do. Saxon and XSLT 3.0 would allow json-to-xml() and remove the intermediate
  document, at the cost of a third-party dependency this transformation does not otherwise need.

  INPUT is the intermediate document produced from the event's JSON by Jackson - element names are
  the JSON field names, and the two movements arrive as repeated <movements> elements.

  OUTPUT is tb:canonicalTransfer, which canonical-v1.xsd declares for exactly this purpose. The
  order of elements is not cosmetic: every canonical type is an xs:sequence, so a document with the
  right elements in the wrong order is invalid.
-->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:tb="http://schemas.tesserabank.example/canonical/v1">

  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/event">
    <tb:canonicalTransfer>
      <tb:transfer>
        <tb:transferRef><xsl:value-of select="transferRef"/></tb:transferRef>
        <tb:debitAccountRef><xsl:value-of select="debitAccountRef"/></tb:debitAccountRef>
        <tb:creditAccountRef><xsl:value-of select="creditAccountRef"/></tb:creditAccountRef>
        <xsl:call-template name="money">
          <xsl:with-param name="element" select="'amount'"/>
          <xsl:with-param name="source" select="amount"/>
        </xsl:call-template>

        <!--
          The event says a transfer was POSTED; that is what the topic means. This is the one value
          derived rather than carried, and it is derived from the fact of the message existing.
        -->
        <tb:status>POSTED</tb:status>

        <xsl:if test="reference != ''">
          <tb:reference><xsl:value-of select="reference"/></tb:reference>
        </xsl:if>

        <!--
          requestedAt is MANDATORY in tb:Transfer and IS NOT IN THE EVENT. The ledger's
          TransferPostedPayload carries postedAt and no request timestamp at all, so this tier cannot
          know when the customer asked.

          postedAt is used, because a transfer is requested no later than it is posted: it is the
          tightest upper bound this component can defend, rather than a value invented to fill a
          mandatory element. It is still not the truth, and it is logged as a follow-up rather than
          left to be discovered by whoever first compares the two timestamps and finds them always
          equal. The real fix is in another package's contract - either the event gains requestedAt
          or the schema stops requiring it - and WP-11's Out of scope forbids changing either from
          here.
        -->
        <tb:requestedAt><xsl:value-of select="postedAt"/></tb:requestedAt>
        <tb:postedAt><xsl:value-of select="postedAt"/></tb:postedAt>

        <xsl:if test="reversesTransferRef != ''">
          <tb:reversesTransferRef><xsl:value-of select="reversesTransferRef"/></tb:reversesTransferRef>
        </xsl:if>

        <tb:correlationId><xsl:value-of select="correlationId"/></tb:correlationId>
      </tb:transfer>

      <xsl:apply-templates select="movements"/>
    </tb:canonicalTransfer>
  </xsl:template>

  <xsl:template match="movements">
    <tb:movement>
      <tb:movementRef><xsl:value-of select="movementRef"/></tb:movementRef>
      <tb:transferRef><xsl:value-of select="transferRef"/></tb:transferRef>
      <tb:legNo><xsl:value-of select="legNo"/></tb:legNo>
      <tb:accountRef><xsl:value-of select="accountRef"/></tb:accountRef>
      <tb:direction><xsl:value-of select="direction"/></tb:direction>
      <xsl:call-template name="money">
        <xsl:with-param name="element" select="'amount'"/>
        <xsl:with-param name="source" select="amount"/>
      </xsl:call-template>
      <tb:valueDate><xsl:value-of select="valueDate"/></tb:valueDate>
      <tb:postedAt><xsl:value-of select="postedAt"/></tb:postedAt>
      <xsl:if test="reference != ''">
        <tb:reference><xsl:value-of select="reference"/></tb:reference>
      </xsl:if>
    </tb:movement>
  </xsl:template>

  <!--
    Money is never split from its currency, in any tier. amountMinor is copied as it arrives: a
    signed count of minor units, never divided, never formatted. The one place in this estate a
    decimal point may appear is a screen.
  -->
  <xsl:template name="money">
    <xsl:param name="element"/>
    <xsl:param name="source"/>
    <xsl:element name="tb:{$element}">
      <tb:amountMinor><xsl:value-of select="$source/amountMinor"/></tb:amountMinor>
      <tb:currency><xsl:value-of select="$source/currency"/></tb:currency>
    </xsl:element>
  </xsl:template>

</xsl:stylesheet>
