# esb-adapter

**Stratum 2** | **Java 8, Spring Boot 2.7.18** | **Built by WP-11**

Connects 2023 to 1995. Consumes a Kafka transfer event, transforms it to canonical XML by XSLT, calls the 2011 SOAP service, and writes a fixed-width movement record in COMP-3 packed decimal for tonight's COBOL batch run.

The most interesting engineering in the repository, because every one of those steps is a real integration problem that banks genuinely solve this way.

