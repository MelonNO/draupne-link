/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone (GPL v3 or later, see the project's LICENSE).
 */

package de.droiddrone.common.group;

import java.nio.ByteBuffer;

/**
 * DRAUPNE fleet (PRD AC-36, AC-37, AC-41, K20): a follower's way out without the PC, held by its phone and refreshed
 * by the PC every 10 s and at every change. The lane is not a fixed point: it runs from where the drone is when it
 * starts to leave, in the direction of its place in the group as its last order gives it (its offset from the centre:
 * straight out through its cell, or out from its point on the lane or in a shaft), on until it is laneEndRadiusM from
 * the centre - so a plan up to a refresh old still points the right way after the group has moved, and a drone that
 * braked on past its cell when the PC went (37 m from 15 m/s) still leaves outwards, not back through the group. At the lane's end it sets RTL_ALT to
 * returnAltRelM (its own return height, above its home) and goes home by plain RTL to its own rally point.
 * startDelayS: how long after the 2 min hold it waits before it leaves (where heights alone can't keep two apart).
 * Java 11.
 * <p>
 * Wire form, big-endian, 24 bytes: [version u8 = 1][flags u8][seq u16][lane end radius u16 dm][start delay u16 ds]
 * [return alt above home i32 cm][rally lat i32 1e-7][rally lon i32 1e-7][own layer AMSL i32 cm].
 */
public final class ExitPlan {
    public static final int VERSION = 1, BYTES = 24;
    /** flags: the drone is in the bottom row - at 3 % during the hold it lands straight down (AC-37). */
    public static final int FLAG_BOTTOM_ROW = 1;

    public final int flags, seq;
    public final double laneEndRadiusM, startDelayS, returnAltRelM, rallyLat, rallyLon, layerAltAmsl;

    public ExitPlan(int flags, int seq, double laneEndRadiusM, double startDelayS, double returnAltRelM,
                    double rallyLat, double rallyLon, double layerAltAmsl) {
        this.flags = flags;
        this.seq = seq & 0xffff;
        this.laneEndRadiusM = laneEndRadiusM;
        this.startDelayS = startDelayS;
        this.returnAltRelM = returnAltRelM;
        this.rallyLat = rallyLat;
        this.rallyLon = rallyLon;
        this.layerAltAmsl = layerAltAmsl;
    }

    public boolean bottomRow() {
        return (flags & FLAG_BOTTOM_ROW) != 0;
    }

    /**
     * The lane's end for a drone at (lat, lon) whose place in the group is (offE, offN) metres from its centre: {lat, lon}.
     * A place right on the centre (never, in a hollow cube) goes north.
     */
    public double[] laneEnd(double lat, double lon, double offE, double offN) {
        double d = Math.hypot(offE, offN);
        double ue = d < 0.5 ? 0 : offE / d, un = d < 0.5 ? 1 : offN / d;
        double go = Math.max(laneEndRadiusM - d, 0);
        return GeoLocal.offset(lat, lon, ue * go, un * go);
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        b.put((byte) VERSION).put((byte) flags).putShort((short) seq);
        b.putShort((short) Math.max(0, Math.min(65535, Math.round(laneEndRadiusM * 10))));
        b.putShort((short) Math.max(0, Math.min(65535, Math.round(startDelayS * 10))));
        b.putInt((int) Math.round(returnAltRelM * 100));
        b.putInt(GroupOrder.e7(rallyLat)).putInt(GroupOrder.e7(rallyLon));
        b.putInt((int) Math.round(layerAltAmsl * 100));
        return b.array();
    }

    /** The plan in data[at..], or null: too short or another version. */
    public static ExitPlan decode(byte[] data, int at) {
        if (data == null || at < 0 || data.length - at < BYTES || data[at] != VERSION) return null;
        ByteBuffer b = ByteBuffer.wrap(data, at, BYTES);
        b.get();
        int flags = b.get() & 0xff, seq = b.getShort() & 0xffff;
        double radius = (b.getShort() & 0xffff) / 10.0, delay = (b.getShort() & 0xffff) / 10.0;
        double ret = b.getInt() / 100.0;
        double lat = b.getInt() / 1e7, lon = b.getInt() / 1e7;
        double layer = b.getInt() / 100.0;
        return new ExitPlan(flags, seq, radius, delay, ret, lat, lon, layer);
    }
}
