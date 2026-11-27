import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / 'testdata' / 'bodyhash'
SCRIPT = HERE / 'bodyhash.py'


class BodyHashTest(unittest.TestCase):
    def run_hash(self, document, *options):
        return subprocess.run(
            [sys.executable, '-I', *options, str(SCRIPT), str(document)],
            check=True, capture_output=True,
        ).stdout

    def test_whitespace_normalisation_and_repeat_run(self):
        original = FIXTURE / 'copy.md'
        expected = (FIXTURE / 'expected.txt').read_bytes()
        self.assertEqual(expected, self.run_hash(original))
        self.assertEqual(expected, self.run_hash(original))
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / 'reflowed.md'
            reflowed = original.read_text(encoding='utf-8').replace('basis, meaning', 'basis,\n    meaning')
            changed.write_bytes(reflowed.replace('\n', '\r\n').encode('utf-8'))
            self.assertEqual(expected, self.run_hash(changed))

    def test_quote_encoding_is_stable_and_quote_glyphs_are_preserved(self):
        windows_stdout = (
            'import runpy, sys; '
            'sys.stdout.reconfigure(encoding="cp1252", newline="\\r\\n"); '
            'sys.argv = sys.argv[1:]; runpy.run_path(sys.argv[0], run_name="__main__")'
        )
        actual = self.run_hash(FIXTURE / 'copy.md', '-c', windows_stdout)
        self.assertEqual((FIXTURE / 'expected.txt').read_bytes(), actual)
        straight, curled = actual.decode('utf-8').splitlines()
        self.assertIn('"take-turns"', straight)
        self.assertIn('“take-turns”', curled)
        self.assertNotEqual(straight.split()[0], curled.split()[0])


if __name__ == '__main__':
    unittest.main()
