"""Render one complete PDF page to stdout using the local PDFium runtime."""

import sys
from contextlib import closing

import pypdfium2


def main() -> None:
    filename, page, dpi = sys.argv[1:]
    with pypdfium2.PdfDocument(filename) as document:
        with closing(document[int(page) - 1]) as source:
            with closing(source.render(scale=int(dpi) / 72, draw_annots=False, rev_byteorder=True)) as bitmap:
                bitmap.to_pil().convert("RGB").save(sys.stdout.buffer, format="PNG")


if __name__ == "__main__":
    main()
