import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


KIT_BUILD = Path(__file__).resolve().parent
FIXTURES = KIT_BUILD / "testdata" / "ecfr2md"


class EcfrMarkdownTest(unittest.TestCase):
    def assert_golden_and_rerun(self, name, *subparts):
        fixture = FIXTURES / f"{name}.xml"
        # Git can check text files out with CRLF; the converter always writes LF.
        golden = fixture.with_suffix(".md").read_bytes().replace(b"\r\n", b"\n")
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out.md"
            command = [
                sys.executable, "-I", str(KIT_BUILD / "ecfr2md.py"),
                str(fixture), str(output), "eCFR table excerpt", "Source fixture", *subparts,
            ]
            first = self.run_converter(command, output)
            self.assertEqual(first, golden)
            output.unlink()
            self.assertEqual(self.run_converter(command, output), first)

    def run_converter(self, command, output):
        result = subprocess.run(command, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace"))
        return output.read_bytes()

    def test_gpotable_keeps_caption_headers_rows_and_note(self):
        self.assert_golden_and_rerun("gpotable", "A")

    def test_html_table_keeps_header_and_pads_short_row(self):
        self.assert_golden_and_rerun("html-table")


if __name__ == "__main__":
    unittest.main()
