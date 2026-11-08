package de.droiddrone.pc;

import de.droiddrone.pc.fleet.VirtualFleet;
import de.droiddrone.pc.setup.Tailscale;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps the name map (1.0.35, {@link Names}) up to date: every SCAN_MS it asks each online device on the Tailscale
 * network who it is (NAME_QUERY on the link port) and files the answers. It names this PC (the first PC letter
 * not seen), and when this PC is the master - the pilot PC with the highest letter online - it names each phone
 * that answers without a name (NAME_ASSIGN, the first free Mobile letter). It also answers other PCs' queries from
 * the link port while no link is open; the open link answers them itself ({@link ControlLink#setNameReply}).
 */
public final class NameService {
    static final long SCAN_MS = 5000;
    /** A reply later than this after its query is not waited for. */
    private static final int REPLY_WAIT_MS = 1500;

    private final PcConfig config;
    private final Names names;
    private final Supplier<Tailscale.Status> status;
    private final int port;
    private final boolean answerOnLinkPort;
    private final Consumer<String> log;
    private final Consumer<java.util.Map<String, String>> saver;
    private final String device;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile long scanNow;
    private Thread thread;
    private DatagramSocket answerSocket;
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean master;

    /**
     * @param status            this PC's view of the Tailscale network (Tailscale::status; a fake in tests)
     * @param port              the link port the devices listen on
     * @param answerOnLinkPort  answer other PCs' queries from the link port while no link is open (off in tests)
     * @param saver             writes settings (this PC's name, the map)
     */
    public NameService(PcConfig config, Names names, Supplier<Tailscale.Status> status, int port, boolean answerOnLinkPort,
                       Consumer<java.util.Map<String, String>> saver, Consumer<String> log) {
        this.config = config;
        this.names = names;
        this.status = status;
        this.port = port;
        this.answerOnLinkPort = answerOnLinkPort;
        this.saver = saver;
        this.log = log;
        String host = "";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException ignored) {
        }
        this.device = host;
    }

    /** Called (on the service thread) after every scan: the LINK card's list, the name map window. */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public String ownName() {
        return config.getString(Names.PC_NAME_KEY);
    }

    /** This PC is the master: the pilot PC with the highest letter online (as of the last scan). */
    public boolean isMaster() {
        return master;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "names");
        thread.setDaemon(true);
        thread.start();
        // 1.0.47: the link port's answers on a thread of their own - the scan waits 1.5 s for replies, and an
        // overwatch phone's preview or clip arriving meanwhile overflowed the socket's buffer
        answerThread = new Thread(this::answerRun, "linkPortAnswers");
        answerThread.setDaemon(true);
        answerThread.start();
    }

    private Thread answerThread;

    private void answerRun() {
        while (running) {
            try {
                if (!answerOnLinkPort || paused || !answerQueries()) Thread.sleep(200); // no port (the link has it): again shortly
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                log.accept("Names: " + e);
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (answerThread != null) answerThread.interrupt();
        if (thread != null) thread.interrupt();
        closeAnswerSocket();
    }

    /** A scan now (the pilot opened the list). */
    public void scanSoon() {
        scanNow = System.currentTimeMillis();
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /**
     * The pilot renamed a phone in the name map: NAME_ASSIGN to it (a phone keeps its name itself, so it must be
     * online), then a scan, which files its answer under the new name.
     */
    public void assign(String ip, String name) {
        byte[] assign = Names.assign(config.getKey(), name);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(assign, assign.length, InetAddress.getByName(ip), VirtualFleet.portOf(ip, port)));
            log.accept("Names: phone at " + ip + " renamed to " + name + ".");
        } catch (IOException e) {
            log.accept("Names: rename failed for phone at " + ip + ": " + e);
        }
        scanSoon();
    }

    /**
     * A sleeping phone's check-in (SLEEP_HELLO, phone app pc.22 on) reaching this PC while no link holds the port: the
     * handler gets the sender's address and the packet, and says whether the phone is to wake (true), sleep on
     * (false), or gets no answer (null). Before 1.0.41 nothing answered here, so a phone put to deep sleep before a
     * DISCONNECT had nothing to wake it but a CONNECT that happened to land in one of its 20 s windows.
     */
    public void setSleepHandler(java.util.function.BiFunction<String, byte[], Boolean> handler) {
        sleepHandler = handler;
    }

    private volatile java.util.function.BiFunction<String, byte[], Boolean> sleepHandler;

    /**
     * Where each phone's drone is (phone app pc.32 on: its NAME_REPLY says), by the phone's name, as of the last scan
     * that heard it; for the map's view of every drone on the network. at = when it was heard (wall clock).
     */
    public record DronePosition(String name, Names.Position position, long at) {}

    private final java.util.Map<String, DronePosition> positions = new java.util.concurrent.ConcurrentHashMap<>();

    /** Every phone's last word on where its drone is; kept after it falls silent (the map shows how old). */
    public java.util.List<DronePosition> dronePositions() {
        return new ArrayList<>(positions.values());
    }

    /** The host name the name map files a virtual drone under. */
    static final String VIRTUAL_HOST = "virtual fleet";
    /** Where the virtual fleet's drones are (VirtualFleet.announced: none while no fleet runs; a fake in tests). */
    private volatile Supplier<List<VirtualFleet.Drone>> virtualDrones = VirtualFleet::announced;

    void setVirtualDrones(Supplier<List<VirtualFleet.Drone>> drones) {
        virtualDrones = drones;
    }

    /**
     * 1.0.47: a phone's overwatch packets reaching the link port while no link holds it (OverwatchHub.offer). They
     * can come fast (the live preview, a clip), so the port is read on a thread of its own, with room for a burst.
     */
    private volatile ControlLink.OverwatchSink overwatchSink;

    public void setOverwatchSink(ControlLink.OverwatchSink sink) {
        overwatchSink = sink;
    }

    /** The link is about to open on the link port: the answering socket lets go of it. */
    public void pause() {
        paused = true;
        closeAnswerSocket();
    }

    /** The link is closed: the link port is free to answer queries from again. */
    public void resume() {
        paused = false;
    }

    private synchronized void closeAnswerSocket() {
        DatagramSocket s = answerSocket;
        answerSocket = null;
        if (s != null) s.close();
    }

    /** The reply this PC gives to a query. */
    public byte[] replyPacket() {
        return Names.reply(config.getKey(), Names.Kind.PC, ownName(), device, AppInfo.version());
    }

    private void run() {
        long nextScan = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextScan || scanNow > 0) {
                    scanNow = 0;
                    nextScan = now + SCAN_MS;
                    scan();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    if (!running) return;
                }
            } catch (RuntimeException e) {
                log.accept("Names: " + e);
            }
        }
    }

    /** One round: ask every online device, file the answers, name this PC and (as master) the unnamed phones. */
    void scan() {
        Tailscale.Status s;
        try {
            s = status.get();
        } catch (RuntimeException e) {
            return;
        }
        // the virtual fleet's drones on this PC's loopback, while the fleet program runs and announces them (empty
        // otherwise: then nothing here is any different)
        List<VirtualFleet.Drone> virtual = virtualDrones.get();
        boolean tailscale = s != null && s.installed() && s.signedIn();
        if (!tailscale && virtual.isEmpty()) {
            names.onlineOnly(Set.of());
            changed();
            return;
        }
        List<Tailscale.Peer> online = new ArrayList<>();
        if (tailscale) for (Tailscale.Peer p : s.peers()) if (p.online() && p.ip() != null && !p.ip().isEmpty()) online.add(p);
        java.util.Map<String, Integer> ports = new java.util.HashMap<>();
        for (VirtualFleet.Drone d : virtual) {
            online.add(new Tailscale.Peer(VIRTUAL_HOST, "", true, d.ip(), "", ""));
            ports.put(d.ip(), d.port());
        }
        Set<String> onlineIps = new HashSet<>();
        Set<String> onlinePcNames = new HashSet<>();
        List<String> unnamedPhones = new ArrayList<>();
        java.util.Map<String, List<String>> claims = new java.util.LinkedHashMap<>(); // name (lower case) -> the addresses answering with it
        String key = config.getKey();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(100);
            byte[] query = Names.query(key);
            for (Tailscale.Peer p : online) {
                try {
                    socket.send(new DatagramPacket(query, query.length, InetAddress.getByName(p.ip()), ports.getOrDefault(p.ip(), port)));
                } catch (IOException ignored) {
                    // an address that can't be reached: no entry
                }
            }
            long end = System.currentTimeMillis() + REPLY_WAIT_MS;
            byte[] buf = new byte[512];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (System.currentTimeMillis() < end) {
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Names.Reply reply = Names.parseReply(packet.getData(), packet.getLength(), key);
                if (reply == null) continue;
                String ip = packet.getAddress().getHostAddress();
                onlineIps.add(ip);
                String host = "";
                for (Tailscale.Peer p : online) if (p.ip().equals(ip)) host = p.hostName();
                if (reply.name().isEmpty()) {
                    if (reply.kind() == Names.Kind.PHONE) unnamedPhones.add(ip);
                    continue;
                }
                if (reply.kind() == Names.Kind.PC) onlinePcNames.add(reply.name());
                List<String> claimed = claims.computeIfAbsent(reply.name().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>());
                if (!claimed.contains(ip)) claimed.add(ip);
                if (claimed.size() > 1) continue; // a second device with this name: not filed (M3) - see duplicates()
                if (reply.kind() == Names.Kind.PHONE && reply.position() != null) {
                    positions.put(reply.name(), new DronePosition(reply.name(), reply.position(), System.currentTimeMillis()));
                }
                String moved = names.seen(reply, ip, host);
                if (moved != null) log.accept("Names: " + reply.name() + " now at " + ip + " (was " + moved + ").");
            }
            names.onlineOnly(onlineIps);
            noteDuplicates(claims);
            // this PC's own name: the first PC letter nobody else online has
            String own = ownName();
            if (own.isEmpty() || Names.letterOf(own) < 0 || onlinePcNames.stream().anyMatch(own::equalsIgnoreCase)) {
                String free = names.firstFree(Names.Kind.PC, onlinePcNames);
                if (free != null) {
                    saver.accept(java.util.Map.of(Names.PC_NAME_KEY, free));
                    log.accept("This PC is " + free + (own.isEmpty() ? "." : " (" + own + " was taken)."));
                    own = free;
                }
            }
            master = names.isMaster(own);
            if (master) {
                List<String> given = new ArrayList<>();
                for (String ip : unnamedPhones) {
                    String name = names.firstFree(Names.Kind.PHONE, given);
                    if (name == null) break;
                    given.add(name);
                    byte[] assign = Names.assign(key, name);
                    try {
                        socket.send(new DatagramPacket(assign, assign.length, InetAddress.getByName(ip), ports.getOrDefault(ip, port)));
                        log.accept("Names: phone at " + ip + " is " + name + ".");
                    } catch (IOException ignored) {
                    }
                }
                if (!given.isEmpty()) scanNow = System.currentTimeMillis(); // the named phones answer with their names at once
            }
        } catch (IOException e) {
            log.accept("Names: " + e);
        }
        changed();
    }

    /**
     * Names more than one device answered with in the last scan (M3, 1.0.49): two PCs that each thought they were the
     * master can each have named a phone MobileAlpha. Name (as answered) -> every address that answered with it, the
     * one filed in the map first. CONNECT refuses such a name until one of them is renamed. A duplicate stays known
     * for {@link #DUPLICATE_KEEP_MS} after the last scan that heard it: one lost reply doesn't clear it.
     */
    private volatile java.util.Map<String, Duplicate> duplicates = java.util.Map.of();
    static final long DUPLICATE_KEEP_MS = 30_000;

    private record Duplicate(List<String> ips, long at) {}

    /** The addresses answering with this name (more than one), or null when only one device has it. */
    public List<String> duplicateOf(String name) {
        Duplicate d = name == null ? null : duplicates.get(name.toLowerCase(java.util.Locale.ROOT));
        return d == null ? null : d.ips();
    }

    void noteDuplicates(java.util.Map<String, List<String>> claims) {
        long now = MonoClock.ms();
        java.util.Map<String, Duplicate> next = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Duplicate> d : duplicates.entrySet()) {
            List<String> heard = claims.getOrDefault(d.getKey(), List.of());
            int others = 0; // the duplicate's addresses heard now with another name: renamed
            for (String ip : d.getValue().ips()) {
                for (java.util.Map.Entry<String, List<String>> c : claims.entrySet()) {
                    if (!c.getKey().equals(d.getKey()) && c.getValue().contains(ip)) others++;
                }
            }
            boolean resolved = heard.size() <= 1 && others >= d.getValue().ips().size() - 1;
            if (!resolved && now - d.getValue().at() < DUPLICATE_KEEP_MS) next.put(d.getKey(), d.getValue());
        }
        for (java.util.Map.Entry<String, List<String>> c : claims.entrySet()) {
            if (c.getValue().size() < 2) continue;
            if (!next.containsKey(c.getKey())) {
                Names.Entry e = names.get(c.getKey());
                log.accept("Names: " + (e == null ? c.getKey() : e.name()) + " answers from " + String.join(" and ", c.getValue())
                        + " - two devices, one name. CONNECT refused until one is renamed.");
            }
            next.put(c.getKey(), new Duplicate(List.copyOf(c.getValue()), now));
        }
        duplicates = java.util.Map.copyOf(next);
    }

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    /**
     * Other PCs' queries, sleeping phones' check-ins and (1.0.47) overwatch packets on the link port while no link
     * holds it: read until 200 ms pass without one. False when the port can't be had.
     */
    private boolean answerQueries() {
        DatagramSocket s;
        synchronized (this) {
            if (answerSocket == null) {
                try {
                    answerSocket = new DatagramSocket(port);
                    answerSocket.setSoTimeout(200);
                    answerSocket.setReceiveBufferSize(1024 * 1024);
                } catch (IOException e) {
                    return false; // the link has the port (or something else does): again shortly
                }
                if (paused) { // the link asked for the port while this one was being opened
                    closeAnswerSocket();
                    return false;
                }
            }
            s = answerSocket;
        }
        byte[] buf = new byte[1200];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running && !paused) {
            try {
                packet.setLength(buf.length);
                s.receive(packet);
            } catch (SocketTimeoutException e) {
                return true;
            } catch (IOException e) {
                return true;
            }
            if (packet.getLength() >= 6 && packet.getData()[0] == ControlLink.SLEEP_HELLO) {
                answerSleepCheck(s, packet);
                continue;
            }
            if (de.droiddrone.common.OverwatchProtocol.isFromPhone(packet.getData(), packet.getLength())) {
                ControlLink.OverwatchSink sink = overwatchSink;
                if (sink != null) sink.offer(packet.getData(), packet.getLength(), packet.getAddress(), packet.getPort());
                continue;
            }
            answer(s, packet, config.getKey(), this::replyPacket);
        }
        return true;
    }

    private void answerSleepCheck(DatagramSocket s, DatagramPacket packet) {
        java.util.function.BiFunction<String, byte[], Boolean> h = sleepHandler;
        if (h == null) return;
        Boolean wake = h.apply(packet.getAddress().getHostAddress(), java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
        if (wake == null) return;
        byte[] answer = {ControlLink.SLEEP_ANSWER, (byte) (wake ? 1 : 0)};
        try {
            s.send(new DatagramPacket(answer, answer.length, packet.getAddress(), packet.getPort()));
        } catch (IOException ignored) {
        }
    }

    /** A NAME_QUERY with this key from anyone: the reply back to the sender. Used by the open link too. */
    static void answer(DatagramSocket socket, DatagramPacket packet, String key, Supplier<byte[]> reply) {
        byte[] d = packet.getData();
        int len = packet.getLength();
        if (len < 1 || d[0] != Names.NAME_QUERY || !key.equals(Names.keyOf(d, len))) return;
        byte[] r = reply.get();
        try {
            socket.send(new DatagramPacket(r, r.length, packet.getAddress(), packet.getPort()));
        } catch (IOException ignored) {
        }
    }
}
