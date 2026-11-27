"""Save the text of a locally downloaded official web page as Markdown."""

import argparse
from hashlib import sha256
from html.parser import HTMLParser
from pathlib import Path
import re


VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
SKIP = {"aside", "button", "form", "iframe", "nav", "noscript", "script", "style", "svg", "template"}
BLOCKS = {"address", "blockquote", "caption", "dd", "div", "dl", "dt", "figcaption", "p", "section", "table", "tr", "ul", "ol"}
HEADINGS = {f"h{n}" for n in range(1, 7)}


class ContentText(HTMLParser):
    def __init__(self, root):
        super().__init__(convert_charrefs=True)
        self.root = root
        self.started = False
        self.stack = []
        self.parts = []

    @property
    def included(self):
        return self.stack and not self.stack[-1][1]

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if not self.stack:
            if not self.started and self.root(tag, attrs):
                self.started = True
                self.stack.append((tag, False))
            return
        omitted = not self.included or tag in SKIP or "hidden" in attrs or attrs.get("aria-hidden") == "true"
        if tag not in VOID:
            self.stack.append((tag, omitted))
        if omitted:
            return
        if tag in HEADINGS:
            self.parts.append("\n\n" + "#" * min(int(tag[1]) + 1, 6) + " ")
        elif tag == "li":
            self.parts.append("\n- ")
        elif tag in BLOCKS or tag == "hr":
            self.parts.append("\n\n")
        elif tag == "br":
            self.parts.append("\n")
        elif tag in {"td", "th"}:
            self.parts.append(" | ")

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                if self.included and (tag in BLOCKS or tag in HEADINGS or tag == "li"):
                    self.parts.append("\n")
                del self.stack[i:]
                return

    def handle_data(self, data):
        if self.included:
            self.parts.append(re.sub(r"\s+", " ", data))

    def markdown(self):
        lines = [re.sub(r" +", " ", line).strip().removeprefix("| ") for line in "".join(self.parts).splitlines()]
        return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def page_text(html, root_id=None, root_class=None):
    roots = (
        [lambda tag, attrs: attrs.get("id") == root_id] if root_id else
        [lambda tag, attrs: root_class in attrs.get("class", "").split()] if root_class else
        [lambda tag, attrs: tag == "main" or attrs.get("role") == "main", lambda tag, attrs: tag == "article"]
    )
    for root in roots:
        parser = ContentText(root)
        parser.feed(html)
        parser.close()
        if text := parser.markdown():
            return text
    raise ValueError("no text in the requested page content region")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--url", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--edition", required=True)
    parser.add_argument("--credit", required=True)
    root = parser.add_mutually_exclusive_group()
    root.add_argument("--root-id")
    root.add_argument("--root-class")
    args = parser.parse_args()
    try:
        data = args.input.read_bytes()
        charset = re.search(rb"charset\s*=\s*[\"']?([\w.-]+)", data[:65536], re.IGNORECASE)
        text = page_text(data.decode(charset[1].decode("ascii") if charset else "utf-8-sig"), args.root_id, args.root_class)
        header = (
            f"# {args.title}\n\n"
            f"Source: {args.url}\n"
            f"Edition: {args.edition}\n"
            f"Credit and licence: {args.credit}\n"
            f"Source HTML SHA-256: {sha256(data).hexdigest()}\n\n"
            "<!-- body -->\n"
        )
        with args.out.open("x", encoding="utf-8", newline="\n") as out:
            out.write(header + text + "\n<!-- end -->\n")
    except (LookupError, OSError, ValueError) as error:
        parser.exit(1, f"webtext: {error}\n")


if __name__ == "__main__":
    main()
