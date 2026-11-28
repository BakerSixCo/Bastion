"""Convert official U.S. and Idaho constitutional documents to Markdown."""

import argparse
from hashlib import sha256
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import re

WEBTEXT_PATH = Path(__file__).resolve().with_name("webtext.py")
parser_spec = spec_from_file_location("webtext", WEBTEXT_PATH)
assert parser_spec is not None and parser_spec.loader is not None
webtext = module_from_spec(parser_spec)
parser_spec.loader.exec_module(webtext)


NARA_BOUNDS = {
    "constitution": ("We the People ", "For biographies of the non-signing delegates"),
    "amendments-1-10": ("### The U.S. Bill of Rights", "Amendments 11-27"),
    "amendments-11-27": ("#### AMENDMENT XI\n", "Back to Constitution Main Page"),
    "declaration": ("In Congress, July 4, 1776", "Back to Main Declaration Page"),
    "confederation": ("### Transcript\n", None),
}
IDAHO_URL = "https://legislature.idaho.gov/statutesrules/idconst/"
ARTICLE = re.compile(r"idaho-([ivx]+)", re.I)
SECTION = re.compile(r"idaho-([ivx]+)-(\d+[a-z]?)", re.I)


class RepeatedContent(webtext.ContentText):
    """Collect consecutive page regions from one official section."""

    def handle_endtag(self, tag):
        super().handle_endtag(tag)
        if self.started and not self.stack:
            self.parts.append("\n\n")
            self.started = False


def select(html, tag, css_class, repeat=False):
    parser_type = RepeatedContent if repeat else webtext.ContentText
    parser = parser_type(lambda name, attrs: name == tag and css_class in attrs.get("class", "").split())
    parser.feed(html)
    parser.close()
    if not (text := parser.markdown()):
        raise ValueError(f"missing {tag}.{css_class}")
    return text


def nara_body(html, document):
    text = webtext.page_text(html, root_id="block-system-main")
    start, end = NARA_BOUNDS[document]
    first = text.index(start)
    last = text.index(end, first + len(start)) if end else len(text)
    return text[first:last].strip()


def idaho_body(index, articles_dir, sections_dir):
    index_html = index.read_text(encoding="utf-8-sig")
    preamble = re.search(r"<center>PREAMBLE</center>.*?<center>(.*?)</center>", index_html, re.S | re.I)
    article_order = list(dict.fromkeys(re.findall(r'/idconst/Art([IVX]+)"', index_html, re.I)))
    if not preamble or len(article_order) != 21:
        raise ValueError("missing Idaho preamble or incomplete article index")
    preamble_text = webtext.page_text(f"<main>{preamble[1]}</main>")
    blocks = ["## Preamble", preamble_text]
    files = [index]
    for roman in article_order:
        code = roman.lower()
        article_file = articles_dir / f"idaho-{code}" / f"idaho-{code}.html"
        article = article_file.read_text(encoding="utf-8-sig")
        files.append(article_file)
        heading = select(article, "h2", "lso-toc").lstrip("# ")
        if not re.match(rf"ARTICLE\s+{roman}\b", heading, re.I):
            raise ValueError(f"unexpected Idaho article title: {heading}")
        blocks.append(f"## {heading}")
        sections = list(dict.fromkeys(re.findall(rf'/idconst/Art{roman}/Sect(\d+[a-z]?)"', article, re.I)))
        if not sections:
            raise ValueError(f"no sections found for Idaho Article {roman}")
        for number in sections:
            name = f"idaho-{code}-{number.lower()}"
            section_file = sections_dir / name / f"{name}.html"
            section_html = section_file.read_text(encoding="utf-8-sig")
            files.append(section_file)
            text = select(section_html, "div", "pgbrk", repeat=True)
            if not re.match(rf"Section\s+{number}\b", text, re.I):
                raise ValueError(f"{section_file} has a missing or unexpected section heading")
            blocks.append(text)
    return "\n\n".join(blocks), files


def source_digests(files):
    """Return a stable inventory independent of the download location."""
    result = []
    for file in files:
        if match := SECTION.fullmatch(file.stem):
            url = f"{IDAHO_URL}Art{match[1].upper()}/Sect{match[2].upper()}/"
        elif match := ARTICLE.fullmatch(file.stem):
            url = f"{IDAHO_URL}Art{match[1].upper()}/"
        else:
            url = IDAHO_URL
        result.append(f"{sha256(file.read_bytes()).hexdigest()}  {url}")
    return "\n".join(result) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--edition", required=True)
    parser.add_argument("--credit", required=True)
    source = parser.add_subparsers(dest="source", required=True)
    nara = source.add_parser("nara")
    nara.add_argument("document", choices=NARA_BOUNDS)
    idaho = source.add_parser("idaho")
    idaho.add_argument("articles", type=Path)
    idaho.add_argument("sections", type=Path)
    args = parser.parse_args()
    try:
        if args.source == "nara":
            data = args.input.read_bytes()
            body = nara_body(data.decode("utf-8-sig"), args.document)
            evidence = f"Source HTML SHA-256: {sha256(data).hexdigest()}"
            inventory = ""
        else:
            body, files = idaho_body(args.input, args.articles, args.sections)
            inventory = source_digests(files)
            evidence = f"Source inventory SHA-256: {sha256(inventory.encode('utf-8')).hexdigest()}"
        output = (
            f"# {args.title}\n\nSource: {args.url}\nEdition: {args.edition}\n"
            f"Credit and licence: {args.credit}\n{evidence}\n\n"
            f"<!-- body -->\n{body}\n<!-- end -->\n"
        )
        if inventory:
            output += f"\n<!-- Source SHA-256 and URL inventory\n{inventory}-->\n"
        with args.out.open("x", encoding="utf-8", newline="\n") as dest:
            dest.write(output)
    except (OSError, ValueError) as error:
        parser.exit(1, f"legaltext: {error}\n")


if __name__ == "__main__":
    main()
