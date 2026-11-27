import io
import json
import runpy
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / 'testdata' / 'nccih'
SCRIPT = HERE / 'nccih.py'
BASE_URL = 'https://www.nccih.nih.gov/health/'
EXPECTED_SLUGS = [
    'traditional-chinese-medicine-what-you-need-to-know',
    'acupuncture-what-you-need-to-know',
    'using-dietary-supplements-wisely',
    'safety',
    'lavender',
    'peppermint-oil',
]


def convert(index, output):
    pages = json.loads((FIXTURE / 'pages.json').read_text(encoding='utf-8'))
    queries = []
    log = io.StringIO()

    def page_response(request, timeout):
        url = request.full_url
        queries.append(url)
        if not url.startswith(BASE_URL) or url[len(BASE_URL):] not in pages:
            raise AssertionError(f'Unexpected request: {url}')
        return io.BytesIO(pages[url[len(BASE_URL):]].encode('utf-8'))

    with (
        patch('urllib.request.urlopen', side_effect=page_response),
        patch('time.sleep'),
        patch.object(sys, 'argv', [str(SCRIPT), str(index), str(output)]),
        redirect_stdout(log),
    ):
        runpy.run_path(str(SCRIPT), run_name='__main__')
    return queries, log.getvalue()


class NccihTest(unittest.TestCase):
    def test_page_selection_markup_and_lf_golden_rerun(self):
        expected = (FIXTURE / 'expected.md').read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / 'nccih.md'
            for _ in range(2):
                queries, log = convert(FIXTURE / 'index.html', out)
                self.assertEqual([BASE_URL + slug for slug in EXPECTED_SLUGS], queries)
                self.assertEqual('6 pages; 0 failed\n', log)
                self.assertEqual(expected, out.read_bytes())


if __name__ == '__main__':
    unittest.main()
