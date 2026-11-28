// java tools/map-verify.java <pack.bmap> [more.bmap ...] (Java 17+, offline).
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

class MapVerify {
    private static final int TILE = 256;
    private static final long MAX_DEM_CELLS = 12_000_000;
    private static final long LOCAL_EDGE_MASK = (1L << 48) - 1;
    private static final String INDEX = "(0|[1-9]\\d*)";
    private static final Pattern XY = Pattern.compile(INDEX + "/" + INDEX + "\\.(geojson|bin)");
    private static final Pattern TILE_FILE = Pattern.compile(INDEX + "/" + INDEX + "/" + INDEX + "\\.([a-z0-9]+)");

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java tools/map-verify.java <pack.bmap> [more.bmap ...]");
            System.exit(2);
        }
        var verifier = new MapVerify();
        try {
            int status = verifier.run(args);
            if (status != 0) System.exit(status);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private final List<Pack> packs = new ArrayList<>();
    private int errors;
    private long shared, crossMismatches, internalMismatches;

    private int run(String[] args) throws IOException {
        ImageIO.setUseCache(false);
        try (var index = new JunctionIndex()) {
            var seen = new HashSet<Path>();
            for (String arg : args) {
                try {
                    var pack = new Pack(Path.of(arg), packs.size());
                    if (!seen.add(pack.path.toRealPath())) {
                        error(pack.path.toString(), "pack was already supplied");
                        continue;
                    }
                    packs.add(pack);
                    readPack(pack, index);
                } catch (IOException | IllegalArgumentException e) {
                    error(arg, e.getMessage());
                }
            }
            index.check(this::checkJunctions);
        }
        for (Pack pack : packs) pack.print();
        var byGrid = new TreeMap<Integer, Long>();
        for (Pack pack : packs) {
            if (pack.roadGrid >= 0 && pack.roadCells > 0) byGrid.merge(pack.roadGrid, 1L, Long::sum);
        }
        for (Pack pack : packs) {
            if (pack.invalidJoinedId != 0 && byGrid.getOrDefault(pack.roadGrid, 0L) > 1) {
                error(pack.path + "!" + pack.invalidJoinedCell, "joined road id out of range: " + pack.invalidJoinedId);
            }
        }
        System.out.printf(Locale.ROOT,
            "CROSS-PACK road packs by grid=%s, shared junctions=%d, junction mismatches=%d, internal mismatches=%d%n",
            byGrid, shared, crossMismatches, internalMismatches);
        System.out.printf("RESULT %s, %d error(s)%n", errors == 0 ? "OK" : "FAILED", errors);
        return errors == 0 ? 0 : 1;
    }

    private void error(String where, String message) {
        errors++;
        System.err.printf("ERROR %s: %s%n", where, message == null ? "unexpected end of input" : message);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static final class Pack {
        final Path path;
        final int ordinal;
        String name, kind;
        Bounds bounds;
        int roadGrid = -1;
        long invalidJoinedId;
        String invalidJoinedCell;
        long roadCells, roadNodes, uniqueNodes, roadEdges, vectorFeatures, lineFeatures, elevationTiles, extras, alternateImages;
        final Map<String, Raster> rasters = new LinkedHashMap<>();
        final Map<String, Layer> layers = new LinkedHashMap<>();
        final Map<String, Layer> wholeFiles = new HashMap<>();

        Pack(Path path, int ordinal) {
            this.path = path;
            this.ordinal = ordinal;
        }

        void print() {
            if (bounds == null) return;
            System.out.printf(Locale.ROOT, "PACK %s (%s) %s bbox=%s%n", name, kind, path, bounds);
            for (var item : rasters.entrySet()) {
                System.out.printf("  %s: %s tiles%n", item.getKey(), item.getValue().tileCounts);
            }
            for (Layer layer : layers.values()) {
                if (layer.enabled()) {
                    System.out.printf("  %s (%s): %d %s%n", layer.id, layer.kind, layer.observed,
                        layer.kind.equals("roadgraph") ? "cells" : "source features");
                }
            }
            System.out.printf(Locale.ROOT,
                "  roadCells=%d roadNodes=%d uniqueNodes=%d roadEdges=%d vectorFeatures=%d lineFeatures=%d elevationTiles=%d%n",
                roadCells, roadNodes, uniqueNodes, roadEdges, vectorFeatures, lineFeatures, elevationTiles);
            if (extras > 0) System.out.printf("  Other readable entries=%d%n", extras);
            if (alternateImages > 0) {
                System.out.printf("  Decodable raster tiles with different byte format than their filename=%d%n", alternateImages);
            }
        }
    }

    private record Bounds(double west, double south, double east, double north) {
        @Override public String toString() { return "[" + west + ", " + south + ", " + east + ", " + north + "]"; }
    }

    private record Raster(int minZoom, int maxZoom, String extension, Map<Integer, Long> tileCounts) {}

    private static final class Layer {
        final String id, kind, file;
        final int grid;
        final long count;
        long observed, files;

        Layer(String id, String kind, String file, int grid, long count) {
            this.id = id;
            this.kind = kind;
            this.file = file;
            this.grid = grid;
            this.count = count;
        }

        boolean enabled() { return count != 0; }
    }

    private void readPack(Pack pack, JunctionIndex index) throws IOException {
        try (var zip = new ZipFile(pack.path.toFile())) {
            var entries = new LinkedHashMap<String, ZipEntry>();
            for (var it = zip.entries(); it.hasMoreElements();) {
                var entry = it.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.endsWith("/")) continue;
                if (!safeName(name) || entries.putIfAbsent(name, entry) != null) {
                    error(pack.path + "!" + name, "unsafe or duplicate ZIP entry name");
                }
            }
            ZipEntry header = entries.remove("pack.json");
            require(header != null, "missing pack.json");
            require(header.getSize() <= 1_000_000, "pack.json exceeds the Android header limit");
            readEntry(zip, header, in -> {
                readHeader(pack, Json.parse(in));
                return null;
            });
            for (var item : entries.entrySet()) {
                String path = item.getKey();
                try {
                    readEntry(zip, item.getValue(), in -> readLayer(pack, path, item.getValue().getSize(), in, index));
                } catch (IOException | IllegalArgumentException e) {
                    error(pack.path + "!" + path, e.getMessage());
                }
            }
            for (var item : pack.rasters.entrySet()) {
                Raster raster = item.getValue();
                for (int zoom = raster.minZoom; zoom <= raster.maxZoom; zoom++) {
                    if (!raster.tileCounts.containsKey(zoom)) error(pack.path.toString(), "missing " + item.getKey() + " tiles at zoom " + zoom);
                }
            }
            for (Layer layer : pack.layers.values()) {
                if (!layer.enabled()) continue;
                if (layer.files == 0) error(pack.path.toString(), "missing declared layer " + layer.id);
                else if (layer.count >= 0 && layer.observed != layer.count) {
                    error(pack.path + "!" + layer.id, "declared count " + layer.count + " differs from " + layer.observed);
                }
            }
        }
    }

    private static boolean safeName(String name) {
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0) return false;
        }
        return true;
    }

    private static void readHeader(Pack pack, Object json) throws IOException {
        var info = object(json, "pack.json");
        long format = whole(info.get("format"), "format");
        require(format == 1 || format == 2, "unsupported pack format " + format);
        pack.name = text(info.getOrDefault("name", pack.path.getFileName().toString()), "name");
        String defaultKind = pack.name.endsWith("-overview") ? "overview" : pack.name.startsWith("state-") ? "state" : "local";
        pack.kind = text(info.getOrDefault("kind", defaultKind), "kind");
        var box = array(info.get("bbox"), "bbox");
        require(box.size() == 4, "bbox must contain west, south, east and north");
        double w = number(box.get(0), "west"), s = number(box.get(1), "south");
        double e = number(box.get(2), "east"), n = number(box.get(3), "north");
        require(-180 <= w && w < e && e <= 180 && -90 <= s && s < n && n <= 90, "invalid bbox");
        pack.bounds = new Bounds(w, s, e, n);
        for (String key : List.of("topo", "land", "sat", "elev")) {
            if (!info.containsKey(key)) continue;
            var data = object(info.get(key), key);
            boolean elev = key.equals("elev");
            int min = zoom(data.get(elev ? "zoom" : "minZoom"));
            int max = elev ? min : zoom(data.get("maxZoom"));
            require(max >= min, "invalid zoom range for " + key);
            String ext = text(data.getOrDefault("ext", "png"), key + " extension");
            require(ext.matches("[a-z0-9]+"), "invalid extension for " + key);
            if (elev) {
                require("terrarium".equals(data.getOrDefault("encoding", "terrarium")), "unsupported elevation encoding");
                require(tileX(e, max) * TILE <= Integer.MAX_VALUE &&
                        tileY(s, max) * TILE <= Integer.MAX_VALUE, "elevation pixel origin exceeds Android Int range");
            }
            pack.rasters.put(key, new Raster(min, max, ext, new TreeMap<>()));
        }
        for (Object value : array(info.getOrDefault("layers", List.of()), "layers")) {
            var data = object(value, "layer");
            String id = text(data.get("id"), "layer id");
            require(!id.contains("/") && !id.contains("\\") && safeName(id), "unsafe layer id");
            String kind = text(data.getOrDefault("kind", "line"), id + " kind");
            String file = data.containsKey("file") ? text(data.get("file"), id + " file") : "";
            int grid = data.containsKey("grid") ? zoom(data.get("grid")) : -1;
            long count = data.containsKey("count") ? whole(data.get("count"), id + " count") : -1;
            require(!data.containsKey("count") || count >= 0, "negative feature count for " + id);
            var layer = new Layer(id, kind, file, grid, count);
            require(pack.layers.putIfAbsent(id, layer) == null, "duplicate layer id " + id);
            if (!layer.enabled()) continue;
            if (kind.equals("roadgraph")) {
                require(pack.roadGrid == -1 && id.equals("streets") && grid >= 0, "invalid streets roadgraph declaration");
                require(data.getOrDefault("complete", true) instanceof Boolean, "invalid road complete flag");
                if (Boolean.FALSE.equals(data.get("complete"))) {
                    System.out.println("NOTE " + pack.path + ": road layer declares incomplete coverage");
                }
                pack.roadGrid = grid;
            } else if (grid == -1) {
                require(!file.contains("\\") && safeName(file) && !file.equals("pack.json"), "invalid file for layer " + id);
                require(pack.wholeFiles.putIfAbsent(file, layer) == null, "duplicate layer file " + file);
            }
        }
    }

    @FunctionalInterface
    private interface FinishEntry { void accept() throws IOException; }

    @FunctionalInterface
    private interface ReadEntry { FinishEntry read(DataInputStream input) throws IOException; }

    private static void readEntry(ZipFile file, ZipEntry entry, ReadEntry action) throws IOException {
        var crc = new CRC32();
        try (var checked = new CheckedInputStream(file.getInputStream(entry), crc);
             var data = new DataInputStream(new BufferedInputStream(checked))) {
            FinishEntry finish = action.read(data);
            data.transferTo(OutputStream.nullOutputStream());
            require(crc.getValue() == entry.getCrc(), "ZIP entry CRC mismatch");
            if (finish != null) finish.accept();
        }
    }

    private static FinishEntry readLayer(Pack pack, String path, long size, DataInputStream in, JunctionIndex index)
            throws IOException {
        int separator = path.indexOf('/');
        if (separator >= 0) {
            String kind = path.substring(0, separator);
            Raster raster = pack.rasters.get(kind);
            if (raster != null) return readTile(pack, kind, raster, path.substring(separator + 1), in);
        }

        Layer layer = pack.wholeFiles.get(path);
        if (layer == null && path.startsWith("layers/")) {
            int next = path.indexOf('/', "layers/".length());
            if (next >= 0) {
                var candidate = pack.layers.get(path.substring("layers/".length(), next));
                if (candidate != null && candidate.enabled() && candidate.grid >= 0) {
                    layer = candidate;
                    var cell = XY.matcher(path.substring(next + 1));
                    String extension = layer.kind.equals("roadgraph") ? "bin" : "geojson";
                    require(cell.matches() && cell.group(3).equals(extension), "invalid " + layer.id + " cell path");
                    checkTile(Integer.parseInt(cell.group(1)), Integer.parseInt(cell.group(2)), layer.grid);
                }
            }
        }
        if (layer == null) {
            require(!(path.startsWith("layers/streets/") && path.endsWith(".bin")), "missing enabled streets declaration");
            return () -> pack.extras++;
        }
        Layer found = layer;
        if (layer.kind.equals("roadgraph")) {
            RoadCell road = readRoad(in, size);
            return () -> {
                index.add(pack, road);
                found.files++;
                found.observed++;
                pack.roadCells++;
                pack.roadNodes += road.ids.length;
                pack.roadEdges += road.edges;
                if (pack.invalidJoinedId == 0 && road.invalidJoinedId != 0) {
                    pack.invalidJoinedId = road.invalidJoinedId;
                    pack.invalidJoinedCell = path;
                }
            };
        }

        var json = object(Json.parse(in), "GeoJSON root");
        require("FeatureCollection".equals(json.get("type")), "expected GeoJSON FeatureCollection");
        var features = array(json.get("features"), "GeoJSON features");
        long lines = 0;
        for (Object feature : features) {
            var geometry = object(object(feature, "feature").get("geometry"), "geometry");
            lines += geometry(text(geometry.get("type"), "geometry type"), geometry.get("coordinates"));
        }
        long count = lines;
        return () -> {
            found.files++;
            found.observed += features.size();
            pack.vectorFeatures += features.size();
            pack.lineFeatures += count;
        };
    }

    private static long geometry(String type, Object value) throws IOException {
        var coordinates = array(value, type + " coordinates");
        switch (type) {
            case "Point" -> { position(coordinates); return 0; }
            case "LineString" -> { line(coordinates); return 1; }
            case "MultiLineString", "Polygon" -> {
                for (Object ring : coordinates) line(array(ring, type + " part"));
                return coordinates.size();
            }
            case "MultiPolygon" -> {
                long count = 0;
                for (Object part : coordinates) count += geometry("Polygon", part);
                return count;
            }
            default -> throw new IOException("unsupported geometry: " + type);
        }
    }

    private static void line(List<?> points) throws IOException {
        require(points.size() >= 2, "line with fewer than two positions");
        for (Object point : points) position(array(point, "position"));
    }

    private static void position(List<?> point) throws IOException {
        require(point.size() >= 2, "position missing longitude or latitude");
        double lon = number(point.get(0), "longitude"), lat = number(point.get(1), "latitude");
        require(lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90, "position outside longitude/latitude range");
    }

    private static FinishEntry readTile(Pack pack, String kind, Raster raster, String name, DataInputStream in)
            throws IOException {
        var file = TILE_FILE.matcher(name);
        require(file.matches() && file.group(4).equals(raster.extension), "invalid " + kind + " tile path");
        int z = Integer.parseInt(file.group(1)), x = Integer.parseInt(file.group(2)), y = Integer.parseInt(file.group(3));
        require(z >= raster.minZoom && z <= raster.maxZoom, "tile zoom outside declared range");
        checkTile(x, y, z);
        boolean elevation = kind.equals("elev");
        in.mark(16);
        String encoding = imageFormat(in);
        String expected = raster.extension.equals("jpeg") ? "jpg" : raster.extension;
        boolean alternate = !encoding.equals(expected);
        in.reset();
        if (elevation) {
            require(!alternate, "elevation image encoding differs from its declared extension");
            var bounds = pack.bounds;
            long west = tileX(bounds.west, z), east = tileX(bounds.east, z);
            long north = tileY(bounds.north, z), south = tileY(bounds.south, z);
            require(x >= west && x <= east && y >= north && y <= south, "elevation tile outside pack bbox");
            inspectImage(in, true, true);
            // The app copies rows from a tile into a clamped window. Check a window around the
            // tile without allocating a state-sized DEM or changing any of its inputs.
            long left = Math.max(west, x - 1L), top = Math.max(north, y - 1L);
            long width = (Math.min(east, x + 1L) - left + 1) * TILE;
            long height = (Math.min(south, y + 1L) - top + 1) * TILE;
            long lastPixel = ((y - top + 1) * TILE - 1) * width + (x - left + 1) * TILE - 1;
            require(lastPixel >= 0 && lastPixel < width * height && width * height <= MAX_DEM_CELLS,
                    "elevation window outside Android array bounds");
        } else {
            // Android sniffs the bytes. Verify the image header, and fully decode tiles whose
            // underlying format differs from the cached filename extension.
            inspectImage(in, alternate, false);
        }
        return () -> {
            raster.tileCounts.merge(z, 1L, Long::sum);
            if (elevation) pack.elevationTiles++;
            if (alternate) pack.alternateImages++;
        };
    }

    private static void inspectImage(DataInputStream data, boolean decode, boolean elevation) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(data)) {
            var readers = ImageIO.getImageReaders(stream);
            require(readers.hasNext(), "unreadable tile image");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                require(width > 0 && height > 0, "invalid image dimensions");
                if (elevation) require(width == TILE && height == TILE, "elevation tile must be 256x256");
                if (!decode) return;
                require((long) width * height <= MAX_DEM_CELLS, "tile decode exceeds the image pixel limit");
                BufferedImage image = reader.read(0);
                require(image != null, "tile decode failed");
                if (elevation) {
                    require(image.getColorModel().getNumColorComponents() >= 3, "elevation tile is missing Terrarium RGB channels");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static String imageFormat(DataInputStream in) throws IOException {
        byte[] signature = in.readNBytes(12);
        require(signature.length == 12, "truncated image");
        boolean png = Arrays.equals(signature, 0, 8, new byte[]{(byte) 0x89, 80, 78, 71, 13, 10, 26, 10}, 0, 8);
        boolean jpeg = (signature[0] & 0xff) == 0xff && (signature[1] & 0xff) == 0xd8 && (signature[2] & 0xff) == 0xff;
        boolean webp = new String(signature, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") &&
                new String(signature, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
        if (png) return "png";
        if (jpeg) return "jpg";
        if (webp) return "webp";
        throw new IOException("invalid image signature");
    }

    private static int zoom(Object value) throws IOException {
        long n = whole(value, "zoom");
        require(n >= 0 && n <= 30, "zoom out of range");
        return (int) n;
    }

    private static void checkTile(int x, int y, int zoom) throws IOException {
        long across = 1L << zoom;
        require(x >= 0 && y >= 0 && x < across && y < across, "tile/cell coordinate outside its world grid");
    }

    private static long tileX(double lon, int z) {
        return (long) Math.floor((lon + 180) / 360 * (1L << z));
    }

    private static long tileY(double lat, int z) {
        double r = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
        return (long) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1L << z));
    }

    private record RoadCell(long[] ids, int[] lat, int[] lon, int edges, long invalidJoinedId) {}

    private static RoadCell readRoad(DataInputStream data, long size) throws IOException {
        int version = data.readInt() - 0x42524730;  // BRG0
        require(version >= 1 && version <= 4, "unsupported road cell magic");
        int names = count(data, size / 2, "road names");
        for (int i = 0; i < names; i++) data.readUTF();
        int nodes = count(data, size / 16, "road nodes");
        var ids = new long[nodes];
        var lat = new int[nodes];
        var lon = new int[nodes];
        for (int i = 0; i < nodes; i++) {
            ids[i] = data.readLong();
            lat[i] = data.readInt();
            lon[i] = data.readInt();
            checkMicrodegrees(lat[i], lon[i]);
        }
        int edges = count(data, size / (version == 1 ? 29 : 30), "road edges");
        long invalidJoinedId = 0;
        for (int i = 0; i < edges; i++) {
            long id = data.readLong();
            invalidJoinedId = firstInvalidRoadId(invalidJoinedId, id);
            int a = data.readInt(), b = data.readInt();
            if (a < 0 || a >= nodes || b < 0 || b >= nodes) {
                throw new IOException("edge " + id + " has dangling node index (" + a + ", " + b + ")");
            }
            data.readByte(); // class
            binaryFlag(data, "oneway");
            data.readUnsignedByte(); // mph
            if (version >= 2) data.readUnsignedByte(); // flags
            int name = data.readInt();
            float length = data.readFloat();
            require(name >= -1 && name < names, "road name index out of bounds");
            require(Float.isFinite(length) && length >= 0, "invalid road length");
            int points = data.readShort();
            require(points >= 0, "negative road shape count");
            for (int j = 0; j < points; j++) checkMicrodegrees(data.readInt(), data.readInt());
        }
        if (version >= 2) {
            int turns = count(data, size / (version >= 3 ? 25 : 17), "turns");
            for (int i = 0; i < turns; i++) {
                invalidJoinedId = firstInvalidRoadId(invalidJoinedId, data.readLong()); // from
                if (version >= 3) data.readLong(); // via node may be in another record
                invalidJoinedId = firstInvalidRoadId(invalidJoinedId, data.readLong()); // to
                binaryFlag(data, "turn only");
            }
        }
        if (version == 4) {
            int paths = count(data, size / 26, "via-way paths");
            for (int i = 0; i < paths; i++) {
                invalidJoinedId = firstInvalidRoadId(invalidJoinedId, data.readLong()); // from
                int via = data.readUnsignedByte();
                require(via > 0, "via-way path has no via edge");
                for (int j = 0; j <= via; j++) { // via edges and destination can cross cells
                    invalidJoinedId = firstInvalidRoadId(invalidJoinedId, data.readLong());
                }
                binaryFlag(data, "via-way only");
            }
        }
        require(data.read() == -1, "trailing bytes in road cell");
        return new RoadCell(ids, lat, lon, edges, invalidJoinedId);
    }

    private static long firstInvalidRoadId(long known, long id) {
        return known != 0 || id >= 0 && id <= LOCAL_EDGE_MASK ? known : id;
    }

    private static int count(DataInputStream data, long maximum, String label) throws IOException {
        int value = data.readInt();
        if (value < 0 || value > maximum) throw new IOException("invalid count for " + label);
        return value;
    }

    private static void checkMicrodegrees(int lat, int lon) throws IOException {
        require(lat >= -90_000_000 && lat <= 90_000_000 && lon >= -180_000_000 && lon <= 180_000_000,
            "road position outside longitude/latitude range");
    }

    private static void binaryFlag(DataInputStream data, String label) throws IOException {
        if (data.readUnsignedByte() > 1) throw new IOException("invalid " + label + " flag");
    }

    private record Junction(int grid, long id, int pack, int lat, int lon) {
        boolean sameKey(Junction other) { return grid == other.grid && id == other.id; }
        boolean samePosition(Junction other) { return lat == other.lat && lon == other.lon; }
    }

    private void checkJunctions(List<Junction> values) {
        int start = 0;
        while (start < values.size()) {
            Junction first = values.get(start);
            int end = start + 1;
            while (end < values.size() && first.sameKey(values.get(end))) end++;
            checkJunction(values, start, end, first);
            start = end;
        }
    }

    private void checkJunction(List<Junction> values, int start, int end, Junction first) {
        int at = start, packCount = 0;
        boolean differentPosition = false;
        while (at < end) {
            Junction local = values.get(at);
            int limit = at + 1;
            while (limit < end && values.get(limit).pack == local.pack) limit++;
            packs.get(local.pack).uniqueNodes++;
            packCount++;
            differentPosition |= !first.samePosition(local) || !first.samePosition(values.get(limit - 1));
            // The positions within one pack are sorted. If the extrema differ, report the ID.
            if (!local.samePosition(values.get(limit - 1))) {
                internalMismatches++;
                error("junction " + local.id + " on grid " + local.grid + " within " + packs.get(local.pack).path,
                    "coordinate mismatch " + positions(values, at, limit));
            }
            at = limit;
        }
        if (packCount < 2) return;
        shared++;
        if (differentPosition) {
            crossMismatches++;
            error("cross-pack junction " + first.id + " on grid " + first.grid,
                "coordinate mismatch " + positions(values, start, end));
        }
    }

    private String positions(List<Junction> values, int start, int end) {
        var out = new StringJoiner(", ");
        Junction previous = null;
        for (int i = start; i < end; i++) {
            Junction value = values.get(i);
            if (!value.equals(previous)) out.add(packs.get(value.pack).path + "=(" + value.lat + "," + value.lon + ") microdegrees");
            previous = value;
        }
        return out.toString();
    }

    /** On-disk partitions keep the working set to one road cell plus one junction shard. */
    private static final class JunctionIndex implements AutoCloseable {
        private static final Comparator<Junction> ORDER = Comparator.comparingInt(Junction::grid)
            .thenComparingLong(Junction::id).thenComparingInt(Junction::pack)
            .thenComparingInt(Junction::lat).thenComparingInt(Junction::lon);
        private final Path directory = Files.createTempDirectory("bastion-map-verify-");
        private final DataOutputStream[] shards = new DataOutputStream[128];

        JunctionIndex() throws IOException {}

        private Path path(int partition) { return directory.resolve("junctions-" + partition); }

        void add(Pack pack, RoadCell cell) throws IOException {
            for (int i = 0; i < cell.ids.length; i++) {
                long id = cell.ids[i];
                long key = id ^ (id >>> 33);
                key *= 0xff51afd7ed558ccdL;
                int shard = (int) (key ^ (key >>> 32)) & (shards.length - 1);
                DataOutputStream out = shards[shard];
                if (out == null) {
                    out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path(shard))));
                    shards[shard] = out;
                }
                out.writeInt(pack.roadGrid);
                out.writeLong(id);
                out.writeInt(pack.ordinal);
                out.writeInt(cell.lat[i]);
                out.writeInt(cell.lon[i]);
            }
        }

        void check(java.util.function.Consumer<List<Junction>> consumer) throws IOException {
            for (int shard = 0; shard < shards.length; shard++) {
                if (shards[shard] == null) continue;
                shards[shard].close();
                shards[shard] = null;
                Path file = path(shard);
                long size = Files.size(file);
                require(size % 24 == 0, "truncated junction index");
                try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    var values = new ArrayList<Junction>();
                    for (long i = 0; i < size / 24; i++) {
                        values.add(new Junction(in.readInt(), in.readLong(), in.readInt(), in.readInt(), in.readInt()));
                    }
                    values.sort(ORDER);
                    consumer.accept(values);
                }
                Files.delete(file);
            }
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            for (int i = 0; i < shards.length; i++) {
                try {
                    if (shards[i] != null) shards[i].close();
                    Files.deleteIfExists(path(i));
                } catch (IOException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            Files.deleteIfExists(directory);
            if (failure != null) throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) throws IOException {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IOException("expected object for " + label);
    }

    private static List<?> array(Object value, String label) throws IOException {
        if (value instanceof List<?> list) return list;
        throw new IOException("expected array for " + label);
    }

    private static String text(Object value, String label) throws IOException {
        if (value instanceof String s && !s.isBlank()) return s;
        throw new IOException("expected text for " + label);
    }

    private static double number(Object value, String label) throws IOException {
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        throw new IOException("expected finite number for " + label);
    }

    private static long whole(Object value, String label) throws IOException {
        double n = number(value, label);
        require(n == Math.rint(n) && n >= -0x1.0p63 && n < 0x1.0p63, "expected integer for " + label);
        return (long) n;
    }

    /** A small strict JSON reader keeps the source launcher independent of Gradle and downloads. */
    private static final class Json {
        private static final Pattern NUM = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
        private final Reader in;
        private int ch;
        private long offset;

        private Json(InputStream bytes) throws IOException {
            var utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
            in = new BufferedReader(new InputStreamReader(bytes, utf8));
            ch = in.read();
            if (ch == 0xfeff) advance();
        }

        static Object parse(InputStream bytes) throws IOException {
            var parser = new Json(bytes);
            Object value = parser.value(0);
            parser.space();
            if (parser.ch != -1) throw parser.problem("trailing content");
            return value;
        }

        private void advance() throws IOException { ch = in.read(); offset++; }

        private void space() throws IOException {
            while (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') advance();
        }

        private boolean take(char want) throws IOException {
            if (ch != want) return false;
            advance();
            return true;
        }

        private void expect(char want) throws IOException {
            if (!take(want)) throw problem("expected '" + want + "'");
        }

        private IOException problem(String message) { return new IOException("invalid JSON at " + offset + ": " + message); }

        private Object value(int depth) throws IOException {
            if (depth > 128) throw problem("nesting too deep");
            space();
            return switch (ch) {
                case '{' -> objectValue(depth + 1);
                case '[' -> arrayValue(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> numeric();
            };
        }

        private Object literal(String word, Object value) throws IOException {
            for (char c : word.toCharArray()) expect(c);
            return value;
        }

        private Map<String, Object> objectValue(int depth) throws IOException {
            expect('{');
            space();
            var out = new LinkedHashMap<String, Object>();
            if (take('}')) return out;
            do {
                space();
                String key = string();
                space();
                expect(':');
                if (out.containsKey(key)) throw problem("duplicate property " + key);
                out.put(key, value(depth));
                space();
            } while (take(','));
            expect('}');
            return out;
        }

        private List<Object> arrayValue(int depth) throws IOException {
            expect('[');
            space();
            var out = new ArrayList<Object>();
            if (take(']')) return out;
            do {
                out.add(value(depth));
                space();
            } while (take(','));
            expect(']');
            return out;
        }

        private String string() throws IOException {
            expect('"');
            var out = new StringBuilder();
            while (ch != '"') {
                if (ch < 0x20) throw problem("unclosed string or unescaped control character");
                if (ch == '\\') {
                    advance();
                    out.append(escape());
                } else {
                    out.append((char) ch);
                    advance();
                }
            }
            expect('"');
            return out.toString();
        }

        private char escape() throws IOException {
            int escaped = ch;
            advance();
            return switch (escaped) {
                case '"', '\\', '/' -> (char) escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> unicode();
                default -> throw problem("unknown escape");
            };
        }

        private char unicode() throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(ch, 16);
                if (digit < 0) throw problem("invalid Unicode escape");
                value = value * 16 + digit;
                advance();
            }
            return (char) value;
        }

        private Number numeric() throws IOException {
            var out = new StringBuilder();
            while (ch >= '0' && ch <= '9' || ch == '+' || ch == '-' || ch == 'e' || ch == 'E' || ch == '.') {
                out.append((char) ch);
                advance();
            }
            String token = out.toString();
            if (!NUM.matcher(token).matches()) throw problem("invalid value");
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw problem("invalid number");
            }
        }
    }
}
