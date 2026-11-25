// Builds BASTION's offline road network from OpenStreetMap ways (Overpass XML, one file per grid cell).
// Run by map-build.ps1:  java tools/RoadGraph.java <rawDir> <outDir> <gridZoom> <x0> <y0> <x1> <y1>
//
// Output: <outDir>/<x>/<y>.bin, one per grid cell, each holding every road edge that starts or
// ends in that cell (edges are cut at junctions, so the phone can search cell by cell and never
// loads the whole state). Big-endian, read by android .../map/RoadGraph.kt:
//   "BRG3"; int nNames; nNames x UTF; int nNodes; nNodes x (long osmId, int lat1e6, int lon1e6);
//   int nEdges; nEdges x (long edgeId, int a, int b, byte class, byte oneway, byte mph, byte flags,
//                         int nameIdx, float meters, short nPts, nPts x (int lat1e6, int lon1e6));
//   int nTurns; nTurns x (long fromEdge, long viaNode, long toEdge, byte only)   -- turn restrictions at a junction in this cell
// viaNode is the OSM id of the junction the rule applies at, so a rule never restricts the other end of
// its from edge. When a way passes through the via node (two of its edges end there), only the edge pairs
// that can carry the named movement (legal direction; left / right / straight on / U-turn) are written.
// flags: 1 = 4wd only, 2 = unpaved, 4 = rough (high clearance), 8 = not passable by car (tracktype grade5, very bad surface).
// only: 0 = this turn is forbidden ("no_left_turn" etc.), 1 = from this road only this turn is allowed ("only_straight_on").
// BRG2 (turns without viaNode) and BRG1 (no flags, no turns) are still read by the phone.
// Not yet handled: restrictions whose via is a way (they are skipped and counted in the summary line).
// oneway: 0 = both ways, 1 = only a->b. Points are the shape between a and b, a and b excluded.
// The data is (c) OpenStreetMap contributors, ODbL; the pack credits it.

import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RoadGraph {
    static final String[] CLASSES = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "living_street", "service", "track", "road", "motorway_link", "trunk_link", "primary_link",
        "secondary_link", "tertiary_link"};
    // Typical US speeds (mph) when the way has no usable maxspeed.
    static final int[] DEFAULT_MPH = {65, 55, 50, 45, 35, 30, 25, 10, 10, 10, 25, 45, 40, 35, 30, 25};

    record Way(long id, int cls, int oneway, int mph, String name, long[] nodes, int flags) {}
    record Turn(long fromWay, long via, long toWay, int only, String kind) {}
    static final List<Turn> turns = new ArrayList<>();
    static int viaWayTurns = 0;

    static final Map<Long, int[]> coord = new HashMap<>();
    static final Map<Long, Way> ways = new HashMap<>();
    static final Map<Long, List<Object[]>> byWay = new HashMap<>();   // way id -> its edges

    public static void main(String[] a) throws Exception {
        Path raw = Path.of(a[0]), out = Path.of(a[1]);
        int z = Integer.parseInt(a[2]), x0 = Integer.parseInt(a[3]), y0 = Integer.parseInt(a[4]),
            x1 = Integer.parseInt(a[5]), y1 = Integer.parseInt(a[6]);
        try (var files = Files.walk(raw)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".xml")).toList()) read(p);
        }
        // A node is a junction where two ways meet or a way ends; edges run junction to junction.
        Map<Long, Integer> uses = new HashMap<>();
        for (Way w : ways.values()) for (long n : w.nodes) uses.merge(n, 1, Integer::sum);
        Map<Long, List<Object[]>> cells = new HashMap<>();   // cell key -> edges
        long edgeId = 0;
        for (Way w : ways.values()) {
            int start = 0;
            for (int i = 1; i < w.nodes.length; i++) {
                if (i < w.nodes.length - 1 && uses.get(w.nodes[i]) < 2) continue;
                long[] seg = Arrays.copyOfRange(w.nodes, start, i + 1);
                start = i;
                if (seg[0] == seg[seg.length - 1] && seg.length < 3) continue;
                double m = 0;
                for (int k = 1; k < seg.length; k++) m += meters(coord.get(seg[k - 1]), coord.get(seg[k]));
                Object[] e = {edgeId++, w, seg, (float) m};
                byWay.computeIfAbsent(w.id, k -> new ArrayList<>()).add(e);
                for (long n : new long[]{seg[0], seg[seg.length - 1]}) {
                    int[] c = coord.get(n);
                    int cx = tileX(c[1] / 1e6, z), cy = tileY(c[0] / 1e6, z);
                    if (cx < x0 || cx > x1 || cy < y0 || cy > y1) continue;
                    List<Object[]> list = cells.computeIfAbsent(((long) cx << 32) | cy, k -> new ArrayList<>());
                    if (list.isEmpty() || list.get(list.size() - 1) != e) list.add(e);
                }
            }
        }
        // Turn restrictions become (from edge, via node, to edge) movements, stored in the via node's cell.
        Map<Long, List<long[]>> cellTurns = new HashMap<>();
        int nTurns = 0;
        for (Turn t : turns) {
            int[] c = coord.get(t.via);
            if (c == null) continue;
            int cx = tileX(c[1] / 1e6, z), cy = tileY(c[0] / 1e6, z);
            for (Object[][] mv : movements(t)) {
                cellTurns.computeIfAbsent(((long) cx << 32) | cy, k -> new ArrayList<>())
                    .add(new long[]{(long) mv[0][0], t.via, (long) mv[1][0], t.only});
                nTurns++;
            }
        }
        long edges = 0;
        for (var en : cells.entrySet()) {
            int cx = (int) (en.getKey() >> 32), cy = (int) (long) en.getKey();
            Path f = out.resolve(cx + "/" + cy + ".bin");
            Files.createDirectories(f.getParent());
            write(f, en.getValue(), cellTurns.getOrDefault(en.getKey(), List.of()));
            edges += en.getValue().size();
        }
        System.out.println(ways.size() + " ways, " + edgeId + " edges, " + nTurns + " turn rules, " + cells.size() + " cells, " + edges + " edge entries"
            + (viaWayTurns > 0 ? ", " + viaWayTurns + " via-way restrictions skipped (not supported)" : ""));
    }

    /** The edges of a way that have [node] as one of their ends (two when the way passes through it). */
    static List<Object[]> endingAt(List<Object[]> es, long node) {
        List<Object[]> out = new ArrayList<>();
        if (es == null) return out;
        for (Object[] e : es) { long[] s = (long[]) e[2]; if (s[0] == node || s[s.length - 1] == node) out.add(e); }
        return out;
    }

    /**
     * The (from edge, to edge) pairs a restriction names at its via node. A way that passes through the
     * via node has an edge on each side, so pairs are kept only if they can be driven (into the via on the
     * from edge, out of it on the to edge) and, when still ambiguous, only if their turn angle matches the
     * restriction (left, right, straight on, U-turn). If no pair matches the angle, every legal pair is kept.
     */
    static List<Object[][]> movements(Turn t) {
        List<Object[]> froms = endingAt(byWay.get(t.fromWay), t.via), tos = endingAt(byWay.get(t.toWay), t.via);
        boolean uTurn = t.kind.endsWith("u_turn");
        List<Object[][]> all = new ArrayList<>();
        for (Object[] f : froms) for (Object[] to : tos) if (f != to || uTurn) all.add(new Object[][]{f, to});
        List<Object[][]> moves = new ArrayList<>();
        for (Object[][] p : all) if (canEnter(p[0], t.via) && canLeave(p[1], t.via)) moves.add(p);
        if (moves.isEmpty()) moves = all;
        if (moves.size() > 1) {
            List<Object[][]> shaped = new ArrayList<>();
            for (Object[][] p : moves) {
                double d = angle(p[0], p[1], t.via);
                boolean ok = t.kind.endsWith("left_turn") ? d < -20 && d > -160
                    : t.kind.endsWith("right_turn") ? d > 20 && d < 160
                    : t.kind.endsWith("straight_on") ? Math.abs(d) < 45
                    : !uTurn || Math.abs(d) > 135;
                if (ok) shaped.add(p);
            }
            if (!shaped.isEmpty()) moves = shaped;
        }
        return moves;
    }

    static boolean canEnter(Object[] e, long via) { long[] s = (long[]) e[2]; return ((Way) e[1]).oneway == 0 || s[s.length - 1] == via; }
    static boolean canLeave(Object[] e, long via) { long[] s = (long[]) e[2]; return ((Way) e[1]).oneway == 0 || s[0] == via; }

    /** Signed turn angle (degrees, negative = left) arriving at [via] on [from] and leaving on [to]. */
    static double angle(Object[] from, Object[] to, long via) {
        long[] f = (long[]) from[2], o = (long[]) to[2];
        long before = f[f.length - 1] == via ? f[f.length - 2] : f[1];
        long after = o[0] == via ? o[1] : o[o.length - 2];
        double d = heading(coord.get(via), coord.get(after)) - heading(coord.get(before), coord.get(via));
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }

    static double heading(int[] p, int[] q) {
        double dx = (q[1] - p[1]) * Math.cos(Math.toRadians(p[0] / 1e6)), dy = q[0] - p[0];
        return Math.toDegrees(Math.atan2(dx, dy));
    }

    static void read(Path p) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p))) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            long id = 0; List<Long> nds = null; Map<String, String> tags = null;
            long[] rel = null;   // restriction members: from way, via node, to way
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "way" -> { id = Long.parseLong(r.getAttributeValue(null, "id")); nds = new ArrayList<>(); tags = new HashMap<>(); }
                        case "nd" -> {
                            if (nds == null) break;
                            long ref = Long.parseLong(r.getAttributeValue(null, "ref"));
                            String la = r.getAttributeValue(null, "lat"), lo = r.getAttributeValue(null, "lon");
                            if (la == null || lo == null) break;
                            nds.add(ref);
                            coord.putIfAbsent(ref, new int[]{(int) Math.round(Double.parseDouble(la) * 1e6), (int) Math.round(Double.parseDouble(lo) * 1e6)});
                        }
                        case "relation" -> { rel = new long[]{-1, -1, -1, 0}; tags = new HashMap<>(); }
                        case "member" -> {
                            if (rel == null) break;
                            String role = r.getAttributeValue(null, "role"), type = r.getAttributeValue(null, "type");
                            long ref = Long.parseLong(r.getAttributeValue(null, "ref"));
                            if ("from".equals(role) && "way".equals(type)) rel[0] = ref;
                            else if ("via".equals(role) && "node".equals(type)) rel[1] = ref;
                            else if ("via".equals(role) && "way".equals(type)) rel[3] = 1;
                            else if ("to".equals(role) && "way".equals(type)) rel[2] = ref;
                        }
                        case "tag" -> { if (tags != null) tags.put(r.getAttributeValue(null, "k"), r.getAttributeValue(null, "v")); }
                        default -> {}
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("way")) {
                    if (!ways.containsKey(id) && nds.size() >= 2) {
                        Way w = way(id, tags, nds);
                        if (w != null) ways.put(id, w);
                    }
                    nds = null; tags = null;
                } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("relation")) {
                    // Only simple restrictions (one from way, a via node, one to way) that apply to cars.
                    String kind = tags.getOrDefault("restriction:motorcar", tags.getOrDefault("restriction", ""));
                    boolean exceptCars = tags.getOrDefault("except", "").contains("motorcar");
                    if ("restriction".equals(tags.get("type")) && rel[0] > 0 && rel[1] > 0 && rel[2] > 0 && !exceptCars
                        && (kind.startsWith("no_") || kind.startsWith("only_"))) {
                        Turn t = new Turn(rel[0], rel[1], rel[2], kind.startsWith("only_") ? 1 : 0, kind);
                        if (!turns.contains(t)) turns.add(t);
                    } else if ("restriction".equals(tags.get("type")) && rel[3] == 1) viaWayTurns++;
                    rel = null; tags = null;
                }
            }
        }
    }

    static Way way(long id, Map<String, String> t, List<Long> nds) {
        int cls = Arrays.asList(CLASSES).indexOf(t.getOrDefault("highway", ""));
        if (cls < 0) return null;
        // The most specific access tag that covers a car wins: motorcar, then motor_vehicle, vehicle, access.
        String access = "yes";
        for (String k : new String[]{"motorcar", "motor_vehicle", "vehicle", "access"}) {
            if (t.containsKey(k)) { access = t.get(k); break; }
        }
        if (access.equals("no") || access.equals("private")) return null;
        String ow = t.getOrDefault("oneway", "");
        int oneway = 0;
        boolean reverse = false;
        if (ow.equals("yes") || ow.equals("true") || ow.equals("1")) oneway = 1;
        else if (ow.equals("-1") || ow.equals("reverse")) { oneway = 1; reverse = true; }
        else if (!ow.equals("no") && (cls == 0 || cls == 11 || "roundabout".equals(t.get("junction")))) oneway = 1;
        int mph = DEFAULT_MPH[cls];
        String ms = t.get("maxspeed");
        if (ms != null) {
            var m = java.util.regex.Pattern.compile("^(\\d+)\\s*(mph)?$").matcher(ms.trim());
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                int asMph = m.group(2) != null ? v : (int) Math.round(v / 1.609344);
                if (asMph >= 5 && asMph <= 85) mph = asMph;
            }
        }
        String name = t.getOrDefault("name", "");
        String ref = t.getOrDefault("ref", "");
        if (!ref.isEmpty()) name = name.isEmpty() ? ref : ref + " (" + name + ")";
        long[] n = nds.stream().mapToLong(Long::longValue).toArray();
        if (reverse) { for (int i = 0, j = n.length - 1; i < j; i++, j--) { long s = n[i]; n[i] = n[j]; n[j] = s; } }
        // Road condition, for the car / 4x4 choice on the phone.
        int flags = 0;
        if ("yes".equals(t.get("4wd_only"))) flags |= 1;
        String surface = t.getOrDefault("surface", "");
        if (surface.matches("unpaved|gravel|dirt|ground|earth|sand|mud|grass|fine_gravel|compacted|rock|pebblestone|woodchips|snow|ice")
            || (cls == 9 && surface.isEmpty())) flags |= 2;
        String grade = t.getOrDefault("tracktype", ""), smooth = t.getOrDefault("smoothness", "");
        if (grade.matches("grade[345]") || smooth.matches("bad|very_bad|horrible|very_horrible|impassable")) flags |= 4;
        if (grade.equals("grade5") || smooth.matches("very_horrible|impassable")) flags |= 8;
        return new Way(id, cls, oneway, mph, name, n, flags);
    }

    static void write(Path f, List<Object[]> edges, List<long[]> cellTurns) throws IOException {
        Map<String, Integer> names = new LinkedHashMap<>();
        Map<Long, Integer> nodes = new LinkedHashMap<>();
        for (Object[] e : edges) {
            Way w = (Way) e[1]; long[] seg = (long[]) e[2];
            if (!w.name.isEmpty()) names.putIfAbsent(w.name, names.size());
            nodes.putIfAbsent(seg[0], nodes.size()); nodes.putIfAbsent(seg[seg.length - 1], nodes.size());
        }
        try (var o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(f)))) {
            o.writeBytes("BRG3");
            o.writeInt(names.size());
            for (String s : names.keySet()) o.writeUTF(s.length() > 200 ? s.substring(0, 200) : s);
            o.writeInt(nodes.size());
            for (long n : nodes.keySet()) { int[] c = coord.get(n); o.writeLong(n); o.writeInt(c[0]); o.writeInt(c[1]); }
            o.writeInt(edges.size());
            for (Object[] e : edges) {
                Way w = (Way) e[1]; long[] seg = (long[]) e[2];
                o.writeLong((long) e[0]);
                o.writeInt(nodes.get(seg[0])); o.writeInt(nodes.get(seg[seg.length - 1]));
                o.writeByte(w.cls); o.writeByte(w.oneway); o.writeByte(w.mph); o.writeByte(w.flags);
                o.writeInt(w.name.isEmpty() ? -1 : names.get(w.name));
                o.writeFloat((float) e[3]);
                int pts = Math.min(seg.length - 2, Short.MAX_VALUE);
                o.writeShort(pts);
                for (int k = 1; k <= pts; k++) { int[] c = coord.get(seg[k]); o.writeInt(c[0]); o.writeInt(c[1]); }
            }
            o.writeInt(cellTurns.size());
            for (long[] t : cellTurns) { o.writeLong(t[0]); o.writeLong(t[1]); o.writeLong(t[2]); o.writeByte((int) t[3]); }
        }
    }

    static double meters(int[] p, int[] q) {
        double la1 = Math.toRadians(p[0] / 1e6), la2 = Math.toRadians(q[0] / 1e6);
        double dla = la2 - la1, dlo = Math.toRadians((q[1] - p[1]) / 1e6);
        double h = Math.sin(dla / 2) * Math.sin(dla / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dlo / 2) * Math.sin(dlo / 2);
        return 2 * 6371008.8 * Math.asin(Math.sqrt(h));
    }

    static int tileX(double lon, int z) { return (int) Math.floor((lon + 180) / 360 * (1 << z)); }
    static int tileY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }
}
