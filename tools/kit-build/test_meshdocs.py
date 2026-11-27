"""Exercise the offline Meshtastic Markdown/MDX converter through its CLI."""

from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "testdata" / "meshdocs"


class MeshDocsTest(unittest.TestCase):
    def test_page_order_source_metadata_and_body_heading_are_repeatable(self):
        expected = (FIXTURES / "expected.md").read_bytes()
        inputs = sorted(path for path in (FIXTURES / "docs").rglob("*") if path.is_file())

        with TemporaryDirectory() as directory:
            temp = Path(directory)
            outputs = []
            for label, files, newline in (
                ("first", reversed(inputs), b"\n"),
                ("second", inputs, b"\r\n"),
            ):
                source = temp / label
                for path in files:
                    dest = source / path.relative_to(FIXTURES)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    content = path.read_bytes().replace(b"\r\n", b"\n")
                    dest.write_bytes(content.replace(b"\n", newline))
                output = temp / f"{label}.md"
                subprocess.run(
                    [sys.executable, "-I", str(HERE / "meshdocs.py"), str(source), str(output),
                     "8210e9d", "2026-11-16"],
                    cwd=temp, check=True, capture_output=True,
                )
                outputs.append(output.read_bytes())

        self.assertEqual(outputs, [expected, expected])
        self.assertIn(b"commit 8210e9d of 2026-11-16", outputs[0])
        self.assertIn(b"## Android App (docs/software/android/index.md)\n\n"
                      b"### Meshtastic Android & Desktop App\n", outputs[0])
        self.assertNotIn(b"Serial Drivers", outputs[0])


if __name__ == "__main__":
    unittest.main()
