package de.droiddrone.pc.air;

/**
 * Fleet PRD FL-9: the group designer's checks. The stacking and the layer's shape are the hollow cube's
 * ({@link HollowCube}: a ring of {@link HollowCube#CELLS} round an empty core, the same ring in every layer); the pilot
 * sets the cell size, the layer spacing, the keep-out, the leash and the distance kept from the lead, the floor and the
 * ceiling. The size check is AC-35 ({@link AirParams#sizeProblem}), the layer check AC-13: the group's top layer and the
 * reserves' approach layers above it stay under the ceiling. Without GNSS (camera, UWB) is stage 2.
 */
public final class GroupDesign {
    private GroupDesign() {
    }

    /** The ceiling when none is set: 120 m above the ground. */
    public static final double DEFAULT_CEILING_M = 120;
    /** Layers never closer than this: the protection's height (AC-9: 10 m). */
    public static final double MIN_LAYER_M = 10;

    /** The settings with the designer's numbers, the rest as they were. */
    public static AirParams with(AirParams p, double cellM, double layerM, double keepOutM, double leashM, double keepM,
                                 double floorM, double ceilingM) {
        return new AirParams(cellM, layerM, p.radiusM(), keepOutM, leashM, keepM, floorM, p.moveCapMs(), p.catchUpMs(), p.accelMs2(),
                p.lostWaitMs(), p.lostMoveM(), p.holdMs(), p.refreshMs(), p.leadSilentMs(), p.orderCheckMs(), p.transitWaitMs(), ceilingM,
                p.brakeMs2());
    }

    /** Cube layers for this many followers. */
    public static int layers(int followers) {
        return Math.max(1, (followers + HollowCube.CELLS - 1) / HollowCube.CELLS);
    }

    /** The ceiling in force: the set one, else {@link #DEFAULT_CEILING_M}. */
    public static double ceiling(AirParams p) {
        return Double.isNaN(p.ceilingM()) ? DEFAULT_CEILING_M : p.ceilingM();
    }

    /**
     * The highest layer above the ground: the abort layer at the floor, the cube's bottom layer one spacing above it
     * (AC-31), its layers on top, then one approach layer for each reserve on its way (AC-5).
     */
    public static double topM(AirParams p, int followers, int reserves) {
        int layers = layers(followers);
        return reserves <= 0 ? cubeTopM(p, layers) : approachM(p, layers, reserves - 1);
    }

    /** The cube's top layer above the ground, with this many layers: the abort layer at the floor, the cube above it. */
    public static double cubeTopM(AirParams p, int layers) {
        return p.floorM() + Math.max(1, layers) * p.layerM();
    }

    /**
     * AC-5: the k-th reserve's approach layer (0: the first) above the ground - two spacings over the cube's top layer,
     * one more for each reserve on its way before it. The one rule for the designer's check (AC-13) and the group's
     * own approach layers (GroupController): before, the designer counted the first a spacing lower than the group flew it.
     */
    public static double approachM(AirParams p, int layers, int k) {
        return cubeTopM(p, layers) + (2 + Math.max(0, k)) * p.layerM();
    }

    /** AC-13 and the layer spacing, in plain words; null when it fits. */
    public static String layerProblem(AirParams p, int followers, int reserves) {
        StringBuilder s = new StringBuilder();
        if (p.layerM() < MIN_LAYER_M) s.append("Layers at least ").append(Math.round(MIN_LAYER_M)).append(" m apart. ");
        double top = topM(p, followers, reserves), ceiling = ceiling(p);
        if (top > ceiling) {
            s.append("Top layer ").append(Math.round(top)).append(" m, over the ceiling of ").append(Math.round(ceiling))
                    .append(" m: fewer drones, a lower floor or closer layers (AC-13). ");
        }
        return s.length() == 0 ? null : s.toString().trim();
    }

    /** Every check: the sizes (AC-35) and the layers (AC-13); null when all fit. */
    public static String problems(AirParams p, int followers, int reserves) {
        String size = p.sizeProblem(), layer = layerProblem(p, followers, reserves);
        if (size == null) return layer;
        return layer == null ? size : size + " " + layer;
    }
}
