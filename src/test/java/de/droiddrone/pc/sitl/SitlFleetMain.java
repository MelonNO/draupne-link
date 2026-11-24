package de.droiddrone.pc.sitl;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.AppPaths;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.fleet.Fleet;
import de.droiddrone.pc.fleet.VirtualFleet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static de.droiddrone.common.PacketIds.NAME_QUERY;
import static de.droiddrone.common.PacketIds.NAME_REPLY;

/**
 * The fleet-only drones of the SITL lab (SitlLab): SITL drones each with the phone's fleet role in front of it
 * (SitlFleetPhone) on a loopback address of its own, announced as the virtual fleet is and answering DRAUPNE Link's
 * name queries. {@link #start} for a test; the helpers for the lab.
 */
public final class SitlFleetMain {
    /** SITL instances 60 and up: clear of the tests' (0 - 49). */
    static final int FIRST_INSTANCE = 60;
    /** The phones on 127.0.0.FIRST_HOST and up: clear of the virtual fleet's (11 +) and the tour's show (60 +). */
    static final int FIRST_HOST = 100;
    static final double SPACING_M = 25;

    private SitlFleetMain() {
    }

    /** The drones up: their phones by name, announced in the fleet file; close ends everything. */
    record Running(Fleet fleet, Map<String, SitlFleetPhone> phones, List<ArduSitl> sitls) implements AutoCloseable {
        @Override
        public void close() {
            fleet.close();
            for (SitlFleetPhone p : phones.values()) p.close();
            for (ArduSitl s : sitls) s.close();
        }
    }

    /** Starts the drones (SITL instances from firstInstance), announces them in fleetFile, waits until they would arm. */
    static Running start(PcConfig config, Path fleetFile, int drones, double lat, double lon, double alt, int firstInstance,
                         java.util.function.Consumer<String> log) throws IOException, InterruptedException {
        Fleet fleet = new Fleet(config, fleetFile, log);
        String key = fleet.key();
        Running r = new Running(fleet, new LinkedHashMap<>(), new ArrayList<>());
        try {
            fleet.startHeartbeat();
            for (int i = 0; i < drones; i++) {
                int instance = firstInstance + i;
                java.io.File eeprom = ArduSitl.home().resolve("run" + instance).resolve("eeprom.bin").toFile();
                if (eeprom.exists() && !eeprom.delete()) log.accept("  (run" + instance + "'s old eeprom.bin kept: in use?)");
                double[] at = GeoLocal.offset(lat, lon, (i % 5) * SPACING_M, (i / 5) * -SPACING_M);
                ArduSitl s = ArduSitl.start(instance, at[0], at[1], alt, 1);
                r.sitls().add(s);
                s.awaitReady(120_000);
                new de.droiddrone.pc.setup.ardu.ArduTools(s.fc).simpleAccelCal();
                String name = freeName(fleet, r.phones().keySet());
                InetAddress bind = InetAddress.getByName("127.0.0." + (FIRST_HOST + i));
                SitlFleetPhone p = new SitlFleetPhone(s, key, bind);
                p.onOther(d -> answerName(key, name, p, d));
                r.phones().put(name, p);
                fleet.announceExtra(new VirtualFleet.Drone(bind.getHostAddress(), p.address().getPort()));
                log.accept(String.format(Locale.ROOT, "  %s: SITL instance %d, phone %s:%d, Mission Planner tcp 127.0.0.1:%d",
                        name, instance, bind.getHostAddress(), p.address().getPort(), 5762 + 10 * instance));
            }
            for (ArduSitl s : r.sitls()) s.awaitPreArm(120_000);
            return r;
        } catch (IOException | InterruptedException | RuntimeException e) {
            r.close();
            throw e;
        }
    }

    /** A Mobile letter from Zulu down that DRAUPNE Link's name map doesn't have (never one of the pilot's phones). */
    static String freeName(Fleet fleet, java.util.Set<String> mine) {
        for (int i = Names.LETTERS.length - 1; i >= 0; i--) {
            String n = Names.Kind.PHONE.name(i);
            if (!fleet.nameTaken(n) && !mine.contains(n)) return n;
        }
        return "Sitl" + (mine.size() + 1);
    }

    /** The ground's height at the place (the app's elevation service), so routes over the terrain fit SITL's flat ground. */
    static double groundAt(double lat, double lon) {
        java.util.concurrent.CompletableFuture<Double> h = new java.util.concurrent.CompletableFuture<>();
        try {
            new de.droiddrone.pc.map.Elevation().lookup(lat, lon, h::complete);
            Double m = h.get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (m != null && !m.isNaN()) return m;
        } catch (Exception ignored) {
            // below
        }
        System.out.println("The ground's height there isn't known: 100 m above sea level assumed (give it as the 4th argument).");
        return 100;
    }

    /** A name query answered as the phone does: its name, where it is, armed or not, NORMAL (as ArduShow's). */
    static void answerName(String key, String name, SitlFleetPhone p, DatagramPacket d) {
        byte[] b = java.util.Arrays.copyOf(d.getData(), d.getLength());
        if (b.length < 1 || b[0] != NAME_QUERY) return;
        if (!key.equals(Names.keyOf(b, b.length))) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            double[] pos = p.fc.position();
            out.writeByte(NAME_REPLY);
            out.writeUTF(key);
            out.writeByte(0);
            out.writeUTF(name);
            out.writeUTF("ArduPilot SITL");
            out.writeUTF("2.3.0-pc.58-fleet");
            out.writeByte(1); // where the drone is
            out.writeByte(pos == null ? 0 : 1); // from its flight controller (0: no fix yet)
            out.writeInt(pos == null ? 0 : (int) Math.round(pos[0] * 1e7));
            out.writeInt(pos == null ? 0 : (int) Math.round(pos[1] * 1e7));
            out.writeShort(0);
            out.writeByte(p.fc.armedFlag() ? 1 : 0);
            out.writeByte(0); // not flown by a pilot
            out.writeByte(0); // no pilot address
            out.writeByte(0); // NORMAL
            new FleetProtocol.Status(80, false, 4, 1, 14, Short.MIN_VALUE, 0, 0, 0).writeTo(out);
            p.sendRaw(bytes.toByteArray(), new InetSocketAddress(d.getAddress(), d.getPort()));
        } catch (IOException ignored) {
            // the next query
        }
    }
}
