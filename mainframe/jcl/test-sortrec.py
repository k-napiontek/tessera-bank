#!/usr/bin/env python3
"""Assert the DFSORT stand-in orders fixed-length records without touching their bytes.

Run: python3 mainframe/jcl/test-sortrec.py
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "mainframe" / "data"))

from comp3 import encode_comp3  # noqa: E402

_spec = importlib.util.spec_from_file_location("sortrec", REPO / "mainframe" / "jcl" / "sortrec.py")
sortrec = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sortrec)


class SortRecTest(unittest.TestCase):
    def run_sort(self, records, record_length, keys):
        work = pathlib.Path(tempfile.mkdtemp())
        source, target = work / "IN.DAT", work / "OUT.DAT"
        source.write_bytes(b"".join(records))
        argv = ["--record-length", str(record_length)]
        for key in keys:
            argv += [f"--key={key}"]
        code = sortrec.main(argv + [str(source), str(target)])
        return code, (target.read_bytes() if target.exists() else b"")

    def test_orders_records_on_a_single_key(self):
        records = [b"CCCC0001", b"AAAA0002", b"BBBB0003"]
        code, out = self.run_sort(records, 8, ["0:4"])

        self.assertEqual(code, 0)
        self.assertEqual(out, b"AAAA0002BBBB0003CCCC0001")

    def test_applies_keys_in_the_order_given(self):
        """Currency then reference: the master's report sequence."""
        records = [b"USD" + b"TB01", b"EUR" + b"TB02", b"USD" + b"TB00", b"EUR" + b"TB01"]
        code, out = self.run_sort(records, 7, ["0:3", "3:7"])

        self.assertEqual(code, 0)
        self.assertEqual(out, b"EURTB01EURTB02USDTB00USDTB01")

    def test_a_secondary_key_never_outranks_the_primary(self):
        """The primary key decides; the secondary only breaks its ties.

        USD/AA01 against EUR/ZZ99: on currency the EUR record wins, even though its reference is
        the higher of the two. An implementation that compares the fields as an unordered set puts
        AA01 first and produces a report broken into the wrong sections.
        """
        records = [b"USDAA01", b"EURZZ99"]
        code, out = self.run_sort(records, 7, ["0:3", "3:7"])

        self.assertEqual(code, 0)
        self.assertEqual(out, b"EURZZ99USDAA01")

    def test_equal_keys_keep_their_arrival_order(self):
        """Stability is what makes two runs of the cycle produce identical bytes."""
        records = [b"AAAA0003", b"AAAA0001", b"AAAA0002"]
        code, out = self.run_sort(records, 8, ["0:4"])

        self.assertEqual(code, 0)
        self.assertEqual(out, b"AAAA0003AAAA0001AAAA0002")

    def test_packed_decimal_bytes_survive_the_sort(self):
        """A COMP-3 amount can contain 0x0D, and that is why `sort` cannot be used on these files.

        Not 0x0A, though. Every nibble of a packed field is a digit except the last, which is the
        sign - C, D or F - so the byte 0x0A cannot occur. 0x0D can, and does: a negative amount
        whose final digit is zero packs to a trailing 0x0D, which is a carriage return.
        """
        carriage_return_amount = encode_comp3(-1000)
        self.assertIn(0x0D, carriage_return_amount)
        self.assertNotIn(0x0A, carriage_return_amount)

        records = [b"BBBB" + carriage_return_amount, b"AAAA" + carriage_return_amount]
        code, out = self.run_sort(records, 12, ["0:4"])

        self.assertEqual(code, 0)
        self.assertEqual(out, b"AAAA" + carriage_return_amount + b"BBBB" + carriage_return_amount)

    def test_a_ragged_file_is_an_error_not_a_truncated_read(self):
        """A partial record means the previous step failed. Dropping it hides that downstream."""
        work = pathlib.Path(tempfile.mkdtemp())
        source, target = work / "IN.DAT", work / "OUT.DAT"
        source.write_bytes(b"AAAA0001" + b"BBB")

        code = sortrec.main(["--record-length", "8", "--key", "0:4", str(source), str(target)])

        self.assertEqual(code, 12)
        self.assertFalse(target.exists(), "a ragged input still produced an output file")

    def test_a_key_past_the_record_is_refused(self):
        code, _ = self.run_sort([b"AAAA0001"], 8, ["4:20"])
        self.assertEqual(code, 12)

    def test_a_malformed_key_is_refused(self):
        for key in ["4", "10:4", "-1:4", "a:b"]:
            with self.subTest(key=key):
                code, _ = self.run_sort([b"AAAA0001"], 8, [key])
                self.assertEqual(code, 12, f"key {key!r} was accepted")

    def test_a_missing_input_file_is_refused(self):
        work = pathlib.Path(tempfile.mkdtemp())
        code = sortrec.main(["--record-length", "8", "--key", "0:4",
                             str(work / "ABSENT.DAT"), str(work / "OUT.DAT")])
        self.assertEqual(code, 12)


if __name__ == "__main__":
    unittest.main(verbosity=2)
