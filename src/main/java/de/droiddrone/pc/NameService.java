package de.droiddrone.pc;

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
    }

    public synchronized void stop() {
        running = false;
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
            socket.send(new DatagramPacket(assign, assign.length, InetAddress.getByName(ip), port));
            log.accept("Names: the phone at " + ip + " is " + name + " now (renamed).");
        } catch (IOException e) {
            log.accept("Names: could not rename the phone at " + ip + ": " + e);
        }
        scanSoon();
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
                answerQueries();
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
        if (s == null || !s.installed() || !s.signedIn()) {
            names.onlineOnly(Set.of());
            changed();
            return;
        }
        List<Tailscale.Peer> online = new ArrayList<>();
        for (Tailscale.Peer p : s.peers()) if (p.online() && p.ip() != null && !p.ip().isEmpty()) online.add(p);
        Set<String> onlineIps = new HashSet<>();
        Set<String> onlinePcNames = new HashSet<>();
        List<String> unnamedPhones = new ArrayList<>();
        String key = config.getKey();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(100);
            byte[] query = Names.query(key);
            for (Tailscale.Peer p : online) {
                try {
                    socket.send(new DatagramPacket(query, query.length, InetAddress.getByName(p.ip()), port));
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
                String moved = names.seen(reply, ip, host);
                if (moved != null) log.accept("Names: " + reply.name() + " answers from " + ip + " now (it was " + moved + ").");
            }
            names.onlineOnly(onlineIps);
            // this PC's own name: the first PC letter nobody else online has
            String own = ownName();
            if (own.isEmpty() || Names.letterOf(own) < 0 || onlinePcNames.stream().anyMatch(own::equalsIgnoreCase)) {
                String free = names.firstFree(Names.Kind.PC, onlinePcNames);
                if (free != null) {
                    saver.accept(java.util.Map.of(Names.PC_NAME_KEY, free));
                    log.accept("This PC is " + free + (own.isEmpty() ? "." : " now (" + own + " was taken)."));
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
                        socket.send(new DatagramPacket(assign, assign.length, InetAddress.getByName(ip), port));
                        log.accept("Names: the phone at " + ip + " is " + name + " now.");
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

    private void changed() {
        for (Runnable r : listeners) r.run();
    }

    /** Other PCs' queries on the link port, while no link holds it. */
    private void answerQueries() {
        if (!answerOnLinkPort || paused) return;
        DatagramSocket s;
        synchronized (this) {
            if (answerSocket == null) {
                try {
                    answerSocket = new DatagramSocket(port);
                    answerSocket.setSoTimeout(50);
                } catch (IOException e) {
                    return; // the link has the port (or something else does): again next round
                }
            }
            s = answerSocket;
        }
        byte[] buf = new byte[512];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        for (int i = 0; i < 20; i++) {
            try {
                s.receive(packet);
            } catch (SocketTimeoutException e) {
                return;
            } catch (IOException e) {
                return;
            }
            answer(s, packet, config.getKey(), this::replyPacket);
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
