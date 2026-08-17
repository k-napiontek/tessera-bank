# Job control

**Stratum 0** | **Built by WP-05**

`EODCYCLE.JCL` defines the nightly job graph in authentic JCL: SORT, then ACCTPOST, then EODREPT, with DD statements. GnuCOBOL cannot execute JCL, so `run-eod.sh` reproduces the same graph locally.

The JCL is kept because it is the real-world artefact a reader should recognise; the shell script is the executable equivalent. The two must always describe the same step graph.

