import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.IIOImage;
import javax.imageio.metadata.IIOMetadataNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

public final class Figures {
    private static final int DPI = 200;
    private static final int MAX_BAND_GAP = 16;
    private static final int MAX_CAPTION_GAP = 36;
    private static final Pattern FIGURE = Pattern.compile(
            "(?i)^(?:figure|fig\\.?)\\s+(?:[A-Z][-\\u2010-\\u2013])?\\d+"
            + "(?:[-.\\u2010-\\u2013]\\d+)*[a-z]?(?:[.:\\u2013\\u2014]\\s*|\\s+|$)");
    private static final Pattern LEADER = Pattern.compile(
            "[.\\u2026·\\s]{4,}\\s*(?:[A-Z0-9]+[-–])?(?:\\d+|[ivxlcdm]+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRINTED_PAGE = Pattern.compile(
            "(?i)(?:page\\s+)?([A-Z0-9]+[-–]\\d+|\\d+|[ivxlcdm]{1,7})(?:\\s+(?:of|/)\\s+\\d+)?");
    private static final Pattern AGENCY = Pattern.compile(
            "(?i)^(Army|JTS|CDC|EPA|FDA|FEMA|HHS|USDA|AFRRI|USAMRIID|NOAA|ORNL|USFS|"
            + "US[- ]Forest[- ]Service)(?:[-_ ].*|\\.pdf)$");
    private static final Set<String> FOREST_SERVICE = Set.of(
            "Field-Guide-Forest-Plants-Northern-Idaho-RMRS-GTR-118.pdf",
            "Special-Forest-Products-Pacific-Northwest-PNW-GTR-513.pdf",
            "Wild-Edible-Mushrooms-Blue-Mountains-PNW-GTR-393.pdf");
    private static final String PUBLIC_DOMAIN = "US government work (public domain)";
    private static final Map<String, String> COVER_TITLES = Map.of(
            "Army-ATP-3-50.21-Survival-2018.pdf", "Survival",
            "Army-ATP-4-02.11-Casualty-Response-TCCC-First-Aid-2026.pdf",
            "Army Techinques Publication for Casualty Response, Tactical Combat Casualty Care, and First Aid",
            "Army-TC-21-3-Cold-Weather-Operations-Survival-2025.pdf",
            "Soldier’s Handbook for Individual Operations and Survival in Cold-Weather Areas",
            "WHO-Model-List-Essential-Medicines-24th-2025.pdf", "WHO Model List of Essential Medicines, 24th list");
    private static final Pattern PLACEHOLDER_TITLE = Pattern.compile(
            "(?i)(?:chapter\\s+\\d+|this is for chapters?.*|microsoft word\\s*-.*|"
            + ".*\\.(?:pdf|docx?)|guidance for industry|TB MED \\d+)");

    record Box(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
        int centerX() { return (left + right) / 2; }
        int overlapX(Box other) { return Math.min(right, other.right) - Math.max(left, other.left); }
        boolean contains(Box other) {
            return left <= other.left && right >= other.right && top <= other.top && bottom >= other.bottom;
        }
        Box union(Box other) {
            return new Box(Math.min(left, other.left), Math.min(top, other.top),
                    Math.max(right, other.right), Math.max(bottom, other.bottom));
        }
        Box pad(int points, int width, int height) {
            return new Box(Math.max(0, left - points), Math.max(0, top - points),
                    Math.min(width, right + points), Math.min(height, bottom + points));
        }
    }

    record Line(String text, Box bounds, int fontSize, boolean bold) {}
    record Caption(String text, Box bounds) {}
    record Candidate(String caption, Box crop) {
        String method() { return crop == null ? "full-page" : "rendered-region"; }
        String confidence() { return crop == null || caption.isEmpty() ? "low" : "high"; }
    }
    record Entry(String id, String doc, String title, int page, String printed, String caption,
                 String file, int width, int height, String hash, String licence, String method,
                 String confidence) {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 4) {
            System.err.println("Usage: Figures <PDF directory> <output directory> [<Python> <render-page.py>]");
            System.exit(2);
        }
        List<String> alternate = args.length == 4 ? List.of(args[2], args[3]) : List.of();
        extract(Path.of(args[0]), Path.of(args[1]), alternate);
    }

    static List<Entry> extract(Path input, Path output) throws IOException {
        return extract(input, output, List.of());
    }

    private static List<Entry> extract(Path input, Path output, List<String> alternate) throws IOException {
        List<Path> pdfs;
        try (var files = Files.list(input)) {
            pdfs = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().toList();
        }
        if (pdfs.isEmpty()) {
            throw new IOException("No PDFs in " + input);
        }
        Files.createDirectories(output.resolve("images"));
        Files.createDirectories(output.resolve("thumbs"));
        List<Entry> entries = new ArrayList<>();
        List<IOException> failures = new ArrayList<>();
        int pages = 0;
        for (Path pdf : pdfs) {
            try {
                int previousCount = entries.size();
                int pageCount = extractDocument(pdf, output, entries, alternate);
                pages += pageCount;
                System.out.printf("%s | %d pages | %d candidates%n",
                        pdf.getFileName(), pageCount, entries.size() - previousCount);
            } catch (IOException | RuntimeException e) {
                failures.add(new IOException(pdf.getFileName().toString(), e));
                System.err.println("Failed: " + pdf.getFileName() + ": " + e);
            }
        }
        if (!failures.isEmpty()) {
            var error = new IOException("Extraction incomplete. Indexes were not written.");
            failures.forEach(error::addSuppressed);
            throw error;
        }
        writeIndexes(output, entries);
        System.out.printf("%d PDFs | %d pages | %d candidates%n", pdfs.size(), pages, entries.size());
        return entries;
    }

    private static int extractDocument(Path pdf, Path output, List<Entry> entries, List<String> alternate)
            throws IOException {
        String filename = pdf.getFileName().toString();
        String docId = sha256(filename.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String title = documentTitle(filename, doc.getDocumentInformation().getTitle());
            var text = new PageText();
            var graphics = new PageRenderer(doc, true);
            var renderer = new PageRenderer(doc, false);
            for (int page = 0; page < doc.getNumberOfPages(); page++) {
                text.lines.clear();
                text.setStartPage(page + 1);
                text.setEndPage(page + 1);
                text.writeText(doc, Writer.nullWriter());
                List<Line> lines = text.lines;
                BufferedImage preview = graphics.render(page, 72);
                int width = preview.getWidth();
                int height = preview.getHeight();
                int rotation = doc.getPage(page).getRotation();
                List<Candidate> candidates = graphics.incomplete ? pageCandidates(lines) : locate(lines, preview, rotation);
                if (candidates.isEmpty()) {
                    continue;
                }
                String printed = printedPage(lines, height);
                BufferedImage rendered = graphics.incomplete ? null : renderer.render(page, DPI);
                if (rendered == null || renderer.incomplete) {
                    if (alternate.isEmpty()) {
                        System.err.printf("Skipped incomplete page: %s | %d | configure the alternate renderer%n",
                                filename, page + 1);
                        continue;
                    }
                    rendered = renderAlternate(alternate, pdf, page + 1);
                    candidates = pageCandidates(lines);
                    System.out.printf("Alternate full-page render: %s | %d%n", filename, page + 1);
                }
                for (int index = 0; index < candidates.size(); index++) {
                    Candidate candidate = candidates.get(index);
                    String id = String.format(Locale.ROOT, "%s-p%04d-%02d", docId, page + 1, index + 1);
                    String file = "images/" + id + ".png";
                    BufferedImage figure = crop(rendered, candidate.crop());
                    Path target = output.resolve(file);
                    savePng(figure, target, DPI);
                    savePng(thumbnail(figure), output.resolve("thumbs/" + id + ".png"), 0);
                    entries.add(new Entry(id, filename, title, page + 1, printed, candidate.caption(),
                            file, figure.getWidth(), figure.getHeight(), sha256(Files.readAllBytes(target)),
                            licence(filename), candidate.method(), candidate.confidence()));
                }
            }
            return doc.getNumberOfPages();
        }
    }

    private static List<Candidate> pageCandidates(List<Line> lines) {
        List<Caption> captions = captions(lines);
        return captions.isEmpty() ? List.of(new Candidate("", null))
                : captions.stream().map(c -> new Candidate(c.text(), null)).toList();
    }

    private static BufferedImage renderAlternate(List<String> alternate, Path pdf, int page) throws IOException {
        var command = new ArrayList<>(alternate);
        command.add(pdf.toAbsolutePath().toString());
        command.add(Integer.toString(page));
        command.add(Integer.toString(DPI));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var data = process.getInputStream()) {
            BufferedImage image = ImageIO.read(data);
            data.transferTo(OutputStream.nullOutputStream());
            if (process.waitFor() != 0 || image == null) {
                throw new IOException("Alternate renderer failed on page " + page);
            }
            return image;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for the alternate renderer", e);
        } finally {
            process.destroy();
        }
    }

    static String licence(String filename) {
        return AGENCY.matcher(filename).matches() || FOREST_SERVICE.contains(filename) ? PUBLIC_DOMAIN : "check";
    }

    static String documentTitle(String filename, String metadataTitle) {
        String title = COVER_TITLES.get(filename);
        if (title != null) {
            return title;
        }
        if (metadataTitle != null && !metadataTitle.isBlank()
                && !PLACEHOLDER_TITLE.matcher(metadataTitle.strip()).matches()) {
            return metadataTitle.strip();
        }
        return filename.substring(0, filename.length() - 4);
    }

    static boolean startsCaption(String text) {
        return FIGURE.matcher(text).find() && !LEADER.matcher(text).find()
                && !text.matches(".*[.\\u2026·]{3,}$");
    }

    static List<Caption> captions(List<Line> lines) {
        List<Caption> captions = new ArrayList<>();
        if (lines.stream().limit(8).anyMatch(line -> line.text().matches(
                "(?i)(?:list of figures|figures|table of contents|contents)"))) {
            return captions;
        }
        for (int i = 0; i < lines.size(); i++) {
            Line first = lines.get(i);
            if (!startsCaption(first.text())) {
                continue;
            }
            String text = first.text();
            Box bounds = first.bounds();
            // Match typography and alignment when the caption wraps. Keep the source line breaks.
            for (int j = i + 1; j < lines.size(); j++) {
                Line next = lines.get(j);
                Box box = next.bounds();
                int gap = box.top - bounds.bottom;
                boolean aligned = Math.abs(box.left - first.bounds().left) <= 5
                        || Math.abs(box.centerX() - first.bounds().centerX()) <= 7;
                if (gap < 0 || gap > first.fontSize() || !aligned
                        || first.fontSize() != next.fontSize() || first.bold() != next.bold()
                        || box.width() > first.bounds().width() + 8 || startsCaption(next.text())
                        || PRINTED_PAGE.matcher(next.text()).matches() || LEADER.matcher(next.text()).find()
                        || next.text().matches("^\\s*(?:[A-Z]?\\d+(?:-\\d+)*\\.|[•*])\\s.*")
                        || text.matches("(?s).*[.!?]$")) {
                    break;
                }
                text += "\n" + next.text();
                bounds = bounds.union(box);
                i = j;
            }
            captions.add(new Caption(text, bounds));
        }
        return captions;
    }

    static String printedPage(List<Line> lines, int height) {
        Set<String> candidates = new HashSet<>();
        for (Line line : lines) {
            if (line.bounds().top < 48 || line.bounds().bottom > height - 52) {
                var match = PRINTED_PAGE.matcher(line.text());
                if (match.matches()) {
                    candidates.add(match.group(1));
                }
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    static List<Candidate> locate(List<Line> lines, BufferedImage preview, int rotation) {
        int width = preview.getWidth();
        int height = preview.getHeight();
        List<Caption> captions = captions(lines);
        List<Box> bands = new ArrayList<>();
        for (Box band : graphicBands(preview)) {
            List<Caption> neighbors = captions.stream()
                    .filter(caption -> adjacent(caption.bounds(), band, lines, height)).toList();
            // Two captions side by side can share a row while identifying distinct panels.
            boolean sharedRow = neighbors.stream().anyMatch(first -> neighbors.stream().anyMatch(second ->
                    first != second && first.bounds().overlapX(second.bounds()) < 0
                    && Math.abs(first.bounds().top - second.bounds().top) <= 12));
            if (rotation == 0 && sharedRow) {
                List<Box> panels = graphicColumns(preview, band);
                boolean separated = panels.size() == neighbors.size()
                        && panels.stream().allMatch(panel -> neighbors.stream()
                                .filter(c -> adjacent(c.bounds(), panel, lines, height)).count() == 1)
                        && neighbors.stream().allMatch(c -> panels.stream()
                                .filter(panel -> adjacent(c.bounds(), panel, lines, height)).count() == 1);
                if (separated) {
                    bands.addAll(panels);
                    continue;
                }
            }
            bands.add(band);
        }
        List<List<Box>> possible = new ArrayList<>();
        Map<Box, Integer> claims = new HashMap<>();
        for (Caption caption : captions) {
            List<Box> nearby = new ArrayList<>();
            if (rotation == 0) {
                for (Box band : bands) {
                    if (adjacent(caption.bounds(), band, lines, height)) {
                        nearby.add(band);
                        claims.merge(band, 1, Integer::sum);
                    }
                }
            }
            possible.add(nearby);
        }
        List<Candidate> found = new ArrayList<>();
        for (int i = 0; i < captions.size(); i++) {
            List<Box> nearby = possible.get(i);
            Box crop = null;
            if (nearby.size() == 1 && claims.get(nearby.getFirst()) == 1) {
                crop = figureBounds(nearby.getFirst(), captions.get(i).bounds(), lines, width, height);
            }
            found.add(new Candidate(captions.get(i).text(), crop));
        }
        if (rotation != 0 && !found.isEmpty()) {
            return found;
        }
        for (Box band : bands) {
            if (claims.containsKey(band)) {
                continue;
            }
            if (pageSpanning(band, width, height)) {
                if (found.isEmpty()) {
                    found.add(new Candidate("", null));
                }
                continue;
            }
            // Uncaptioned candidates need stronger visual evidence than a nearby caption provides.
            if (band.width() >= 58 && band.height() >= 44
                    && lines.stream().filter(line -> line.bounds().width() > 70 && band.contains(line.bounds()))
                            .limit(6).count() < 6) {
                found.add(new Candidate("", band.pad(5, width, height)));
            }
        }
        // An infographic can mix artwork and body text across several full-width bands.
        if (found.isEmpty() && bands.size() >= 2 && bands.getFirst().top <= 12
                && bands.getLast().bottom >= height - 12
                && bands.stream().filter(band -> band.width() >= width * 0.9)
                        .mapToInt(Box::height).sum() >= height * 0.7) {
            found.add(new Candidate("", null));
        }
        return found;
    }

    private static boolean pageSpanning(Box band, int width, int height) {
        return band.width() >= width * 0.91 && band.height() >= height * 0.84;
    }

    private static boolean adjacent(Box caption, Box band, List<Line> lines, int height) {
        boolean above = band.bottom <= caption.top + 2;
        boolean below = band.top >= caption.bottom - 2;
        if (!above && !below || band.height() > height * 0.78 || caption.overlapX(band) <= 0) {
            return false;
        }
        int gap = above ? caption.top - band.bottom : band.top - caption.bottom;
        if (gap > MAX_CAPTION_GAP) {
            return false;
        }
        int left = Math.min(caption.left, band.left);
        int right = Math.max(caption.right, band.right);
        Box between = new Box(left, above ? band.bottom : caption.bottom,
                right, above ? caption.top : band.top);
        for (Line line : lines) {
            Box box = line.bounds();
            if (between.contains(box) && (box.width() > 95 || startsCaption(line.text()))) {
                return false;
            }
        }
        return true;
    }

    private static Box figureBounds(Box band, Box caption, List<Line> lines, int width, int height) {
        Box bounds = band;
        Box labels = band.pad(12, width, height);
        for (Line line : lines) {
            Box box = line.bounds();
            if (caption.contains(box)) {
                continue;
            }
            if (box.top < band.bottom && box.bottom > band.top && box.overlapX(band) > 0
                    && !labels.contains(box)) {
                return null; // The graphic overlaps text that extends beyond its apparent panel.
            }
            if (labels.contains(box) && !startsCaption(line.text())) {
                bounds = bounds.union(box);
            }
        }
        Box padded = bounds.pad(5, width, height);
        if (band.top < caption.top) {
            return new Box(padded.left, padded.top, padded.right, Math.min(padded.bottom, caption.top));
        }
        return new Box(padded.left, Math.max(padded.top, caption.bottom + 2), padded.right, padded.bottom);
    }

    // Use actual painted graphics instead of PDF image object boundaries or the count of vector paths.
    // At 72 dpi, the raster coordinates are in displayed page points.
    static List<Box> graphicBands(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        List<Box> bands = new ArrayList<>();
        Box band = null;
        for (int y = 0; y < height; y++) {
            int left = width;
            int right = 0;
            for (int x = 0; x < width; x++) {
                int pixel = rgb[y * width + x];
                int darkest = Math.min(pixel & 255, Math.min(pixel >> 8 & 255, pixel >> 16 & 255));
                if (darkest < 225) {
                    left = Math.min(x, left);
                    right = x + 1;
                }
            }
            if (right <= left) {
                continue;
            }
            Box row = new Box(left, y, right, y + 1);
            if (band != null && y - band.bottom <= MAX_BAND_GAP) {
                band = band.union(row);
            } else {
                addBand(bands, band);
                band = row;
            }
        }
        addBand(bands, band);
        return bands;
    }

    private static List<Box> graphicColumns(BufferedImage image, Box band) {
        int width = image.getWidth();
        int[] rgb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        List<Box> panels = new ArrayList<>();
        Box panel = null;
        for (int x = band.left; x < band.right; x++) {
            int top = band.bottom;
            int bottom = band.top;
            for (int y = band.top; y < band.bottom; y++) {
                int pixel = rgb[y * width + x];
                if (Math.min(pixel & 255, Math.min(pixel >> 8 & 255, pixel >> 16 & 255)) < 225) {
                    top = Math.min(y, top);
                    bottom = y + 1;
                }
            }
            if (bottom <= top) {
                continue;
            }
            Box column = new Box(x, top, x + 1, bottom);
            if (panel != null && x - panel.right < 9) {
                panel = panel.union(column);
            } else {
                addBand(panels, panel);
                panel = column;
            }
        }
        addBand(panels, panel);
        return panels;
    }

    private static void addBand(List<Box> bands, Box band) {
        if (band != null && band.width() >= 28 && band.height() >= 18) {
            bands.add(band);
        }
    }

    private static final class PageRenderer extends PDFRenderer {
        private static final Logger SAMPLED_IMAGES =
                Logger.getLogger("org.apache.pdfbox.pdmodel.graphics.image.SampledImageReader");
        private final boolean graphicsOnly;
        private boolean incomplete;
        // PDFBox can return a partial image for a truncated 1-bit stream without throwing.
        private final Handler partialImage = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains("premature EOF")) {
                    incomplete = true;
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        PageRenderer(PDDocument doc, boolean graphicsOnly) {
            super(doc);
            this.graphicsOnly = graphicsOnly;
            setSubsamplingAllowed(true);
            setAnnotationsFilter(annotation -> false);
        }

        BufferedImage render(int page, int dpi) throws IOException {
            incomplete = false;
            SAMPLED_IMAGES.addHandler(partialImage);
            try {
                return renderImageWithDPI(page, dpi, ImageType.RGB);
            } finally {
                SAMPLED_IMAGES.removeHandler(partialImage);
            }
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                @Override
                protected void operatorException(Operator operator, List<COSBase> operands, IOException error)
                        throws IOException {
                    incomplete = true;
                    super.operatorException(operator, operands, error);
                }

                @Override
                protected void showFontGlyph(Matrix matrix, PDFont font, int code, Vector displacement)
                        throws IOException {
                    if (!graphicsOnly || getGraphicsState().getTextState().getRenderingMode().isClip()) {
                        super.showFontGlyph(matrix, font, code, displacement);
                    }
                }

                @Override
                protected void showType3Glyph(Matrix matrix, PDType3Font font, int code, Vector displacement)
                        throws IOException {
                    // Type 3 glyphs can paint paths and images as part of ordinary text.
                    if (!graphicsOnly) {
                        super.showType3Glyph(matrix, font, code, displacement);
                    }
                }
            };
        }
    }

    private static final class PageText extends PDFTextStripper {
        private final List<Line> lines = new ArrayList<>();
        private final StringBuilder line = new StringBuilder();
        private Box bounds;
        private int fontSize;
        private boolean bold;
        private boolean space;

        PageText() {
            setSortByPosition(true);
            setShouldSeparateByBeads(false);
        }

        @Override
        protected void writeString(String word, List<TextPosition> positions) {
            if (positions.isEmpty() || word.isBlank()) {
                return;
            }
            // PDFBox may pass an entire text run as one word when it contains explicit spaces.
            // Split across large geometric gaps, even when they are inside such a run.
            int start = 0;
            for (int i = 1; i < positions.size(); i++) {
                TextPosition previous = positions.get(i - 1);
                if (positions.get(i).getX() - previous.getX() - previous.getWidth() > 26) {
                    addRun(positions.subList(start, i));
                    finishLine();
                    start = i;
                }
            }
            if (start == 0) {
                addText(word, positions);
            } else {
                addRun(positions.subList(start, positions.size()));
            }
        }

        private void addRun(List<TextPosition> positions) {
            var original = new StringBuilder();
            positions.forEach(position -> original.append(position.getUnicode()));
            if (!original.toString().isBlank()) {
                addText(original.toString(), positions);
            }
        }

        private void addText(String word, List<TextPosition> positions) {
            Box wordBounds = null;
            for (TextPosition position : positions) {
                float x = position.getX();
                float y = position.getY();
                Box glyph = new Box((int) Math.floor(x), (int) Math.floor(y - position.getHeight()),
                        (int) Math.ceil(x + position.getWidth()), (int) Math.ceil(y));
                wordBounds = wordBounds == null ? glyph : wordBounds.union(glyph);
            }
            if (bounds != null && wordBounds.left - bounds.right > 26) {
                finishLine();
            }
            if (bounds == null) {
                fontSize = Math.round(positions.getFirst().getFontSizeInPt());
                String name = positions.getFirst().getFont().getName();
                bold = name != null && name.toLowerCase(Locale.ROOT).contains("bold");
                bounds = wordBounds;
            } else {
                bounds = bounds.union(wordBounds);
                if (space) {
                    line.append(' ');
                }
            }
            line.append(word);
            space = false;
        }

        @Override
        protected void writeWordSeparator() {
            space = true;
        }

        @Override
        protected void writeLineSeparator() {
            finishLine();
        }

        @Override
        protected void endPage(PDPage page) {
            finishLine();
        }

        private void finishLine() {
            if (bounds != null && !line.toString().isBlank()) {
                lines.add(new Line(line.toString().strip(), bounds, fontSize, bold));
            }
            line.setLength(0);
            bounds = null;
            space = false;
        }
    }

    private static BufferedImage crop(BufferedImage rendered, Box box) {
        if (box == null) {
            return rendered;
        }
        double scale = DPI / 72.0;
        int x = (int) Math.floor(box.left * scale);
        int y = (int) Math.floor(box.top * scale);
        int right = Math.min(rendered.getWidth(), (int) Math.ceil(box.right * scale));
        int bottom = Math.min(rendered.getHeight(), (int) Math.ceil(box.bottom * scale));
        return rendered.getSubimage(x, y, right - x, bottom - y);
    }

    private static BufferedImage thumbnail(BufferedImage image) {
        double scale = Math.min(256.0 / image.getWidth(), 184.0 / image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        var thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return thumbnail;
    }

    private static void savePng(BufferedImage image, Path file, int dpi) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var stream = Files.newOutputStream(file); var output = ImageIO.createImageOutputStream(stream)) {
            writer.setOutput(output);
            var params = writer.getDefaultWriteParam();
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), params);
            if (dpi > 0) {
                String format = "javax_imageio_png_1.0";
                var root = new IIOMetadataNode(format);
                var density = new IIOMetadataNode("pHYs");
                String pixelsPerMeter = Long.toString(Math.round(dpi / 0.0254));
                density.setAttribute("pixelsPerUnitXAxis", pixelsPerMeter);
                density.setAttribute("pixelsPerUnitYAxis", pixelsPerMeter);
                density.setAttribute("unitSpecifier", "meter");
                root.appendChild(density);
                metadata.mergeTree(format, root);
            }
            writer.write(null, new IIOImage(image, null, metadata), params);
        } finally {
            writer.dispose();
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeIndexes(Path output, List<Entry> entries) throws IOException {
        try (var json = Files.newBufferedWriter(output.resolve("index.json"), StandardCharsets.UTF_8);
             var html = Files.newBufferedWriter(output.resolve("index.html"), StandardCharsets.UTF_8)) {
            json.write("[\n");
            html.write("""
                    <!doctype html>
                    <html lang="en"><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>BASTION figure contact sheet</title>
                    <style>
                    body{margin:2rem auto;max-width:95rem;padding:0 1rem;background:#f4f5f1;color:#1e2721;
                         font:1rem/1.5 system-ui,sans-serif}
                    nav ul{columns:16rem;padding-left:1.3rem}a{color:#23563f}
                    h2{overflow-wrap:anywhere}.grid{display:grid;gap:1rem;
                         grid-template-columns:repeat(auto-fill,minmax(min(100%,17rem),1fr))}
                    figure{margin:0;background:white;border:1px solid #cdd4cb;padding:.75rem}
                    figure img{width:100%;height:11.5rem;object-fit:contain}
                    figcaption{white-space:pre-line;overflow-wrap:anywhere}small{display:block;color:#47524a}
                    </style>
                    <main><h1>BASTION figure contact sheet</h1>
                    <p>Open a thumbnail for the 200 dpi PNG. Check the figure against the source PDF before use.</p>
                    <nav aria-label="Source documents"><ul>
                    """);
            String doc = null;
            for (Entry entry : entries) {
                if (!entry.doc().equals(doc)) {
                    doc = entry.doc();
                    html.write("<li><a href=\"#" + documentAnchor(entry) + "\">" + html(doc) + "</a></li>\n");
                }
            }
            html.write("</ul></nav>\n");
            doc = null;
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (i > 0) {
                    json.write(",\n");
                }
                json.write("""
                          {"id":%s,"doc":%s,"docTitle":%s,"pdfPage":%d,"printedPage":%s,"caption":%s,"file":%s,\
                        "widthPx":%d,"heightPx":%d,"sha256":%s,"licence":%s,"method":%s,"confidence":%s}"""
                        .formatted(json(e.id()), json(e.doc()), json(e.title()), e.page(), json(e.printed()),
                                json(e.caption()), json(e.file()), e.width(), e.height(), json(e.hash()),
                                json(e.licence()), json(e.method()), json(e.confidence())));
                if (!e.doc().equals(doc)) {
                    if (doc != null) {
                        html.write("</div></section>\n");
                    }
                    doc = e.doc();
                    html.write("<section id=\"" + documentAnchor(e) + "\"><h2>" + html(e.title())
                            + "</h2><p>" + html(doc) + "</p><div class=\"grid\">\n");
                }
                String caption = e.caption().isEmpty() ? "No caption in the text layer" : e.caption();
                String page = "PDF page " + e.page() + (e.printed() == null ? "" : " · printed " + e.printed());
                html.write("<figure id=\"" + e.id() + "\"><a href=\"" + e.file() + "\" aria-label=\"Open PNG, "
                        + html(page) + "\"><img loading=\"lazy\" alt=\"\" src=\"thumbs/" + e.id()
                        + ".png\"></a><figcaption>" + html(caption) + "<small>" + html(page) + " · "
                        + e.method() + " · " + e.confidence() + " confidence</small></figcaption></figure>\n");
            }
            if (doc != null) {
                html.write("</div></section>\n");
            }
            html.write("</main></html>\n");
            json.write("\n]\n");
        }
    }

    private static String documentAnchor(Entry entry) {
        return "doc-" + entry.id().substring(0, 16);
    }

    private static String json(String text) {
        if (text == null) {
            return "null";
        }
        var escaped = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (c < 32) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
