"""Verify, fetch, and trim the kit using the checked-in source manifest."""

from __future__ import annotations

import argparse
import hashlib
import importlib.machinery
import importlib.util
import json
import re
import shutil
import site
import sys
import tempfile
from pathlib import Path, PurePosixPath, PureWindowsPath
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

HERE = Path(__file__).resolve().parent
DEFAULT_MANIFEST = HERE / "sources.json"
BUFFER_SIZE = 1024 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}")
ORIGINS = {"url", "browser-copy", "owner-download", "derived"}


class KitError(Exception):
    """An input or output that cannot be verified safely."""


def relative_file(value: str) -> PurePosixPath:
    if not isinstance(value, str):
        raise KitError("manifest file names must be strings")
    path = PurePosixPath(value)
    if (not path.parts or path.is_absolute() or path.as_posix() != value or ".." in path.parts
            or any("\\" in part or ":" in part or part.endswith((".", " "))
                   or PureWindowsPath(part).is_reserved() for part in path.parts)):
        raise KitError(f"invalid relative file name: {value!r}")
    return path


def https_host(url: str) -> tuple[str, int]:
    try:
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or "@" in parsed.netloc:
            raise ValueError
        return parsed.hostname, parsed.port or 443
    except (TypeError, ValueError) as error:
        raise KitError("download URLs must use HTTPS without credentials") from error


def trim_ranges(trim: dict) -> list[dict]:
    if not isinstance(trim, dict) or not isinstance(trim.get("ranges"), list) or not trim["ranges"]:
        raise KitError("trim must specify nonempty page ranges")
    output = relative_file(trim.get("output"))
    if len(output.parts) != 1 or output.suffix.lower() != ".pdf":
        raise KitError("trim output must be a PDF filename")
    last = 0
    for item in trim["ranges"]:
        if not isinstance(item, dict):
            raise KitError("invalid trim page range")
        start, end = item.get("pdfStart"), item.get("pdfEnd")
        if type(start) is not int or type(end) is not int or not last < start <= end:
            raise KitError("trim page ranges must be one-based, ascending and nonoverlapping")
        if not isinstance(item.get("printed"), str) or not item["printed"].strip():
            raise KitError("trim page range is missing its printed page label")
        last = end
    return trim["ranges"]


def load_manifest(path: Path = DEFAULT_MANIFEST) -> dict[str, dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("schemaVersion") != 1 or not isinstance(data.get("entries"), list):
        raise KitError("expected a version 1 source manifest")
    entries = {}
    files = set()
    for entry in data["entries"]:
        if not isinstance(entry, dict):
            raise KitError("manifest entries must be objects")
        entry_id = entry.get("id")
        if not isinstance(entry_id, str) or not entry_id or entry_id in entries:
            raise KitError(f"missing or duplicate manifest id: {entry_id!r}")
        file = relative_file(entry.get("file")).as_posix().casefold()
        if file in files:
            raise KitError(f"duplicate manifest file: {entry['file']}")
        digest = entry.get("sha256")
        if digest is not None and (not isinstance(digest, str) or not SHA256.fullmatch(digest)):
            raise KitError(f"{entry_id}: invalid SHA-256")
        origin = entry.get("origin")
        if not isinstance(origin, str) or origin not in ORIGINS:
            raise KitError(f"{entry_id}: unknown origin")
        if origin == "url":
            https_host(entry.get("url"))
        if entry.get("trim") is not None:
            trim_ranges(entry["trim"])
        entries[entry_id] = entry
        files.add(file)
    return entries


def digest_stream(stream) -> str:
    digest = hashlib.sha256()
    while chunk := stream.read(BUFFER_SIZE):
        digest.update(chunk)
    return digest.hexdigest()


def verify(entries: dict[str, dict], kit_dir: Path) -> int:
    if not kit_dir.is_dir():
        raise KitError(f"kit directory does not exist: {kit_dir}")
    by_file = {entry["file"]: entry for entry in entries.values()}
    actual = {}
    for path in kit_dir.rglob("*"):
        if path.is_symlink() or path.is_file():
            actual[path.relative_to(kit_dir).as_posix()] = path
    missing = sorted(by_file.keys() - actual.keys())
    for file in missing:
        print(f"MISSING {file}")
    counts = dict(verified=0, mismatched=0, unknown=0, unverified=0, unreadable=0)
    for file, path in sorted(actual.items()):
        try:
            if path.is_symlink():
                raise OSError("symbolic links are not kit files")
            with path.open("rb") as stream:
                digest = digest_stream(stream)
        except OSError as error:
            counts["unreadable"] += 1
            print(f"UNREADABLE {file}: {error}")
            continue
        if file not in by_file:
            counts["unknown"] += 1
            print(f"UNKNOWN {file}: SHA-256 {digest}")
        elif by_file[file].get("sha256") is None:
            counts["unverified"] += 1
            print(f"UNVERIFIED {file}: no expected SHA-256 (actual {digest})")
        elif by_file[file]["sha256"] != digest:
            counts["mismatched"] += 1
            print(f"MISMATCH {file}: expected {by_file[file]['sha256']}, got {digest}")
        else:
            counts["verified"] += 1
    print(f"{len(missing)} missing, " + ", ".join(f"{n} {label}" for label, n in counts.items()))
    return int(bool(missing or any(n for label, n in counts.items() if label != "verified")))


class SameHostRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if https_host(newurl) != https_host(req.full_url):
            raise KitError("refused redirect to a different host or port")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(entries: list[dict], out: Path) -> None:
    if any(entry["origin"] != "url" for entry in entries):
        raise KitError("fetch requires origin: url for every selected entry")
    if len({entry["id"] for entry in entries}) != len(entries):
        raise KitError("fetch IDs must not repeat")
    if out.is_symlink() or (out.exists() and (not out.is_dir() or any(out.iterdir()))):
        raise KitError("fetch output must be a new or empty directory")
    out.mkdir(parents=True, exist_ok=True)
    opener = build_opener(SameHostRedirect())
    for entry in entries:
        path = out.joinpath(*relative_file(entry["file"]).parts)
        url = entry["url"]
        host = https_host(url)
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryFile(dir=path.parent) as tmp:
            request = Request(url, headers={"User-Agent": "BASTION kit manifest tool"})
            with opener.open(request, timeout=60) as response:
                if https_host(response.geturl()) != host:
                    raise KitError("response came from a different host or port")
                shutil.copyfileobj(response, tmp, length=BUFFER_SIZE)
            size = tmp.tell()
            tmp.seek(0)
            digest = digest_stream(tmp)
            expected = entry.get("sha256")
            if expected is not None and digest != expected:
                raise KitError(f"{entry['id']}: SHA-256 mismatch, expected {expected}, got {digest}")
            tmp.seek(0)
            created = False
            try:
                with path.open("xb") as target:
                    created = True
                    shutil.copyfileobj(tmp, target, length=BUFFER_SIZE)
            except BaseException:
                if created:
                    path.unlink(missing_ok=True)
                raise
        label = "verified" if expected is not None else "no expected SHA-256"
        print(f"FETCHED {entry['file']}: {size} bytes, SHA-256 {digest} ({label})")


def pypdf_module():
    try:
        import pypdf
        return pypdf
    except ModuleNotFoundError as error:
        if error.name != "pypdf":
            raise
    # Python -I deliberately excludes user packages. Load exactly the user-installed
    # pypdf package without adding that directory to sys.path or running any .pth files.
    spec = importlib.machinery.PathFinder.find_spec("pypdf", [site.getusersitepackages()])
    if spec is None or spec.loader is None:
        raise KitError("pypdf is required for trim; install the pinned version named in the kit report")
    module = importlib.util.module_from_spec(spec)
    sys.modules["pypdf"] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        del sys.modules["pypdf"]
        raise
    return module


def trim(entry: dict, kit_dir: Path, out: Path) -> None:
    plan = entry.get("trim")
    if plan is None:
        raise KitError(f"{entry['id']}: no trim plan in the manifest")
    ranges = trim_ranges(plan)
    expected = entry.get("sha256")
    if expected is None:
        raise KitError(f"{entry['id']}: trim requires a pinned original SHA-256")
    source_root = kit_dir.resolve()
    source = source_root.joinpath(*relative_file(entry["file"]).parts)
    if source.is_symlink() or not source.resolve().is_relative_to(source_root):
        raise KitError("trim source must be a file inside the kit directory")
    if out.resolve().is_relative_to(source_root):
        raise KitError("trim output must be outside the source kit directory")
    pdf = out / plan["output"]
    sidecar = pdf.with_suffix(".provenance.txt")
    if any(path.exists() or path.is_symlink() for path in (pdf, sidecar)):
        raise KitError("trim output already exists")

    pypdf = pypdf_module()
    with source.open("rb") as stream:
        actual = digest_stream(stream)
        if actual != expected:
            raise KitError(f"{entry['id']}: original SHA-256 mismatch, expected {expected}, got {actual}")
        stream.seek(0)
        try:
            reader = pypdf.PdfReader(stream)
            if len(reader.pages) < ranges[-1]["pdfEnd"]:
                raise KitError(f"{entry['id']}: trim range exceeds the original PDF")
            writer = pypdf.PdfWriter()
            for item in ranges:
                for page in range(item["pdfStart"] - 1, item["pdfEnd"]):
                    writer.add_page(reader.pages[page])
            out.mkdir(parents=True, exist_ok=True)
            created = []
            try:
                with pdf.open("xb") as target:
                    created.append(pdf)
                    writer.write(target)
                with sidecar.open("x", encoding="utf-8", newline="\n") as target:
                    created.append(sidecar)
                    for item in ranges:
                        target.write(
                            f"PDF pages {item['pdfStart']}–{item['pdfEnd']} of {entry['file']} "
                            f"(printed {item['printed']}), SHA-256 {actual}\n"
                        )
            except BaseException:
                for path in created:
                    path.unlink(missing_ok=True)
                raise
        except pypdf.errors.PyPdfError as error:
            raise KitError(f"{entry['id']}: could not trim the PDF: {error}") from error
    print(f"TRIMMED {pdf.name}: {len(writer.pages)} pages, provenance {sidecar.name}")


def default_kit_dir() -> Path:
    in_checkout = HERE / "docs"
    if in_checkout.is_dir():
        return in_checkout
    sibling = HERE.parents[2] / "Bastion" / "tools" / "kit" / "docs"
    return sibling if sibling.is_dir() else in_checkout


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("verify").add_argument("kit_dir", type=Path)
    fetch_parser = commands.add_parser("fetch")
    fetch_parser.add_argument("ids", nargs="+")
    fetch_parser.add_argument("--out", type=Path, required=True)
    trim_parser = commands.add_parser("trim")
    trim_parser.add_argument("id")
    trim_parser.add_argument("--out", type=Path, required=True)
    trim_parser.add_argument("--kit-dir", type=Path, default=default_kit_dir())
    args = parser.parse_args(argv)
    try:
        entries = load_manifest(args.manifest)
        if args.command == "verify":
            return verify(entries, args.kit_dir)
        if args.command == "fetch":
            fetch([entries[entry_id] for entry_id in args.ids], args.out)
        else:
            trim(entries[args.id], args.kit_dir, args.out)
        return 0
    except KeyError as error:
        print(f"kit: missing field or unknown ID {error}", file=sys.stderr)
    except (KitError, OSError, ValueError, URLError) as error:
        print(f"kit: {error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
