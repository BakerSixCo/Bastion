import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class FiguresTest {
    public static void main(String[] args) throws IOException {
        check(args.length == 1, "Pass a scratch directory under out/");
        testCaptions();
        testLicence();
        check(Figures.documentTitle("Army-ATP-3-50.21-Survival-2018.pdf", "Chapter 1").equals("Survival"),
                "Reviewed cover title");
        check(Figures.documentTitle("Army-Synthetic.pdf", "Microsoft Word - Draft.docx").equals("Army-Synthetic"),
                "Placeholder title falls back to the filename stem");
        Path root = Path.of(args[0]);
        Path sources = Files.createDirectories(root.resolve("pdfs"));
        createFixture(sources.resolve("Army-Synthetic.pdf"));
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        List<Figures.Entry> entries = Figures.extract(sources, first);
        check(entries.size() == 9, "Expected nine candidates: " + entries);
        checkEntry(entries.get(0), 1, "C-11", "Figure C-1. Example & diagram", "rendered-region", "high");
        checkEntry(entries.get(1), 2, null, "Fig. 2: Caption above", "rendered-region", "high");
        checkEntry(entries.get(2), 3, null, "Figure 3. No drawing", "full-page", "low");
        checkEntry(entries.get(3), 4, null, "Figure 4. Two possible drawings", "full-page", "low");
        checkEntry(entries.get(4), 5, null, "Figure 5a. First possible caption", "full-page", "low");
        checkEntry(entries.get(5), 5, null, "Figure 5b. Second possible caption", "full-page", "low");
        checkEntry(entries.get(6), 6, null, "", "rendered-region", "low");
        checkEntry(entries.get(7), 9, "9", "Figure 9a. Left", "rendered-region", "high");
        checkEntry(entries.get(8), 9, "9", "Figure 9b. Right", "rendered-region", "high");
        check(!entries.get(7).hash().equals(entries.get(8).hash()), "Side-by-side figures get separate images");
        check(entries.getFirst().width() < entries.get(2).width(), "Expected a crop narrower than the page");
        check(entries.getFirst().height() < entries.get(2).height(), "Expected a crop shorter than the page");
        check(Math.abs(entries.get(2).width() - 833) <= 1, "Expected 200 dpi for 300 points of page width");
        for (Figures.Entry entry : entries) {
            Path file = first.resolve(entry.file());
            var image = ImageIO.read(file.toFile());
            check(image.getWidth() == entry.width() && image.getHeight() == entry.height(), "PNG dimensions");
            check(Figures.sha256(Files.readAllBytes(file)).equals(entry.hash()), "PNG digest");
            check(entry.title().equals("Fixture & test"), "PDF title");
        }
        var vector = ImageIO.read(first.resolve(entries.getFirst().file()).toFile());
        check((vector.getRGB(vector.getWidth() / 2, vector.getHeight() / 2) & 0xffffff) != 0xffffff,
                "The crop contains the painted vector");
        check(Files.readString(first.resolve("index.html")).contains("Example &amp; diagram"), "HTML escaping");
        check(Files.readString(first.resolve("index.json")).contains("\"printedPage\":\"C-11\""),
                "JSON provenance");
        check(entries.equals(Figures.extract(sources, second)), "Stable entries and PNG digests");
        try (var files = Files.walk(first)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                check(Arrays.equals(Files.readAllBytes(f), Files.readAllBytes(second.resolve(first.relativize(f)))),
                        "Unstable file: " + f);
            }
        }
        System.out.println("PASS: caption and licence parsing, vector crops, ambiguous/missing-art fallbacks, "
                + "uncaptioned art, provenance, and deterministic output");
    }

    private static void testCaptions() {
        check(Figures.startsCaption("Figure C-1. Ground-to-air markings"), "Army appendix caption");
        check(Figures.startsCaption("Fig. 1.2b: Illustration"), "Decimal caption");
        check(Figures.startsCaption("FIGURE 6–8 Snow shelter"), "En dash numbering");
        check(!Figures.startsCaption("As shown in figure 2, see below."), "Inline reference");
        check(!Figures.startsCaption("Figure shows a line."), "Ordinary sentence");
        check(!Figures.startsCaption("Figure 5-5. Fire lays .......... 5-9"), "Table of contents leader");
        check(!Figures.startsCaption("Figure 5-5. Fire lays .........."), "Separated TOC page number");
        var first = new Figures.Line("Figure 11. A wrapped caption", new Figures.Box(40, 30, 210, 40), 12, true);
        var second = new Figures.Line("with its original wording", new Figures.Box(40, 44, 174, 54), 12, true);
        var body = new Figures.Line("Following paragraph.", new Figures.Box(40, 58, 170, 68), 12, false);
        var caption = Figures.captions(List.of(first, second, body)).getFirst();
        check(caption.text().equals(first.text() + "\n" + second.text()), "Preserved caption line break");
        check(caption.bounds().bottom() == 54, "Wrapped caption geometry");
    }

    private static void testLicence() {
        for (String prefix : List.of("Army", "JTS", "CDC", "EPA", "FDA", "FEMA", "HHS", "USDA", "AFRRI",
                "USAMRIID", "NOAA", "ORNL", "US Forest Service")) {
            check(Figures.licence(prefix + "-Manual.pdf").equals("US government work (public domain)"), prefix);
        }
        check(Figures.licence("Special-Forest-Products-Pacific-Northwest-PNW-GTR-513.pdf")
                .equals("US government work (public domain)"), "Known Forest Service publication");
        for (String unclear : List.of("WHO-Manual.pdf", "Idaho-Manual.pdf", "Private-Army.pdf", "Armywise.pdf")) {
            check(Figures.licence(unclear).equals("check"), unclear);
        }
    }

    private static void createFixture(Path path) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.getDocumentInformation().setTitle("Fixture & test");
            for (int pageNumber = 1; pageNumber <= 9; pageNumber++) {
                var page = new PDPage(new PDRectangle(300, 400));
                doc.addPage(page);
                if (pageNumber == 8) {
                    continue;
                }
                try (var stream = new PDPageContentStream(doc, page)) {
                    switch (pageNumber) {
                        case 1 -> {
                            art(stream, 165);
                            text(stream, 55, 147, "Figure C-1. Example & diagram");
                            text(stream, 255, 21, "C-11");
                        }
                        case 2 -> {
                            text(stream, 70, 250, "Fig. 2: Caption above");
                            art(stream, 157);
                        }
                        case 3 -> text(stream, 45, 170, "Figure 3. No drawing");
                        case 4 -> {
                            art(stream, 217);
                            text(stream, 45, 200, "Figure 4. Two possible drawings");
                            art(stream, 114);
                        }
                        case 5 -> {
                            text(stream, 45, 253, "Figure 5a. First possible caption");
                            art(stream, 160);
                            text(stream, 45, 143, "Figure 5b. Second possible caption");
                        }
                        case 6 -> art(stream, 157);
                        case 7 -> {
                            text(stream, 45, 350, "List of Figures");
                            text(stream, 45, 310, "Figure 1. A list entry .................... 1");
                        }
                        case 9 -> {
                            stream.setNonStrokingColor(0.1f, 0.25f, 0.5f);
                            stream.addRect(40, 174, 82, 70);
                            stream.fill();
                            stream.setNonStrokingColor(0.6f, 0.4f, 0.2f);
                            stream.addRect(168, 174, 92, 66);
                            stream.fill();
                            text(stream, 40, 153, "Figure 9a. Left ");
                            text(stream, 168, 153, "Figure 9b. Right ");
                            text(stream, 35, 20, "9 ");
                            text(stream, 120, 20, "Sample footer");
                        }
                        default -> throw new AssertionError(pageNumber);
                    }
                }
            }
            doc.save(path.toFile());
        }
    }

    private static void art(PDPageContentStream stream, float y) throws IOException {
        stream.setNonStrokingColor(0.1f, 0.25f, 0.5f);
        stream.addRect(65, y, 165, 70);
        stream.fill();
    }

    private static void text(PDPageContentStream stream, float x, float y, String text) throws IOException {
        stream.setNonStrokingColor(0f);
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private static void checkEntry(Figures.Entry entry, int page, String printed, String caption,
                                   String method, String confidence) {
        check(entry.page() == page && java.util.Objects.equals(entry.printed(), printed)
                && entry.caption().equals(caption) && entry.method().equals(method)
                && entry.confidence().equals(confidence), "Unexpected entry: " + entry);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
