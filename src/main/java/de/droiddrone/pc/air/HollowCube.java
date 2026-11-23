package de.droiddrone.pc.air;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The follower group's shape (PRD AC-16, AC-17, AC-22, AC-27, AC-28, AC-31, K3, K11, K14): layers of a 3 x 3 ring of
 * cells round an empty core, 8 drones a layer, fixed to the compass; the abort layer at the floor and the cube's bottom
 * row one layer higher; the outer lane round the ring; four transit shafts (N, E, S, W) outside the lane; the hop ring
 * through the shafts with its four corners; corner exits from the abort layer. Positions are metres from the group's
 * centre ({@link V2}); heights are metres above the group's base (see {@link LayerTable}).
 * <p>
 * A move ({@link #move}) is a list of {@link Stage}s, each with the resources it holds - a side of the outer lane in a
 * layer, a shaft, a hop corner, an abort-layer column, a corner exit - and the points it flies through. The
 * {@link TransitScheduler} grants a stage whole or not at all; a drone flies only the stages it was granted.
 */
public final class HollowCube {
    /** The four faces, clockwise from north; each with its outward unit direction. */
    public enum Face {
        N(0, 1), E(1, 0), S(0, -1), W(-1, 0);

        public final V2 out;

        Face(double e, double n) {
            out = new V2(e, n);
        }

        public Face next() {
            return values()[(ordinal() + 1) % 4];
        }

        public Face prev() {
            return values()[(ordinal() + 3) % 4];
        }
    }

    public static final int CELLS = 8;
    /** The ring's cells, clockwise from the north-west corner: NW, N, NE, E, SE, S, SW, W (in cell sizes). */
    private static final int[][] RING = {{-1, 1}, {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}};

    private final AirParams p;

    public HollowCube(AirParams p) {
        this.p = p;
    }

    public AirParams params() {
        return p;
    }

    /** Layers needed for this many followers (8 a layer). */
    public static int layersFor(int followers) {
        return Math.max(1, (followers + CELLS - 1) / CELLS);
    }

    public V2 cell(int cell) {
        return new V2(RING[cell][0] * p.cellM(), RING[cell][1] * p.cellM());
    }

    public static boolean isCorner(int cell) {
        return cell % 2 == 0;
    }

    /** The faces a cell is on: one for a middle cell, two for a corner. */
    public static List<Face> faces(int cell) {
        List<Face> f = new ArrayList<>();
        int e = RING[cell][0], n = RING[cell][1];
        if (n == 1) f.add(Face.N);
        if (e == 1) f.add(Face.E);
        if (n == -1) f.add(Face.S);
        if (e == -1) f.add(Face.W);
        return f;
    }

    /** Height of a cube layer above the base: layer 0 (the bottom row) one layer spacing over the floor (K14). */
    public double layerAlt(int layer) {
        return p.floorM() + (layer + 1) * p.layerM();
    }

    /** The abort layer: at the floor (AC-31). */
    public double abortAlt() {
        return p.floorM();
    }

    public V2 shaft(Face f) {
        return f.out.times(p.shaftM());
    }

    public V2 laneCentre(Face f) {
        return f.out.times(p.laneM());
    }

    /** Where a drone in this cell meets the outer lane on face f (straight out from its face, AC-17). */
    public V2 lanePoint(Face f, V2 cellPos) {
        return f == Face.N || f == Face.S ? new V2(cellPos.e(), f.out.n() * p.laneM()) : new V2(f.out.e() * p.laneM(), cellPos.n());
    }

    /** The hop ring's corner between two neighbouring faces. */
    public V2 hopCorner(Face a, Face b) {
        return a.out.plus(b.out).times(p.shaftM());
    }

    /** The corner an abort-layer exit from this cell leaves by: its own for a corner cell, else the next clockwise. */
    public static int exitCorner(int cell) {
        return isCorner(cell) ? cell : (cell + 1) % CELLS;
    }

    /** How far p is outside the group's edge (a square of half {@link AirParams#edgeM}); 0 inside. */
    public double outsideEdge(V2 at) {
        double h = p.edgeM();
        double dx = Math.max(0, Math.abs(at.e()) - h), dy = Math.max(0, Math.abs(at.n()) - h);
        return Math.hypot(dx, dy);
    }

    /** One point of a move: where (from the centre) and how high (above the base). */
    public record Point(V2 at, double alt) {}

    /** A stage of a move: what it holds while it flies its points. */
    public record Stage(Set<String> resources, List<Point> points, boolean entersShaft, boolean emergency) {}

    public static String lane(int layer, Face f) {
        return "lane:" + layer + ":" + f;
    }

    public static String shaftRes(Face f) {
        return "shaft:" + f;
    }

    public static String corner(Face a, Face b) {
        Face x = a.ordinal() < b.ordinal() ? a : b, y = x == a ? b : a;
        if (x == Face.N && y == Face.W) return "corner:WN";
        return "corner:" + x + y;
    }

    /** A cell in a layer: held by the drone in it until it has left it, and granted to the next only then. */
    public static String cellRes(int layer, int cell) {
        return "cell:" + layer + ":" + cell;
    }

    public static String abortColumn(int cell) {
        return "abort:" + cell;
    }

    public static String cornerExit(int cornerCell) {
        return "exit:" + cornerCell;
    }

    /** Number of hops from face a to face b going clockwise (dir +1) or counter-clockwise (-1). */
    static int hops(Face a, Face b, int dir) {
        int d = Math.floorMod((b.ordinal() - a.ordinal()) * dir, 4);
        return d;
    }

    /**
     * The move from a cell in one layer to a cell in another (or the same layer, another cell): out to the lane, into
     * the face's shaft, hops round the ring (dir +1 clockwise, -1 the other way; 0 the shorter, clockwise on a tie),
     * the height changed in the last shaft, back along the lane, into the new cell (AC-27).
     */
    public List<Stage> move(int fromLayer, int fromCell, int toLayer, int toCell, int dir) {
        Face fa = null, fb = null;
        int best = Integer.MAX_VALUE, bestDir = 1;
        for (Face a : faces(fromCell)) {
            for (Face b : faces(toCell)) {
                for (int d : dir == 0 ? new int[]{1, -1} : new int[]{dir}) {
                    int h = hops(a, b, d);
                    if (h < best) {
                        best = h;
                        fa = a;
                        fb = b;
                        bestDir = d;
                    }
                }
            }
        }
        List<Stage> stages = new ArrayList<>(out(fromLayer, fromCell, fa));
        stages.addAll(hopsFrom(fa, fb, bestDir, layerAlt(fromLayer)));
        stages.add(in(fb, toLayer, toCell, true));
        return stages;
    }

    /** From a cell out to its face's shaft, at its own height. */
    public List<Stage> out(int layer, int cell, Face f) {
        double alt = layerAlt(layer);
        V2 c = cell(cell);
        List<Point> pts = List.of(new Point(lanePoint(f, c), alt), new Point(laneCentre(f), alt), new Point(shaft(f), alt));
        return List.of(new Stage(set(cellRes(layer, cell), lane(layer, f), shaftRes(f)), pts, true, false));
    }

    /** Round the hop ring from shaft a to shaft b at the given height, one stage a hop. */
    public List<Stage> hopsFrom(Face a, Face b, int dir, double alt) {
        List<Stage> stages = new ArrayList<>();
        Face f = a;
        while (f != b) {
            Face g = dir > 0 ? f.next() : f.prev();
            List<Point> pts = List.of(new Point(hopCorner(f, g), alt), new Point(shaft(g), alt));
            stages.add(new Stage(set(corner(f, g), shaftRes(g)), pts, true, false));
            f = g;
        }
        return stages;
    }

    /** From inside shaft f: the height changed to the layer, back along the lane into the cell. */
    public Stage in(Face f, int layer, int cell, boolean holdsShaft) {
        double alt = layerAlt(layer);
        V2 c = cell(cell);
        List<Point> pts = List.of(new Point(shaft(f), alt), new Point(laneCentre(f), alt), new Point(lanePoint(f, c), alt), new Point(c, alt));
        return new Stage(set(shaftRes(f), lane(layer, f), cellRes(layer, cell)), pts, !holdsShaft, false);
    }

    /**
     * A reserve (or a drone rejoining) above the group in its approach layer: down shaft f to the layer and in to its
     * cell (AC-5, AC-38). The first stage takes the shaft from outside (counted against the three in use).
     */
    public List<Stage> join(Face f, double approachAlt, int layer, int cell) {
        // it waits beside the group, beyond the shaft, in its own approach layer (nothing held there: that layer is its
        // own); never over the shaft, where a drone coming down it would have to pass it
        Stage wait = new Stage(Set.of(), List.of(new Point(waitPoint(f), approachAlt)), false, false);
        Stage down = new Stage(set(shaftRes(f)), List.of(new Point(shaft(f), approachAlt), new Point(shaft(f), layerAlt(layer))), true, false);
        return List.of(wait, down, in(f, layer, cell, true));
    }

    /** Where a drone waits for a shaft: straight out beyond it, past the group's edge. */
    public V2 waitPoint(Face f) {
        return f.out.times(p.edgeM() + 2 * p.radiusM());
    }

    /**
     * A follower above the bottom row at 3 %: out to its face's shaft and down it to the ground (AC-24); it may take
     * the free fourth shaft (K10).
     */
    public List<Stage> landThroughShaft(int layer, int cell, Face f) {
        Stage o = out(layer, cell, f).get(0);
        Stage first = new Stage(o.resources(), o.points(), true, true);
        Stage down = new Stage(set(shaftRes(f)), List.of(new Point(shaft(f), 0)), false, true);
        return List.of(first, down);
    }

    /**
     * A bottom-row drone leaving the group by the abort layer (AC-31, AC-32): straight down into it, under the corner
     * cell, then diagonally out past the keep-out zone. The new lead after DISCARD LEAD; never anyone else.
     */
    public Stage abortExit(int cell) {
        int corner = exitCorner(cell);
        V2 c = cell(cell), k = cell(corner);
        double alt = abortAlt();
        V2 out = k.unit().times(p.edgeM() * Math.sqrt(2) + p.keepOutM() + p.radiusM()); // past the keep-out zone at its corner
        List<Point> pts = new ArrayList<>();
        pts.add(new Point(c, alt));
        if (corner != cell) pts.add(new Point(k, alt));
        pts.add(new Point(out, alt));
        return new Stage(set(cellRes(0, cell), abortColumn(cell), abortColumn(corner), cornerExit(corner)), pts, false, false);
    }

    /** A bottom-row drone at 3 %: straight down from its cell (AC-33). */
    public Stage landBottom(int cell) {
        return new Stage(set(cellRes(0, cell), abortColumn(cell)), List.of(new Point(cell(cell), 0)), false, true);
    }

    private static Set<String> set(String... r) {
        return new LinkedHashSet<>(List.of(r));
    }
}
