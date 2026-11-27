import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / 'testdata' / 'ncvec2md'
SCRIPT = HERE / 'ncvec2md.py'


class NcvecGoldenTest(unittest.TestCase):
    def convert(self, source, target):
        result = subprocess.run(
            [sys.executable, '-I', str(SCRIPT), str(source), str(target)],
            check=True, capture_output=True,
        )
        self.assertEqual(b'3 questions; 0 problems', result.stdout.strip())
        return target.read_bytes()

    def test_bracket_reference_with_or_without_space_and_no_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            output = self.convert(FIXTURE / 'pool.txt', Path(directory) / 'answers.md')
        self.assertEqual((FIXTURE / 'expected.md').read_bytes(), output)
        self.assertIn(b'**T1A01** [97.1] Which', output)
        self.assertIn(b'**T1A02** [97.1] Which', output)
        self.assertIn(b'**T5A01** Electrical', output)
        self.assertNotIn(b'Fixture distractor', output)

    def test_repeated_cli_runs_and_crlf_source_match_the_lf_golden(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            source = FIXTURE / 'pool.txt'
            alternate = folder / 'windows.txt'
            target = folder / 'answers.md'
            alternate.write_bytes(source.read_bytes().replace(b'\n', b'\r\n'))
            expected = (FIXTURE / 'expected.md').read_bytes()
            self.assertEqual(expected, self.convert(source, target))
            self.assertEqual(expected, self.convert(source, target))
            self.assertEqual(expected, self.convert(alternate, target))


if __name__ == '__main__':
    unittest.main()
