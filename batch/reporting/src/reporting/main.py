"""The entry point: read the configuration, run once, write the metrics, exit.

Exit codes matter more here than in a service, because nothing is watching a batch job at 02:00
except a scheduler reading a number:

* ``0`` - the reports were written.
* ``2`` - the configuration or the command line was wrong. Nothing was read and nothing was written.
* ``1`` - the run started and failed. Some output may exist and none of it should be trusted.

The distinction is the whole reason for two failure codes. A scheduler that retries a 2 will fail
identically every time; a 1 is worth retrying once, because the ledger may simply have been busy.
"""

from __future__ import annotations

import logging
import os
import pathlib
import sys
from collections.abc import Sequence

from reporting.config import ConfigError, load_run, load_settings
from reporting.observability import configure_logging, write_metrics
from reporting.run import execute

LOG = logging.getLogger("reporting")


def main(argv: Sequence[str] | None = None) -> int:
    try:
        settings = load_settings(os.environ)
        request = load_run(sys.argv[1:] if argv is None else argv)
    except ConfigError as error:
        # Before logging is configured, so this goes to stderr as plain text. An operator reading a
        # scheduler's captured output should not have to parse JSON to find out which variable they
        # got wrong.
        print(error, file=sys.stderr)
        return 2

    configure_logging(settings.log_level)
    LOG.info(
        "reporting run starting",
        extra={
            "business_date": request.business_date.isoformat(),
            "requested_position": request.position,
            "output_dir": settings.output_dir,
        },
    )

    try:
        result = execute(settings, request)
    except Exception:
        LOG.exception(
            "reporting run failed",
            extra={"business_date": request.business_date.isoformat()},
        )
        return 1

    write_metrics(pathlib.Path(settings.metrics_path), result)
    LOG.info(
        "reporting run complete",
        extra={
            "business_date": result.business_date_ccyymmdd,
            "position": result.position.seq,
            "chain_hash": result.position.chain_hash,
            "accounts_read": result.accounts_read,
            "movements_read": result.movements_read,
            "duration_seconds": round(result.duration_seconds, 3),
        },
    )
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised as a console script
    sys.exit(main())
