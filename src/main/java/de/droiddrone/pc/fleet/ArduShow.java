package de.droiddrone.pc.fleet;

import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.group.GeoLocal;
import de.droiddrone.pc.air.GroupLink;
import de.droiddrone.pc.air.virt.VirtualFleetPhone;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static de.droiddrone.common.PacketIds.NAME_QUERY;
import static de.droiddrone.common.PacketIds.NAME_REPLY;

/**
 * Fleet PRD TO-2 / TO-3: virtual ArduPilot drones flying live for the tour and DEMO - a lead flown by the show itself
 * (no pilot session), four followers in the air, a reserve and two scouts on the ground. Each is the phone's fleet role
 * with a kinematic copter (VirtualFleetPhone) on a loopback address of the virtual fleet's (announced with the fleet's
 * drones, so the name service finds them - on the map, in FLEET), answering name queries as a phone does. The show's
 * own fleet commands (FOLLOW GROUP, DISCARD LEAD, SAFE TO AUTOLAUNCH, CALL RESERVE, DISPATCH) go through a {@link Ops}
 * the window gives it - the window's own FleetOps logic, with this show as its host - so the tour shows the real thing.
 * Never a real drone: only loopback addresses (TO-2). No failures are staged (TO-4).
 */
public final class ArduShow implements AutoCloseable {
    /** The show's phones sit on 127.0.0.FIRST_HOST and up, clear of the fleet's drones (127.0.0.11 + slot). */
    public static final int FIRST_HOST = 60;
    /** DRAUPNE phone names (Mobile + a NATO letter), late in the alphabet: clear of a fleet named from Alpha up. */
    public static final String LEAD = "MobileQuebec", RESERVE = "MobileVictor";
    public static final List<String> FOLLOWERS = List.of("MobileRomeo", "MobileSierra", "MobileTango", "MobileUniform");
    public static final List<String> SCOUTS = List.of("MobileWhiskey", "MobileXray");
    public static final String DEVICE = "virtual ArduPilot";
    static final double GROUND = 0;

    /** The window's fleet logic, as the show drives it (FleetOps, given with this show as its host). */
    public interface Ops {
        List<javax.swing.JMenuItem> items(String phone);

        void dispatch(double lat, double lon);

        void callReserve();

        void tickReserves();

        void discardLead();

        GroupLink.Picture picture();

        void close();
    }

    private final Map<String, VirtualFleetPhone> phones = new LinkedHashMap<>();
    private final Map<String, VirtualFleet.Drone> announced = new LinkedHashMap<>();
    private final Fleet fleet;
    private final String key;
    private final double lat, lon;
    private final Consumer<String> log;
    private volatile String lead = LEAD;
    private volatile boolean open = true;
    private Ops ops;
    private final Thread pilot;

    /**
     * @param fleet the virtual fleet to announce the phones with (its key is theirs)
     * @param lat   the show's place: the group starts round it
     */
    public ArduShow(Fleet fleet, double lat, double lon, Consumer<String> log) throws IOException {
        this.fleet = fleet;
        this.key = fleet.key();
        this.lat = lat;
        this.lon = lon;
        this.log = log == null ? s -> { } : log;
        int host = FIRST_HOST;
        phone(LEAD, host++, 150, 0, 80);
        double[][] at = {{-30, -30, 60}, {30, -30, 70}, {-30, 30, 80}, {30, 30, 90}};
        for (int i = 0; i < FOLLOWERS.size(); i++) phone(FOLLOWERS.get(i), host++, at[i][0], at[i][1], at[i][2]);
        phone(RESERVE, host++, -220, 80, GROUND).landed();
        phone(SCOUTS.get(0), host++, -220, 120, GROUND).landed();
        phone(SCOUTS.get(1), host++, -220, 160, GROUND).landed();
        pilot = new Thread(this::fly, "arduShowPilot");
        pilot.setDaemon(true);
        pilot.start();
    }

    private VirtualFleetPhone phone(String name, int host, double e, double n, double alt) throws IOException {
        InetAddress bind = InetAddress.getByName("127.0.0." + host);
        VirtualFleetPhone p = new VirtualFleetPhone(name, bind, lat, lon, GROUND, e, n, alt, key);
        p.copter.climb = 4;
        p.onOther(d -> answerName(name, p, d));
        phones.put(name, p);
        VirtualFleet.Drone node = new VirtualFleet.Drone(bind.getHostAddress(), p.address().getPort());
        announced.put(name, node);
        fleet.announceExtra(node);
        return p;
    }

    /** The window's fleet logic for the show (FleetOps with {@link #host()}). */
    public void setOps(Ops ops) {
        this.ops = ops;
    }

    public Ops ops() {
        return ops;
    }

    /** The drone the show "flies" now: the lead. */
    public String lead() {
        return lead;
    }

    public void newLead(String drone) {
        lead = drone;
    }

    public VirtualFleetPhone phone(String name) {
        VirtualFleetPhone p = phones.get(name);
        return p;
    }

    public InetSocketAddress addressOf(String name) {
        VirtualFleetPhone p = phones.get(name);
        return p == null ? null : p.address();
    }

    public String key() {
        return key;
    }

    public List<String> names() {
        return new ArrayList<>(phones.keySet());
    }

    public double[] place() {
        return new double[]{lat, lon};
    }

    /** Where DISPATCH sends the scouts: 400 m north of the show. */
    public double[] dispatchTarget() {
        return GeoLocal.offset(lat, lon, 0, 400);
    }

    /** The lead flies a slow loop out past the leash and back, so the group moves (FL-12). */
    private void fly() {
        double[][] legs = {{150, 0}, {260, 120}, {150, 260}, {20, 140}};
        int leg = 0;
        while (open) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            // the fleet logic's tick first, every round: calls, rally points (AC-10: a reserve's launch order waits for
            // its rally point and goes out on a tick) - it was skipped while the lead was a follower still, so after
            // DISCARD LEAD the tour's CALL RESERVE never launched
            Ops o = ops;
            if (o != null) {
                try {
                    o.tickReserves();
                } catch (RuntimeException ignored) {
                    // the show goes on
                }
            }
            VirtualFleetPhone p = phones.get(lead);
            if (p == null || p.isFollower()) continue;
            double[] to = legs[leg];
            p.copter.pilot(to[0], to[1], 85);
            if (Math.hypot(p.copter.e - to[0], p.copter.n - to[1]) < 8) leg = (leg + 1) % legs.length;
        }
    }

    /** A name query answered as a phone does (VirtualDrone's reply): its name, where it is, armed or not, NORMAL. */
    private void answerName(String name, VirtualFleetPhone p, DatagramPacket d) {
        byte[] b = java.util.Arrays.copyOf(d.getData(), d.getLength());
        if (b.length < 1 || b[0] != NAME_QUERY) return;
        if (!key.equals(de.droiddrone.pc.Names.keyOf(b, b.length))) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            double[] ll = p.latLon();
            out.writeByte(NAME_REPLY);
            out.writeUTF(key);
            out.writeByte(0);
            out.writeUTF(name);
            out.writeUTF(DEVICE);
            out.writeUTF("2.3.0-pc.58-fleet");
            out.writeByte(1); // where the drone is
            out.writeByte(1); // from its flight controller
            out.writeInt((int) Math.round(ll[0] * 1e7));
            out.writeInt((int) Math.round(ll[1] * 1e7));
            out.writeShort(0);
            out.writeByte(p.armed() ? 1 : 0);
            out.writeByte(name.equals(lead) ? 1 : 0); // flown: the show's lead
            out.writeByte(0); // no pilot address (the show flies it, not a PC)
            out.writeByte(0); // NORMAL
            new FleetProtocol.Status(80, false, 4, 1, 14, Short.MIN_VALUE /* temperature not known */, 0, 0, 0).writeTo(out);
            p.sendRaw(bytes.toByteArray(), new InetSocketAddress(d.getAddress(), d.getPort()));
        } catch (IOException ignored) {
            // the next query
        }
    }

    @Override
    public void close() {
        open = false;
        pilot.interrupt();
        Ops o = ops;
        if (o != null) o.close();
        for (Map.Entry<String, VirtualFleet.Drone> e : announced.entrySet()) fleet.withdrawExtra(e.getValue());
        for (VirtualFleetPhone p : phones.values()) p.close();
        log.accept("ArduPilot show ended.");
    }
}
