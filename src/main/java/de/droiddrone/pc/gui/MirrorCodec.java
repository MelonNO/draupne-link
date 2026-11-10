package de.droiddrone.pc.gui;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The mirror's window sections (1.0.56, {@link de.droiddrone.pc.Mirror}): the FLEET list's cards, the map's drones and
 * the observed drones' marks as bytes and back. Null in, empty out; broken bytes read as null.
 */
final class MirrorCodec {
    static final String FLEET = "fleet", DRONES = "drones", OBSERVED = "observed", MAP = "text.map";

    private MirrorCodec() {
    }

    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] bytes(Writer w) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            w.write(out);
            return b.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(byte[] d, Reader<T> r) {
        if (d == null) return null;
        try {
            return r.read(new DataInputStream(new ByteArrayInputStream(d)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    private static String n(String v) {
        return v.isEmpty() ? null : v;
    }

    private static MapView.Kind kind(int i) {
        MapView.Kind[] all = MapView.Kind.values();
        return i >= 0 && i < all.length ? all[i] : MapView.Kind.UNKNOWN;
    }

    // ------------------------------------------------------------------ FLEET

    static byte[] cards(List<FleetCards.Card> cards) {
        return bytes(out -> {
            List<FleetCards.Card> list = cards == null ? List.of() : cards;
            out.writeShort(list.size());
            for (FleetCards.Card c : list) {
                out.writeUTF(s(c.name()));
                out.writeBoolean(c.virtual());
                out.writeByte(c.kind() == null ? MapView.Kind.UNKNOWN.ordinal() : c.kind().ordinal());
                out.writeUTF(s(c.badge()));
                out.writeDouble(c.cellVolts());
                out.writeLong(c.cellAgeMs());
                out.writeInt(c.phonePercent());
                out.writeBoolean(c.charging());
                out.writeLong(c.nextCheckMs());
                out.writeInt(c.intervalMin());
                out.writeLong(c.heardAgoMs());
                out.writeInt(c.signal());
                out.writeInt(c.gpsFix());
                out.writeInt(c.sats());
                out.writeInt(c.events());
                out.writeLong(c.lastEventWall());
                out.writeInt(c.phoneTempDeci());
                out.writeInt(c.fcTempC());
                out.writeUTF(s(c.queued()));
                out.writeUTF(s(c.note()));
            }
            for (FleetCards.Card c : list) out.writeLong(c.queuedAgoMs()); // 2026-11-10: after the list, for older readers
        });
    }

    static List<FleetCards.Card> cards(byte[] d) {
        return read(d, in -> {
            int n = in.readUnsignedShort();
            List<FleetCards.Card> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                boolean virtual = in.readBoolean();
                MapView.Kind kind = kind(in.readUnsignedByte());
                String badge = in.readUTF();
                double cell = in.readDouble();
                long cellAge = in.readLong();
                int pct = in.readInt();
                boolean charging = in.readBoolean();
                long next = in.readLong();
                int interval = in.readInt();
                long heard = in.readLong();
                int signal = in.readInt(), fix = in.readInt(), sats = in.readInt(), events = in.readInt();
                long lastEvent = in.readLong();
                int phoneTemp = in.readInt(), fcTemp = in.readInt();
                String queued = in.readUTF(), note = in.readUTF();
                out.add(new FleetCards.Card(name, virtual, kind, badge, cell, cellAge, pct, charging, next, interval, heard, signal,
                        fix, sats, events, lastEvent, phoneTemp, fcTemp, n(queued), n(note)));
            }
            if (in.available() >= 8L * n) { // a newer pilot PC: since each mode was queued
                for (int i = 0; i < n; i++) {
                    FleetCards.Card c = out.get(i);
                    out.set(i, new FleetCards.Card(c.name(), c.virtual(), c.kind(), c.badge(), c.cellVolts(), c.cellAgeMs(), c.phonePercent(),
                            c.charging(), c.nextCheckMs(), c.intervalMin(), c.heardAgoMs(), c.signal(), c.gpsFix(), c.sats(), c.events(),
                            c.lastEventWall(), c.phoneTempDeci(), c.fcTempC(), c.queued(), c.note(), in.readLong()));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ the map's drones

    static byte[] drones(List<MapView.NetDrone> drones) {
        return bytes(out -> {
            List<MapView.NetDrone> list = drones == null ? List.of() : drones;
            out.writeShort(list.size());
            for (MapView.NetDrone d : list) {
                out.writeUTF(s(d.name()));
                out.writeDouble(d.lat());
                out.writeDouble(d.lon());
                out.writeUTF(s(d.state()));
                out.writeByte(d.kind() == null ? MapView.Kind.UNKNOWN.ordinal() : d.kind().ordinal());
                out.writeBoolean(d.flown());
                out.writeUTF(s(d.pilot()));
            }
            // 2026-11-10: each one's queued mode after the list (255 none), for older readers
            for (MapView.NetDrone d : list) out.writeByte(d.queued() == null ? 255 : d.queued().ordinal());
        });
    }

    static List<MapView.NetDrone> drones(byte[] d) {
        return read(d, in -> {
            int n = in.readUnsignedShort();
            List<MapView.NetDrone> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                double lat = in.readDouble(), lon = in.readDouble();
                String state = in.readUTF();
                MapView.Kind kind = kind(in.readUnsignedByte());
                boolean flown = in.readBoolean();
                String pilot = in.readUTF();
                out.add(new MapView.NetDrone(name, lat, lon, state, kind, flown, n(pilot)));
            }
            if (in.available() >= n) { // a newer pilot PC: the queued modes
                for (int i = 0; i < n; i++) {
                    int q = in.readUnsignedByte();
                    MapView.NetDrone was = out.get(i);
                    if (q != 255) out.set(i, new MapView.NetDrone(was.name(), was.lat(), was.lon(), was.state(), was.kind(), was.flown(), was.pilot(), kind(q)));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ the observed drones' marks

    static byte[] observed(List<MapView.Observed> marks) {
        return bytes(out -> {
            List<MapView.Observed> list = marks == null ? List.of() : marks;
            out.writeByte(list.size());
            for (MapView.Observed o : list) {
                out.writeByte(o.tile());
                out.writeInt(o.colour() == null ? 0 : o.colour().getRGB());
                out.writeDouble(o.lat());
                out.writeDouble(o.lon());
                out.writeDouble(o.headingDeg());
                out.writeBoolean(o.approximate());
                List<double[]> cone = o.cone() == null ? List.of() : o.cone();
                out.writeShort(cone.size());
                for (double[] p : cone) {
                    out.writeDouble(p[0]);
                    out.writeDouble(p[1]);
                }
                out.writeBoolean(o.highlighted());
                out.writeBoolean(o.selected());
                out.writeBoolean(o.motion());
            }
        });
    }

    static List<MapView.Observed> observed(byte[] d) {
        return read(d, in -> {
            int n = in.readUnsignedByte();
            List<MapView.Observed> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int tile = in.readUnsignedByte();
                Color colour = new Color(in.readInt(), true);
                double lat = in.readDouble(), lon = in.readDouble(), heading = in.readDouble();
                boolean approximate = in.readBoolean();
                int c = in.readUnsignedShort();
                List<double[]> cone = new ArrayList<>(c);
                for (int k = 0; k < c; k++) cone.add(new double[]{in.readDouble(), in.readDouble()});
                out.add(new MapView.Observed(tile, colour, lat, lon, heading, approximate, cone, in.readBoolean(), in.readBoolean(),
                        in.readBoolean()));
            }
            return out;
        });
    }
}
