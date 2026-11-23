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
    /** GU-8: the follower group, the fleet's OSD figures and the route on the map (older spectators skip them). */
    static final String GROUP = "fleet.group", OSD = "fleet.osd", ROUTE = "fleet.route";

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

    // ------------------------------------------------------------------ GU-8: the fleet's group, OSD figures and route

    /** The follower group as the pilot PC's map draws it (null: none). */
    static byte[] group(de.droiddrone.pc.air.GroupLink.Picture p) {
        return bytes(out -> {
            out.writeBoolean(p != null);
            if (p == null) return;
            out.writeDouble(p.lat());
            out.writeDouble(p.lon());
            for (List<double[]> list : List.of(p.cells(), p.shafts())) {
                out.writeShort(list.size());
                for (double[] ll : list) {
                    out.writeDouble(ll[0]);
                    out.writeDouble(ll[1]);
                }
            }
            out.writeDouble(p.edgeHalfM());
            out.writeDouble(p.keepOutHalfM());
            out.writeDouble(p.leashM());
            out.writeDouble(p.shaftHalfM());
            out.writeDouble(p.floorM());
            out.writeInt(p.followers());
            out.writeUTF(s(p.lead()));
        });
    }

    /** Read back; null: none, or bytes that aren't one. */
    static de.droiddrone.pc.air.GroupLink.Picture group(byte[] d) {
        return read(d, in -> {
            if (!in.readBoolean()) return null;
            double lat = in.readDouble(), lon = in.readDouble();
            List<List<double[]>> lists = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                int n = in.readUnsignedShort();
                List<double[]> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(new double[]{in.readDouble(), in.readDouble()});
                lists.add(list);
            }
            return new de.droiddrone.pc.air.GroupLink.Picture(lat, lon, lists.get(0), lists.get(1), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readInt(), n(in.readUTF()));
        });
    }

    /** The fleet's OSD figures of the drone the pilot flies, without the launch views' pictures. */
    static byte[] osd(OsdFleet f) {
        return bytes(out -> {
            OsdFleet o = f == null ? OsdFleet.NONE : f;
            out.writeUTF(s(o.reserves()));
            out.writeUTF(s(o.group()));
            out.writeDouble(o.edgeM());
            out.writeDouble(o.floorM());
            out.writeUTF(s(o.gnss()));
            out.writeUTF(s(o.traffic()));
            out.writeUTF(s(o.homeNow()));
            out.writeBoolean(o.homeAlarm());
            out.writeBoolean(o.leadDown());
            out.writeByte(Math.min(o.minis().size(), 255)); // the views' words and heights: the pictures stay on the pilot PC
            for (int i = 0; i < Math.min(o.minis().size(), 255); i++) {
                OsdFleet.Mini m = o.minis().get(i);
                out.writeUTF(s(m.name()));
                out.writeUTF(s(m.status()));
                out.writeDouble(m.heightM());
                out.writeDouble(m.floorM());
                out.writeBoolean(m.failed());
            }
        });
    }

    static OsdFleet osd(byte[] d) {
        return read(d, in -> {
            String reserves = in.readUTF(), group = in.readUTF();
            double edge = in.readDouble(), floor = in.readDouble();
            String gnss = in.readUTF(), traffic = in.readUTF(), home = in.readUTF();
            boolean alarm = in.readBoolean(), leadDown = in.readBoolean();
            int n = in.readUnsignedByte();
            List<OsdFleet.Mini> minis = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String name = in.readUTF(), status = in.readUTF();
                double h = in.readDouble(), fl = in.readDouble();
                minis.add(new OsdFleet.Mini(name, null, status, h, fl, in.readBoolean()));
            }
            return new OsdFleet(n(reserves), n(group), edge, floor, n(gnss), n(traffic), n(home), alarm, leadDown, minis);
        });
    }

    /** The route on the pilot PC's map ({@link de.droiddrone.pc.route.Route#toText}) and its planned line; null: none. */
    static byte[] route(de.droiddrone.pc.route.Route r, List<double[]> planned) {
        return bytes(out -> {
            out.writeBoolean(r != null);
            if (r == null) return;
            byte[] text = r.toText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(text.length);
            out.write(text);
            List<double[]> line = planned == null ? List.of() : planned;
            out.writeInt(line.size());
            for (double[] ll : line) {
                out.writeDouble(ll[0]);
                out.writeDouble(ll[1]);
            }
        });
    }

    /** The route and its planned line back; null: none. */
    record MirroredRoute(de.droiddrone.pc.route.Route route, List<double[]> planned) {}

    static MirroredRoute route(byte[] d) {
        return read(d, in -> {
            if (!in.readBoolean()) return null;
            int len = in.readInt();
            if (len < 0 || len > 1 << 20) throw new IOException("route too long");
            byte[] text = in.readNBytes(len);
            de.droiddrone.pc.route.Route r = de.droiddrone.pc.route.Route.fromText(new String(text, java.nio.charset.StandardCharsets.UTF_8));
            int n = in.readInt();
            if (n < 0 || n > 100_000) throw new IOException("planned line too long");
            List<double[]> planned = new ArrayList<>(n);
            for (int i = 0; i < n; i++) planned.add(new double[]{in.readDouble(), in.readDouble()});
            return new MirroredRoute(r, planned.isEmpty() ? null : planned);
        });
    }
}
