#!/usr/bin/env python3
"""Tests for the COMP-3 encoder.

The expected byte strings below are transcribed from the worked examples in
docs/architecture/canonical-data-model.md, as literals. They are deliberately not recomputed from the
same arithmetic the encoder uses - a test that derives its expectation from the implementation proves
only that the implementation agrees with itself.

These four byte strings are the agreement WP-11's Java encoder will be held to, byte for byte.

Run: python3 mainframe/data/test_comp3.py
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from comp3 import decode_comp3, encode_comp3  # noqa: E402


class WorkedExamplesFromTheCanonicalModel(unittest.TestCase):
    """Section 2 of the canonical data model, verbatim."""

    def test_positive_amount(self):
        # 1 234 567.89 PLN -> amountMinor 123456789
        self.assertEqual(encode_comp3(123456789), bytes.fromhex("0000001234567 89C".replace(" ", "")))

    def test_negative_amount(self):
        # -1 234 567.89 PLN. Only the sign nibble differs from the positive case.
        self.assertEqual(encode_comp3(-123456789), bytes.fromhex("000000123456789D"))

    def test_zero_is_always_positive(self):
        # A negative zero would break byte-for-byte comparison in WP-11 and produce a phantom
        # reconciliation break in WP-16.
        self.assertEqual(encode_comp3(0), bytes.fromhex("000000000000000C"))
        self.assertEqual(encode_comp3(-0), bytes.fromhex("000000000000000C"))

    def test_maximum_representable(self):
        # 9 999 999 999 999.99
        self.assertEqual(encode_comp3(999999999999999), bytes.fromhex("999999999999999C"))


class Encoding(unittest.TestCase):

    def test_always_eight_bytes(self):
        for value in (0, 1, -1, 999999999999999, -999999999999999, 5000):
            self.assertEqual(len(encode_comp3(value)), 8, f"for {value}")

    def test_sign_nibble_is_c_or_d_and_nothing_else(self):
        self.assertEqual(encode_comp3(1)[-1] & 0x0F, 0x0C)
        self.assertEqual(encode_comp3(-1)[-1] & 0x0F, 0x0D)

    def test_rejects_a_value_that_does_not_fit(self):
        with self.assertRaises(ValueError):
            encode_comp3(1000000000000000)
        with self.assertRaises(ValueError):
            encode_comp3(-1000000000000000)

    def test_round_trips(self):
        for value in (0, 1, -1, 42, -42, 123456789, -123456789, 999999999999999):
            self.assertEqual(decode_comp3(encode_comp3(value)), value, f"for {value}")

    def test_decodes_an_unsigned_f_nibble_as_positive(self):
        # 0x0F appears in data written by fields declared without S. Accepted on read, never written.
        self.assertEqual(decode_comp3(bytes.fromhex("000000000000123F")), 123)

    def test_rejects_a_wrong_length(self):
        with self.assertRaises(ValueError):
            decode_comp3(bytes.fromhex("0000123C"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
