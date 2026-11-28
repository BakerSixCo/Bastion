"""Run the pinned PdfBox-Android text stripper on tiny PDFs in a host JVM."""

import argparse
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
from zipfile import ZipFile


VERSION = "2.0.27.0"
ASSETS = "assets/com/tom_roush/"
FIXTURES = Path(__file__).with_name("pdfbox-fixtures")

JAVA_TEST = r"""
import com.tom_roush.pdfbox.android.PDFBoxConfig;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.nio.file.Path;

public class PdfBoxExtractionProbe {
    private static void exactText(Path input, String expected) throws Exception {
        try (PDDocument document = PDDocument.load(input.toFile())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            if (!expected.equals(text)) {
                throw new AssertionError(input.getFileName() + ": expected [" + expected + "], got [" + text + "]");
            }
        }
        System.out.println("PASS " + input.getFileName());
    }

    public static void main(String[] args) throws Exception {
        PDFBoxConfig.setFontLoadLevel(PDFBoxConfig.FontLoadLevel.NONE);
        Path fixtures = Path.of(args[0]);
        exactText(fixtures.resolve("base14-winansi.pdf"), "Café");
        exactText(fixtures.resolve("japan1-horizontal.pdf"), "日本");
        exactText(fixtures.resolve("japan1-vertical.pdf"), "日本");
    }
}
"""

# The SDK's Android classes link on the JVM but the logging and geometry stubs
# throw at runtime. PDF parsing, font logic and resource reads are PdfBox's own.
LOG = """
package android.util;
public final class Log {
    public static int d(String tag, String text) { return 0; }
    public static int i(String tag, String text) { return 0; }
    public static int w(String tag, String text) { return 0; }
    public static int w(String tag, String text, Throwable error) { return 0; }
    public static int w(String tag, Throwable error) { return 0; }
    public static int e(String tag, String text) { return 0; }
    public static int e(String tag, String text, Throwable error) { return 0; }
}
"""

POINT = """
package android.graphics;
public class PointF {
    public float x, y;
    public PointF() {}
    public PointF(float x, float y) { this.x = x; this.y = y; }
    public final void set(float x, float y) { this.x = x; this.y = y; }
    public final float length() { return length(x, y); }
    public static float length(float x, float y) { return (float) Math.hypot(x, y); }
}
"""

GRAPHICS_PATH = """
package android.graphics;
import java.awt.geom.Path2D;
public class Path {
    private final Path2D.Float geometry;
    public Path() { geometry = new Path2D.Float(); }
    public Path(Path other) { geometry = new Path2D.Float(other.geometry); }
    public void moveTo(float x, float y) { geometry.moveTo(x, y); }
    public void lineTo(float x, float y) { geometry.lineTo(x, y); }
    public void close() { geometry.closePath(); }
}
"""


def find_aar():
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    directory = gradle_home / "caches/modules-2/files-2.1/com.tom-roush/pdfbox-android" / VERSION
    matches = sorted(directory.glob(f"*/pdfbox-android-{VERSION}.aar"))
    if len(matches) != 1:
        raise SystemExit("Expected one pinned AAR in the local Gradle cache. Supply --aar.")
    return matches[0]


def find_android_jar():
    sdk_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk_home:
        return Path(sdk_home) / "platforms/android-35/android.jar"
    if os.environ.get("LOCALAPPDATA"):
        return Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-35/android.jar"
    raise SystemExit("Supply --android-jar or set ANDROID_HOME.")


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    if home:
        executable = name + (".exe" if os.name == "nt" else "")
        return Path(home) / "bin" / executable
    return shutil.which(name) or name


def stage_library(root, aar_path, asset_path, omissions):
    classes = root / "pdfbox-android.jar"
    assets = root / "resources"
    with ZipFile(aar_path) as aar, ZipFile(asset_path) as source:
        classes.write_bytes(aar.read("classes.jar"))
        entries = {item.filename for item in aar.infolist()
                   if item.filename.startswith(ASSETS) and not item.is_dir()}
        if not omissions <= entries:
            raise SystemExit(f"Unknown omission paths: {sorted(omissions - entries)}")
        missing = entries - omissions - set(source.namelist())
        if missing:
            raise SystemExit(f"Source archive is missing PdfBox assets: {sorted(missing)}")
        for name in sorted(entries - omissions):
            relative = PurePosixPath(name.removeprefix("assets/"))
            if relative.is_absolute() or ".." in relative.parts or "\\" in name or ":" in name:
                raise SystemExit(f"Invalid asset path: {name}")
            output = assets.joinpath(*relative.parts)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(source.read(name))
    return classes, assets


def exercise(args):
    aar = args.aar or find_aar()
    sdk = args.android_jar or find_android_jar()
    if not sdk.is_file():
        raise SystemExit(f"Local Android SDK JAR does not exist: {sdk}")
    with tempfile.TemporaryDirectory(prefix="bastion-pdfbox-") as directory:
        root = Path(directory)
        classes, assets = stage_library(root, aar, args.apk or aar, set(args.omit_asset))
        sources = {
            "PdfBoxExtractionProbe.java": JAVA_TEST,
            "android/util/Log.java": LOG,
            "android/graphics/PointF.java": POINT,
            "android/graphics/Path.java": GRAPHICS_PATH,
        }
        for name, code in sources.items():
            source = root / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(code, encoding="utf-8")
        output = root / "classes"
        output.mkdir()
        compile_cp = os.pathsep.join(map(str, [classes, sdk]))
        subprocess.run(
            [java_tool("javac"), "-encoding", "UTF-8", "-cp", compile_cp, "-d", output]
            + [root / name for name in sources], check=True,
        )
        run_cp = os.pathsep.join(map(str, [output, classes, assets, sdk]))
        subprocess.run([java_tool("java"), "-cp", run_cp, "PdfBoxExtractionProbe", FIXTURES], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, help="pinned PdfBox-Android AAR from the Gradle cache")
    parser.add_argument("--apk", type=Path, help="read assets from this APK instead of the AAR")
    parser.add_argument("--android-jar", type=Path, help="local Android SDK platform JAR")
    parser.add_argument("--omit-asset", default=[], action="append",
                        help="omit an exact assets/... path from the temporary JVM classpath")
    try:
        exercise(parser.parse_args())
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None


if __name__ == "__main__":
    main()
