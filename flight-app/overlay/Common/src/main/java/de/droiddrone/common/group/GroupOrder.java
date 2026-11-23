/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone (GPL v3 or later, see the project's LICENSE).
 */

package de.droiddrone.common.group;

import java.nio.ByteBuffer;

/**
 * DRAUPNE fleet (PRD FL-1 - FL-3, FL-13, AC-18, AC-39): what the PC tells one follower's phone, a few times a second.
 * The group's centre and how fast it moves (the same for every follower, so the cube keeps its shape), where this
 * follower is to be relative to that centre (its cell, or the point of a move through the outer lane and a transit
 * area the PC has granted), at what height, the floor it may never go under, and where the lead is (the follower
 * points at it). The phone carries the centre forward by its velocity for the order's age ({@link FollowerRule}).
 * Java 11.
 * <p>
 * Wire form, big-endian, 48 bytes: [version u8 = 1][flags u8][seq u16][centre lat i32 1e-7][centre lon i32 1e-7]
 * [centre velocity east i16 cm/s][north i16 cm/s][offset east i16 dm][offset north i16 dm][alt AMSL i32 cm]
 * [floor AMSL i32 cm][speed cap u16 cm/s][lead lat i32][lead lon i32][lead alt AMSL i32 cm][lead velocity east i16 cm/s]
 * [north i16 cm/s][spare u16].
 */
public final class GroupOrder {
    public static final int VERSION = 1, BYTES = 48;
    /** flags: this drone is in the bottom row (it lands straight down at 3 %; it leaves by the abort layer). */
    public static final int FLAG_BOTTOM_ROW = 1;
    /** flags: the order holds the drone where it is (the group has stopped for a drone that stopped, AC-40). */
    public static final int FLAG_STOPPED = 2;
    /** flags: the lead's place is known (relayed by the PC, PRD E6). */
    public static final int FLAG_LEAD_KNOWN = 4;
    /** flags: land now, where it is (AC-20 column by column; AC-24 / AC-33 at 3 %) - taken also without GNSS. */
    public static final int FLAG_LAND = 8;

    public final int flags, seq;
    public final double centreLat, centreLon, velE, velN, offE, offN, altAmsl, floorAmsl, speedCap;
    public final double leadLat, leadLon, leadAltAmsl, leadVelE, leadVelN;

    public GroupOrder(int flags, int seq, double centreLat, double centreLon, double velE, double velN, double offE, double offN,
                      double altAmsl, double floorAmsl, double speedCap, double leadLat, double leadLon, double leadAltAmsl,
                      double leadVelE, double leadVelN) {
        this.flags = flags;
        this.seq = seq & 0xffff;
        this.centreLat = centreLat;
        this.centreLon = centreLon;
        this.velE = velE;
        this.velN = velN;
        this.offE = offE;
        this.offN = offN;
        this.altAmsl = altAmsl;
        this.floorAmsl = floorAmsl;
        this.speedCap = speedCap;
        this.leadLat = leadLat;
        this.leadLon = leadLon;
        this.leadAltAmsl = leadAltAmsl;
        this.leadVelE = leadVelE;
        this.leadVelN = leadVelN;
    }

    public boolean bottomRow() {
        return (flags & FLAG_BOTTOM_ROW) != 0;
    }

    public boolean stopped() {
        return (flags & FLAG_STOPPED) != 0;
    }

    public boolean leadKnown() {
        return (flags & FLAG_LEAD_KNOWN) != 0;
    }

    public boolean land() {
        return (flags & FLAG_LAND) != 0;
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        b.put((byte) VERSION).put((byte) flags).putShort((short) seq);
        b.putInt(e7(centreLat)).putInt(e7(centreLon));
        b.putShort(s16(velE * 100)).putShort(s16(velN * 100));
        b.putShort(s16(offE * 10)).putShort(s16(offN * 10));
        b.putInt((int) Math.round(altAmsl * 100)).putInt((int) Math.round(floorAmsl * 100));
        b.putShort((short) Math.max(0, Math.min(65535, Math.round(speedCap * 100))));
        b.putInt(e7(leadLat)).putInt(e7(leadLon)).putInt((int) Math.round(leadAltAmsl * 100));
        b.putShort(s16(leadVelE * 100)).putShort(s16(leadVelN * 100));
        b.putShort((short) 0);
        return b.array();
    }

    /** The order in data[at..], or null: too short or another version. */
    public static GroupOrder decode(byte[] data, int at) {
        if (data == null || at < 0 || data.length - at < BYTES || data[at] != VERSION) return null;
        ByteBuffer b = ByteBuffer.wrap(data, at, BYTES);
        b.get();
        int flags = b.get() & 0xff, seq = b.getShort() & 0xffff;
        double cLat = b.getInt() / 1e7, cLon = b.getInt() / 1e7;
        double vE = b.getShort() / 100.0, vN = b.getShort() / 100.0;
        double oE = b.getShort() / 10.0, oN = b.getShort() / 10.0;
        double alt = b.getInt() / 100.0, floor = b.getInt() / 100.0;
        double cap = (b.getShort() & 0xffff) / 100.0;
        double lLat = b.getInt() / 1e7, lLon = b.getInt() / 1e7, lAlt = b.getInt() / 100.0;
        double lvE = b.getShort() / 100.0, lvN = b.getShort() / 100.0;
        return new GroupOrder(flags, seq, cLat, cLon, vE, vN, oE, oN, alt, floor, cap, lLat, lLon, lAlt, lvE, lvN);
    }

    static int e7(double deg) {
        return Double.isNaN(deg) ? 0 : (int) Math.round(deg * 1e7);
    }

    static short s16(double v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
    }
}
