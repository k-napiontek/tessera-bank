"""One synthetic transfer event, and the knobs the tests turn on it.

Every value here is generated and belongs to nobody: account references are the estate's synthetic
format, and the remittance reference is a marker string. There is no personal data in this
repository, in fixtures least of all.
"""

from __future__ import annotations

from typing import Any


def transfer_payload(**overrides: Any) -> dict[str, Any]:
    """A ``TransferPostedPayload`` as the ledger's outbox publishes it."""
    payload: dict[str, Any] = {
        "transferRef": "TB202608190000000001",
        "debitAccountRef": "TB00000000000C03",
        "creditAccountRef": "TB00000000000A01",
        "amount": {"amountMinor": 250_000, "currency": "PLN"},
        "reference": "SYNTHETIC-REF",
        "postedAt": "2026-08-19T13:19:21.451Z",
        "correlationId": "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
        "movements": [
            {
                "movementRef": "TB202608190000000001-01",
                "transferRef": "TB202608190000000001",
                "legNo": 1,
                "accountRef": "TB00000000000C03",
                "direction": "DEBIT",
                "amount": {"amountMinor": 250_000, "currency": "PLN"},
                "valueDate": "2026-08-19",
                "postedAt": "2026-08-19T13:19:21.451Z",
                "reference": "SYNTHETIC-REF",
            },
            {
                "movementRef": "TB202608190000000001-02",
                "transferRef": "TB202608190000000001",
                "legNo": 2,
                "accountRef": "TB00000000000A01",
                "direction": "CREDIT",
                "amount": {"amountMinor": 250_000, "currency": "PLN"},
                "valueDate": "2026-08-19",
                "postedAt": "2026-08-19T13:19:21.451Z",
                "reference": "SYNTHETIC-REF",
            },
        ],
    }
    payload.update(overrides)
    return payload
