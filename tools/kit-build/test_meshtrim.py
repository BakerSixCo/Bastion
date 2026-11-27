"""Exercise the offline Meshtastic guide trim through its CLI."""

from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "testdata" / "meshtrim"


class MeshTrimTest(unittest.TestCase):
    def test_keeps_the_page_heading_when_the_site_name_differs(self):
        combined = (FIXTURES / "combined.md").read_text(encoding="utf-8")
        expected = (FIXTURES / "expected.md").read_bytes()

        with TemporaryDirectory() as directory:
            temp = Path(directory)
            outputs = []
            for label, newline in (("first", "\n"), ("second", "\r\n")):
                source = temp / f"{label}-combined.md"
                source.write_text(combined, encoding="utf-8", newline=newline)
                output = temp / f"{label}-trimmed.md"
                subprocess.run(
                    [sys.executable, "-I", str(HERE / "meshtrim.py"), str(source), str(output)],
                    cwd=temp, check=True, capture_output=True,
                )
                outputs.append(output.read_bytes())

        self.assertEqual(outputs, [expected, expected])
        self.assertIn(b"## Android App (docs/software/android/index.md)\n\n"
                      b"### Meshtastic Android & Desktop App\n", outputs[0])
        self.assertIn(b"Pages included (owner keep-or-kill 2026-11-26)", outputs[0])
        self.assertNotIn(b"## Firmware Flashing", outputs[0])
        self.assertNotIn(b"## Android Developer Guide", outputs[0])


if __name__ == "__main__":
    unittest.main()
