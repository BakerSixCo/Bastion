"""Offline fixtures for python -I tools/kit/test_kit.py."""

import hashlib
import importlib.util
import io
import json
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
from urllib.request import Request

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("kit", HERE / "kit.py")
kit = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(kit)


def validate_schema(value, schema, root, at="$"):
    """Evaluate the JSON Schema keywords used by the kit's schema using stdlib only."""
    supported = {
        "$id", "$schema", "$defs", "$ref", "title", "description", "type", "required",
        "properties", "additionalProperties", "items", "minItems", "minLength",
        "minimum", "pattern", "enum", "const", "uniqueItems", "allOf", "if", "then", "else",
    }
    assert schema.keys() <= supported, f"{at}: unsupported schema keywords {schema.keys() - supported}"
    if "$ref" in schema:
        reference = schema["$ref"]
        assert reference.startswith("#/"), f"{at}: expected a local $ref"
        target = root
        for key in reference[2:].split("/"):
            target = target[key.replace("~1", "/").replace("~0", "~")]
        validate_schema(value, target, root, at)
    if "type" in schema:
        json_types = {
            "object": dict, "array": list, "string": str, "integer": int, "null": type(None),
        }
        kinds = schema["type"]
        kinds = [kinds] if isinstance(kinds, str) else kinds
        assert any(type(value) is json_types[kind] for kind in kinds), f"{at}: expected {kinds}"
    if "const" in schema:
        assert value == schema["const"], f"{at}: expected constant {schema['const']!r}"
    if "enum" in schema:
        assert value in schema["enum"], f"{at}: not in {schema['enum']!r}"
    if isinstance(value, str):
        assert len(value) >= schema.get("minLength", 0), f"{at}: string too short"
        if "pattern" in schema:
            assert re.search(schema["pattern"], value), f"{at}: does not match {schema['pattern']}"
    if type(value) is int and "minimum" in schema:
        assert value >= schema["minimum"], f"{at}: below minimum"
    if isinstance(value, list):
        assert len(value) >= schema.get("minItems", 0), f"{at}: too few items"
        if schema.get("uniqueItems"):
            assert len({json.dumps(v, sort_keys=True) for v in value}) == len(value), f"{at}: duplicate items"
        if "items" in schema:
            for i, child in enumerate(value):
                validate_schema(child, schema["items"], root, f"{at}[{i}]")
    if isinstance(value, dict):
        required = set(schema.get("required", []))
        assert required <= value.keys(), f"{at}: missing properties {required - value.keys()}"
        properties = schema.get("properties", {})
        extra = value.keys() - properties.keys()
        if schema.get("additionalProperties") is False:
            assert not extra, f"{at}: unexpected properties {extra}"
        for key, child in properties.items():
            if key in value:
                validate_schema(value[key], child, root, f"{at}.{key}")
    for subschema in schema.get("allOf", []):
        validate_schema(value, subschema, root, at)
    if "if" in schema:
        try:
            validate_schema(value, schema["if"], root, at)
        except AssertionError:
            branch = "else"
        else:
            branch = "then"
        if branch in schema:
            validate_schema(value, schema[branch], root, at)


class ManifestTest(unittest.TestCase):
    def test_manifest_validates_against_schema(self):
        manifest = json.loads(kit.DEFAULT_MANIFEST.read_text(encoding="utf-8"))
        schema = json.loads((HERE / "sources.schema.json").read_text(encoding="utf-8"))
        validate_schema(manifest, schema, schema)
        entries = kit.load_manifest()
        self.assertEqual(len(manifest["entries"]), len(entries))


class KitTest(unittest.TestCase):
    def setUp(self):
        scratch = (HERE / "out").resolve()
        self.assertTrue(scratch.is_relative_to(HERE))
        scratch.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="kit-test-", dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.stage = Path(temporary.name)

    @staticmethod
    def entry(file, content, origin="owner-download"):
        return {
            "id": "fake-entry",
            "file": file,
            "title": "Fictitious test document",
            "publisher": "Fictitious test publisher",
            "edition": "2026-01-01",
            "origin": origin,
            "url": "https://docs.example.test/fake" if origin == "url" else None,
            "sha256": hashlib.sha256(content).hexdigest(),
            "licence": "Fake licence for test only",
            "tier": "reference" if file.startswith("reference/") else "own",
            "flags": [],
        }

    def test_verify_checks_hashes_and_reports_every_file_class(self):
        tree = self.stage / "input"
        (tree / "reference").mkdir(parents=True)
        (tree / "reference" / "changed.txt").write_bytes(b"document!")
        (tree / "known.txt").write_bytes(b"known")
        (tree / "unknown.txt").write_bytes(b"new")
        (tree / "unverified.txt").write_bytes(b"without expected hash")
        entries = {
            "changed": self.entry("reference/changed.txt", b"document?"),
            "missing": self.entry("missing.txt", b"gone"),
            "known": self.entry("known.txt", b"known"),
            "unverified": dict(self.entry("unverified.txt", b""), sha256=None),
        }
        log = io.StringIO()
        with redirect_stdout(log):
            self.assertEqual(kit.verify(entries, tree), 1)
        self.assertIn("MISSING missing.txt", log.getvalue())
        self.assertIn("MISMATCH reference/changed.txt", log.getvalue())
        self.assertIn("UNKNOWN unknown.txt", log.getvalue())
        self.assertIn("UNVERIFIED unverified.txt", log.getvalue())
        self.assertIn("1 verified, 1 mismatched", log.getvalue())
        (tree / "reference" / "changed.txt").write_bytes(b"document?")
        (tree / "missing.txt").write_bytes(b"gone")
        (tree / "unknown.txt").unlink()
        (tree / "unverified.txt").unlink()
        del entries["unverified"]
        with redirect_stdout(io.StringIO()):
            self.assertEqual(kit.verify(entries, tree), 0)

    def test_fetch_refuses_non_url_and_occupied_output_without_network(self):
        out = self.stage / "fetch"
        non_url = self.entry("manual.txt", b"data")
        url_entry = self.entry("manual.txt", b"data", origin="url")
        with patch.object(kit, "build_opener", side_effect=AssertionError("unexpected network use")):
            with self.assertRaisesRegex(kit.KitError, "origin: url"):
                kit.fetch([non_url], out)
            self.assertFalse(out.exists())
            out.mkdir()
            original = out / "manual.txt"
            original.write_bytes(b"keep this file")
            with self.assertRaisesRegex(kit.KitError, "new or empty"):
                kit.fetch([url_entry], out)
            self.assertEqual(original.read_bytes(), b"keep this file")

    def test_fetch_verifies_before_output_and_reports_unpinned_hash(self):
        class Response(io.BytesIO):
            def geturl(self):
                return "https://docs.example.test/fake"

        entry = self.entry("reference/manual.txt", b"correct", origin="url")
        out = self.stage / "fetch"
        with patch.object(kit, "build_opener") as opening:
            opener = opening.return_value
            opener.open.return_value = Response(b"wrong")
            with self.assertRaisesRegex(kit.KitError, "SHA-256 mismatch"):
                kit.fetch([entry], out)
            self.assertFalse((out / "reference" / "manual.txt").exists())
            other = self.stage / "verified"
            opener.open.return_value = Response(b"correct")
            log = io.StringIO()
            with redirect_stdout(log):
                kit.fetch([entry], other)
            self.assertIn("(verified)", log.getvalue())
            self.assertEqual((other / "reference" / "manual.txt").read_bytes(), b"correct")
            opener.open.return_value = Response(b"new data")
            other = self.stage / "unpinned"
            log = io.StringIO()
            with redirect_stdout(log):
                kit.fetch([dict(entry, sha256=None)], other)
            self.assertIn(hashlib.sha256(b"new data").hexdigest(), log.getvalue())
            self.assertIn("no expected SHA-256", log.getvalue())

    def test_redirect_handler_refuses_other_hosts_without_network(self):
        redirect = kit.SameHostRedirect()
        original = Request("https://docs.example.test/source")
        request = redirect.redirect_request(original, None, 302, "found", {}, "https://docs.example.test/asset")
        self.assertEqual(request.full_url, "https://docs.example.test/asset")
        for destination in ["https://elsewhere.example.test/asset", "http://docs.example.test/asset",
                            "https://docs.example.test:8443/asset"]:
            with self.subTest(destination=destination):
                with self.assertRaises(kit.KitError):
                    redirect.redirect_request(original, None, 302, "found", {}, destination)

    def test_trim_keeps_exact_requested_pages_and_writes_provenance(self):
        pypdf = kit.pypdf_module()
        root = self.stage / "kit"
        root.mkdir()
        source = root / "manual.pdf"
        writer = pypdf.PdfWriter()
        for width in [101, 102, 103, 104, 105, 106]:
            writer.add_blank_page(width=width, height=200)
        with source.open("wb") as handle:
            writer.write(handle)
        original = source.read_bytes()
        entry = self.entry("manual.pdf", original)
        entry["trim"] = {
            "output": "manual-trimmed.pdf",
            "ranges": [
                {"pdfStart": 2, "pdfEnd": 3, "printed": "ii–iii"},
                {"pdfStart": 6, "pdfEnd": 6, "printed": "5"},
            ],
        }
        out = self.stage / "trim"
        with redirect_stdout(io.StringIO()):
            kit.trim(entry, root, out)
        with (out / "manual-trimmed.pdf").open("rb") as handle:
            reader = pypdf.PdfReader(handle)
            self.assertEqual([page.mediabox.width for page in reader.pages], [102, 103, 106])
        self.assertEqual(source.read_bytes(), original)
        provenance = (out / "manual-trimmed.provenance.txt").read_text(encoding="utf-8").splitlines()
        self.assertEqual(provenance, [
            f"PDF pages 2–3 of manual.pdf (printed ii–iii), SHA-256 {entry['sha256']}",
            f"PDF pages 6–6 of manual.pdf (printed 5), SHA-256 {entry['sha256']}",
        ])
        with self.assertRaisesRegex(kit.KitError, "already exists"):
            kit.trim(entry, root, out)
        with self.assertRaisesRegex(kit.KitError, "original SHA-256 mismatch"):
            kit.trim(dict(entry, sha256="0" * 64), root, self.stage / "bad-trim")
        self.assertFalse((self.stage / "bad-trim").exists())


if __name__ == "__main__":
    unittest.main()
