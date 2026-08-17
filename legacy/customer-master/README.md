# customer-master

**Stratum 1** | **Java 8, WAR on Tomcat 8.5** | **Built by WP-10**

System of record for customers and account metadata, exposed over SOAP. The only component in the estate that holds personal data - deliberately, so that everything else is out of scope for GDPR erasure. See the [GDPR data map](../../docs/compliance/gdpr-data-map.md).

Implements [`contracts/wsdl/`](../../contracts/wsdl/). Built with Maven 3 under JDK 8, packaged as a WAR.

