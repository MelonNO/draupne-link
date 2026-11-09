package de.droiddrone.pc.overwatch;

import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.UnitAssembler;
import de.droiddrone.pc.MonoClock;
import de.droiddrone.pc.map.CameraFootprint;
import de.droiddrone.pc.video.StreamRecorder;
import de.droiddrone.pc.video.VideoDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * This PC's side of OVERWATCH SLEEP (1.0.47): every drone it put to overwatch calls here when its camera sees
 * something. The link's receive threads (ControlLink, NameService's answer thread - whichever holds the link port)
 * hand each overwatch datagram over as it is ({@link #offer}: a copy into a queue, nothing more), so they never wait
 * for any of this. One thread of its own at the lowest priority does the rest: answers the alert, keeps the live
 * preview coming while its card is open, decodes it in software on one thread ({@link VideoDecoder#oneThread}),
 * records it to an MKV (the event's clip until the phone's own arrives), stores the 5 s before the alert, and pulls
 * the phone's full-quality clip once the motion has ended - never while a drone on this PC is armed
 * ({@link #setFlying}). It answers from a socket of its own to wherever the phone's packets came from.
 * <p>
 * The drones are known by the token this PC gave each with its OVERWATCH request ({@link #watch}); a datagram with
 * another token is dropped.
 */
public final class OverwatchHub implements AutoCloseable {
    static final long TICK_MS = 100;
    /** The card's picture is made at most this often (the preview comes at 10-15 a second; the card is a glance). */
    static final long PICTURE_EVERY_MS = 125;
    /** The card's picture at most this wide. */
    static final int PICTURE_MAX_W = 640;
    private static final int QUEUE_MAX = 4000;

    /** A drone this PC put to overwatch: its token, and how it stood and looked then (null: not known). */
    public record Watched(String drone, int token, CameraFootprint.Pose pose) {}

    /**
     * What the map window shows of one drone, once a refresh. picture: the latest preview picture (or pre-roll
     * picture), null before one; alertAtWall / endedAtWall: wall-clock times (endedAtWall -1 while it goes on);
     * held: why the live preview and the clip wait, in plain words (a drone here may be flying; 1.0.50), null when they
     * don't; preRolls: the pictures before the alert that came. boxes: the detector's boxes now ({x, y, w, h, light}),
     * from a pc.40 phone's live preview - null when none came lately (an older phone: the alert's box is all there is).
     */
    public record View(String drone, AlertCard.Shown shown, boolean ended, long alertAtWall, long endedAtWall,
                       BufferedImage picture, OverwatchProtocol.Alert alert, CameraFootprint.Pose pose, String clipLine,
                       boolean live, String held, int preRolls, float[][] boxes) {
        public View(String drone, AlertCard.Shown shown, boolean ended, long alertAtWall, long endedAtWall, BufferedImage picture,
                    OverwatchProtocol.Alert alert, CameraFootprint.Pose pose, String clipLine, boolean live, String held, int preRolls) {
            this(drone, shown, ended, alertAtWall, endedAtWall, picture, alert, pose, clipLine, live, held, preRolls, null);
        }
    }

    /** The phone's boxes count as now for this long (it sends them 4 a second, an empty set when nothing is seen). */
    static final long BOXES_FRESH_MS = 1500;

    public interface Listener {
        /** A new alert (or the same one again after a dismiss's 10 min): sound it and draw attention, unless flying. */
        void onNews(String drone);

        /** The phone left overwatch for deep sleep by itself (PowerFloor's reason). */
        void onDropped(String drone, int reason, int batteryPercent, int tempDeci);

        /** A clip came and checked, or an event's facts changed: the clip list again. */
        void onClips(String drone);

        void log(String line);
    }

    private final ClipIndex index;
    private final Map<Integer, Watched> byToken = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE_MAX);
    /**
     * The pilot's clicks (DISMISS, CONNECT, a badge closed): a queue of their own (1.0.49, M9), never full of a
     * preview's datagrams, so they never run on the caller's thread - the Swing thread - beside the hub's.
     */
    private final LinkedBlockingQueue<Runnable> controls = new LinkedBlockingQueue<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>(); // the hub thread's (tests: the caller's)
    private volatile List<View> views = List.of();
    /** A drone on this PC may be flying (PossiblyFlying, 1.0.49): no clip, no preview, alerts quietly. */
    private volatile java.util.function.BooleanSupplier flying = de.droiddrone.pc.PossiblyFlying::now;
    /** Why it may be flying, in plain words (PossiblyFlying's reason): the card says so. */
    private volatile java.util.function.Supplier<String> flyingWhy = de.droiddrone.pc.PossiblyFlying::reasonNow;
    private volatile boolean running;
    private volatile Listener listener;
    private Thread thread;
    private DatagramSocket socket;
    private final Consumer<byte[]> sendHook; // tests: what would be sent, instead of the socket
    private long dropped;

    private record In(byte[] data, InetAddress from, int port) {}

    public OverwatchHub(ClipIndex index) {
        this(index, null);
    }

    /** @param sendHook tests: every datagram this hub would send goes here instead (no socket is opened) */
    OverwatchHub(ClipIndex index, Consumer<byte[]> sendHook) {
        this.index = index;
        this.sendHook = sendHook;
    }

    public ClipIndex index() {
        return index;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        if (sendHook == null) socket = new DatagramSocket();
        running = true;
        thread = new Thread(this::run, "overwatch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY); // never before the sticks or the pilot's picture
        thread.start();
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            boolean wasRunning = running;
            running = false;
            t = thread;
            if (t != null) t.interrupt();
            thread = null;
            if (!wasRunning) cleanUp(); // no thread to do it (tests)
            if (socket != null) socket.close();
            socket = null;
        }
        // the thread's cleanUp closes the clip files: wait for it (outside the lock it may need), 1 s at most
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The drone was put to overwatch with this token (and stood like this). Kept until {@link #forget}. */
    public void watch(String drone, int token, CameraFootprint.Pose pose) {
        byToken.values().removeIf(w -> w.drone().equals(drone) && w.token() != token);
        byToken.put(token, new Watched(drone, token, pose));
    }

    public void forget(String drone) {
        byToken.values().removeIf(w -> w.drone().equals(drone));
    }

    public List<Watched> watched() {
        return new ArrayList<>(byToken.values());
    }

    /** Tests: whether a drone on this PC may be flying (else this PC's PossiblyFlying flag). */
    void setFlying(boolean armed) {
        flying = () -> armed;
        flyingWhy = () -> armed ? "a drone here may be flying" : null;
    }

    /** Which flag it reads (tests: one of their own; this PC's by default). */
    public void setFlag(de.droiddrone.pc.PossiblyFlying flag) {
        flying = flag::holds;
        flyingWhy = flag::reason;
    }

    /**
     * A drone on this PC may be flying (PossiblyFlying): no clip is pulled and no preview asked for meanwhile, and a
     * new alert comes quietly.
     */
    public boolean isFlying() {
        return flying.getAsBoolean();
    }

    /** Why it may be flying, in plain words. */
    private String heldWhy() {
        String why = flyingWhy.get();
        return why == null || why.isBlank() ? "a drone here may be flying" : why;
    }

    /**
     * From the link's receive threads: an overwatch datagram (OverwatchProtocol.isFromPhone). Copied and queued; a
     * full queue drops it (the phone sends again what matters).
     */
    public void offer(byte[] d, int len, InetAddress from, int port) {
        if (!running || !OverwatchProtocol.isFromPhone(d, len)) return;
        if (!queue.offer(new In(Arrays.copyOf(d, len), from, port))) dropped++;
    }

    public void dismiss(String drone) {
        task(() -> {
            Session s = sessions.get(drone);
            if (s == null) return;
            s.card.dismiss(now());
            send(s, OverwatchProtocol.command(s.token, s.event, OverwatchProtocol.CMD_DISMISS));
            log(drone + ": alert dismissed; silent until motion ends (max 10 min).");
            publish();
        });
    }

    /** CONNECT on the card: the phone wakes fully, and the caller connects to it as its pilot. */
    public void connect(String drone) {
        task(() -> {
            Session s = sessions.get(drone);
            if (s == null) return;
            for (int i = 0; i < 3; i++) send(s, OverwatchProtocol.command(s.token, s.event, OverwatchProtocol.CMD_CONNECT));
            s.card.dismiss(now()); // the pilot answers it: the card closes, the link takes over
            publish();
        });
    }

    /**
     * The drone gone from this PC altogether (1.0.52: the tour's drones at the tour's end): its watch forgotten, its
     * card and event taken off, its open files closed. Its clips on disk are the caller's to keep or delete.
     */
    public void drop(String drone) {
        forget(drone);
        task(() -> {
            Session s = sessions.remove(drone);
            if (s != null) {
                s.closeMedia();
                if (s.transfer != null) s.transfer.close();
                s.transfer = null;
            }
            publish();
        });
    }

    public void closeBadge(String drone) {
        task(() -> {
            Session s = sessions.get(drone);
            if (s != null) s.card.closeBadge();
            publish();
        });
    }

    /** Everything to show, as of the last tick. */
    public List<View> views() {
        return views;
    }

    private void task(Runnable r) {
        if (running) controls.add(r); // unbounded: the pilot's clicks are few
        else r.run(); // no thread (tests): at once
    }

    // ------------------------------------------------------------------ the hub's thread

    private void run() {
        long nextTick = 0;
        while (running) {
            try {
                for (Runnable r; (r = controls.poll()) != null; ) r.run(); // the pilot's clicks before the datagrams
                Object o = queue.poll(TICK_MS, TimeUnit.MILLISECONDS);
                if (o instanceof In in) handle(in.data(), in.from(), in.port());
                long now = now();
                if (now >= nextTick) {
                    nextTick = now + TICK_MS;
                    tick(now);
                }
            } catch (InterruptedException e) {
                if (!running) break;
            } catch (RuntimeException e) {
                log("Overwatch: " + e);
            }
        }
        cleanUp();
    }

    private void cleanUp() {
        for (Session s : sessions.values()) {
            s.closeMedia();
            if (s.transfer != null) s.transfer.close(); // the .part file stays: the next start goes on from it
            s.transfer = null;
        }
    }

    /** Tests: one datagram handled at once, on the caller's thread. */
    void handleNow(byte[] d, InetAddress from, int port) {
        handle(d, from, port);
    }

    /** Tests: one tick at a given time. */
    void tickNow(long now) {
        tick(now);
    }

    long nowOverride = -1;

    private long now() {
        return nowOverride >= 0 ? nowOverride : MonoClock.ms();
    }

    private void handle(byte[] d, InetAddress from, int port) {
        int token = OverwatchProtocol.token(d);
        Watched w = byToken.get(token);
        if (w == null) return; // not a drone this PC put to overwatch (another PC's, or an old token)
        Session s = sessions.computeIfAbsent(w.drone(), k -> new Session(w));
        s.token = token;
        s.watched = w;
        s.from = from;
        s.port = port;
        long now = now();
        switch (d[0]) {
            case OverwatchProtocol.ALERT -> onAlert(s, OverwatchProtocol.readAlert(d, d.length), now);
            case OverwatchProtocol.PREVIEW -> onFragment(s, OverwatchProtocol.readFragment(d, d.length), now);
            case OverwatchProtocol.CLIP -> onClip(s, d, now);
            case OverwatchProtocol.STATUS -> onStatus(s, OverwatchProtocol.readStatus(d, d.length));
            default -> {
            }
        }
    }

    private void onAlert(Session s, OverwatchProtocol.Alert a, long now) {
        if (a == null) return;
        send(s, OverwatchProtocol.command(s.token, a.event, OverwatchProtocol.CMD_ACK));
        boolean newEvent = a.event != s.event || s.id == null;
        if (newEvent) {
            s.closeMedia();
            s.id = null;
            s.endedSaid = false;
            s.live = false;
            s.event = a.event;
            s.alert = a;
            s.id = idForOrNull(s, a.event, a.startedAt);
            if (s.id == null) return;
            s.preRollSaved = 0;
            s.picture = null;
            s.boxes = null;
            s.boxesAt = Long.MIN_VALUE / 2;
            s.assembler.clear();
            saveMeta(s, a);
        }
        s.alert = a;
        boolean news = s.card.onAlert(a.event, a.alertNo, now);
        if (news) {
            log(s.drone() + ": MOTION " + (a.light() ? "(a light) " : "") + "- overwatch alert"
                    + (a.alertNo > 0 ? " again (still moving 10 min after dismiss)" : "") + ".");
            Listener l = listener;
            if (l != null) l.onNews(s.drone());
        }
        if (s.card.previewWanted(now) && !isFlying()) askPreview(s, now);
        publish();
    }

    private void askPreview(Session s, long now) {
        s.previewAskedAt = now;
        send(s, OverwatchProtocol.command(s.token, s.event, OverwatchProtocol.CMD_PREVIEW));
    }

    private void onFragment(Session s, OverwatchProtocol.Fragment f, long now) {
        if (f == null || f.event != s.event || s.id == null) return;
        s.card.onHeard(now);
        UnitAssembler.Unit u = s.assembler.add(f);
        if (u == null) return;
        BufferedImage before = s.picture;
        switch (u.type) {
            case OverwatchProtocol.UNIT_PREROLL -> savePreRoll(s, u.data);
            case OverwatchProtocol.UNIT_CONFIG -> {
                if (!Arrays.equals(s.config, u.data)) {
                    s.closeDecoder();
                    s.config = u.data;
                }
            }
            case OverwatchProtocol.UNIT_KEY_FRAME, OverwatchProtocol.UNIT_FRAME -> onFrame(s, u.data, u.type == OverwatchProtocol.UNIT_KEY_FRAME, now);
            case OverwatchProtocol.UNIT_BOXES -> {
                float[][] b = OverwatchProtocol.readBoxes(u.data);
                if (b != null) {
                    s.boxes = b;
                    s.boxesAt = now;
                    publish();
                }
            }
            default -> {
            }
        }
        if (s.picture != before) publish();
    }

    private void onFrame(Session s, byte[] frame, boolean key, long now) {
        if (s.config == null) return;
        if (s.decoder == null) {
            if (!key) return;
            try {
                s.decoder = VideoDecoder.oneThread(false, s.config);
            } catch (RuntimeException e) {
                log(s.drone() + ": preview can't be decoded: " + e.getMessage());
                return;
            }
        }
        boolean picture = s.decoder.decode(frame, frame.length);
        int w = s.decoder.width(), h = s.decoder.height();
        if (picture && w > 0 && h > 0 && s.recorder == null && key && s.id != null) {
            try {
                Path file = index.folder(s.drone()).resolve(s.id + "-preview.mkv");
                s.recorder = new StreamRecorder(file, false, w, h, s.config);
                s.recordStart = now;
            } catch (IOException e) {
                log(s.drone() + ": preview not recorded: " + e.getMessage());
                s.recordFailed = true;
            }
        }
        if (s.recorder != null) {
            try {
                s.recorder.write(frame, key, now);
                s.recordEnd = now;
            } catch (IOException e) {
                log(s.drone() + ": preview recording stopped: " + e.getMessage());
                s.closeRecorder();
            }
        }
        if (picture && w > 0 && h > 0 && now - s.pictureAt >= PICTURE_EVERY_MS) {
            s.pictureAt = now;
            int pw = Math.min(PICTURE_MAX_W, w), ph = Math.max(1, Math.round(h * (float) pw / w));
            BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
            try {
                s.decoder.convert(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), pw, ph);
                s.picture = img;
                s.live = true;
                s.liveAt = now;
            } catch (RuntimeException ignored) {
                // a picture that can't be converted: the last one stays
            }
        }
    }

    private void savePreRoll(Session s, byte[] unit) {
        if (unit.length < 9) return;
        long at = 0;
        for (int i = 0; i < 8; i++) at = (at << 8) | (unit[i] & 0xff);
        byte[] jpeg = Arrays.copyOfRange(unit, 8, unit.length);
        try {
            Path dir = index.folder(s.drone());
            Path f = dir.resolve(String.format(Locale.ROOT, "%s-pre-%013d.jpg", s.id, at));
            if (!Files.exists(f)) {
                Files.write(f, jpeg);
                s.preRollSaved++;
            }
            if (!s.live) { // until the live preview comes, the card shows the latest picture before the alert
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                if (img != null && (s.preRollShownAt <= at)) {
                    s.picture = img;
                    s.preRollShownAt = at;
                }
            }
        } catch (IOException e) {
            log(s.drone() + ": pre-alert picture not saved: " + e.getMessage());
        }
    }

    private void onClip(Session s, byte[] d, long now) {
        int type = OverwatchProtocol.clipType(d, d.length);
        // cut short: no type, or a chunk without its offset (each threw on the hub's thread, its tick skipped)
        if (type < 0 || type == OverwatchProtocol.CLIP_CHUNK && d.length < OverwatchProtocol.CHUNK_DATA_AT) return;
        int event = OverwatchProtocol.clipEvent(d);
        switch (type) {
            case OverwatchProtocol.CLIP_ENDED -> {
                long[] e = OverwatchProtocol.readClipEnded(d, d.length);
                send(s, OverwatchProtocol.command(s.token, event, OverwatchProtocol.CMD_ENDED_ACK));
                if (event != s.event || e == null) return;
                boolean first = !s.endedSaid;
                s.card.onEnded(now);
                if (first) {
                    s.endedSaid = true;
                    s.closeMedia();
                    updateMeta(s, m -> {
                        m.setProperty("endedAt", Long.toString(e[0]));
                        if (e[1] == 0) m.setProperty("clip", "none");
                    });
                    log(s.drone() + ": motion ended" + (e[1] != 0 ? "; clip comes " + (isFlying() ? "when no drone may be flying (" + heldWhy() + ")." : "now.") : "."));
                    Listener l = listener;
                    if (l != null) l.onClips(s.drone());
                }
                publish();
            }
            case OverwatchProtocol.CLIP_INFO -> onClipInfo(s, OverwatchProtocol.readClipInfo(d, d.length));
            case OverwatchProtocol.CLIP_CHUNK -> onChunk(s, d, event);
            case OverwatchProtocol.CLIP_NONE -> {
                String why = OverwatchProtocol.readClipNone(d, d.length);
                log(s.drone() + ": no full-quality clip (" + why + "); the preview is kept as the clip.");
            }
            default -> {
            }
        }
    }

    private void onClipInfo(Session s, OverwatchProtocol.ClipInfo info) {
        if (info == null) return;
        Transfer t = s.transfer;
        if (t != null && t.info.event == info.event && t.info.startedAt == info.startedAt && Arrays.equals(t.info.sha256, info.sha256)) return;
        if (t != null) t.close();
        s.transfer = null;
        try {
            // pc.38: an event comes in 60 s segments, all filed under the event (its start), each a part of its own
            String id = idFor(s, info.event, info.eventStartedAt);
            int part = Math.max(1, info.part);
            String sfx = ClipIndex.partSuffix(part);
            Path dir = index.folder(s.drone());
            Path done = dir.resolve(ClipIndex.partFile(id, part));
            String hex = hex(info.sha256);
            if (Files.isRegularFile(done) && hasChecked(s, id, part, done, info, hex)) {
                send(s, OverwatchProtocol.clipDone(s.token, info.event, true, info.startedAt)); // here already
                return;
            }
            ClipIndex.Clip known = index.clip(s.drone(), id);
            if (known != null && hex.equals(known.meta().getProperty(BAD_SHA + sfx))) {
                send(s, OverwatchProtocol.clipDone(s.token, info.event, false, info.startedAt)); // given up on: not again
                return;
            }
            ClipTransfer ct = new ClipTransfer(dir.resolve(ClipIndex.partFile(id, part) + ".part"), done, info.size, info.sha256);
            s.transfer = new Transfer(info, id, ct);
            updateMeta(s, id, m -> {
                m.setProperty("clipSize" + sfx, Long.toString(info.size));
                m.setProperty("clipDurationMs" + sfx, Long.toString(info.durationMs));
                m.setProperty("clipSound", Boolean.toString(info.sound));
                m.setProperty("clipSize.wh", info.width + "x" + info.height);
            });
            log(s.drone() + ": clip" + (part > 1 ? " (part " + part + ")" : "") + " (" + ClipIndex.sizeText(info.size)
                    + (ct.received() > 0 ? ", resuming at " + ClipIndex.sizeText(ct.received()) : "")
                    + ") comes" + (isFlying() ? " when no drone may be flying (" + heldWhy() + ")." : " now."));
        } catch (IOException e) {
            log(s.drone() + ": clip can't be stored: " + e.getMessage());
        }
    }

    private void onChunk(Session s, byte[] d, int event) {
        Transfer t = s.transfer;
        if (t == null || t.info.event != event) return;
        try {
            if (t.clip.checked()) return; // in and checked: only its rename is left (the tick tries it)
            t.clip.onChunk(OverwatchProtocol.chunkOffset(d), d, OverwatchProtocol.CHUNK_DATA_AT, d.length - OverwatchProtocol.CHUNK_DATA_AT);
            if (!t.clip.complete()) return;
        } catch (IOException e) {
            log(s.drone() + ": clip can't be written: " + e.getMessage());
            return;
        }
        finishClip(s, t, now());
    }

    /** All of the clip is in: checked once, then renamed (again every RENAME_RETRY_MS while the rename fails). */
    private void finishClip(Session s, Transfer t, long now) {
        t.renameTriedAt = now;
        int event = t.info.event;
        String sfx = ClipIndex.partSuffix(Math.max(1, t.info.part));
        ClipTransfer.Result r;
        try {
            r = t.clip.finish();
        } catch (IOException e) {
            if (!t.renameFailedSaid) log(s.drone() + ": clip checked but can't be renamed (" + e.getMessage() + "): retrying.");
            t.renameFailedSaid = true;
            return;
        }
        switch (r) {
            case OK -> {
                send(s, OverwatchProtocol.clipDone(s.token, event, true, t.info.startedAt));
                t.close();
                s.transfer = null;
                updateMeta(s, t.id, m -> {
                    m.setProperty("clip", "full");
                    m.setProperty(CHECKED_SHA + sfx, hex(t.info.sha256)); // the phone may announce it again: no second hashing
                });
                log(s.drone() + ": full-quality clip" + (t.info.part > 1 ? " (part " + t.info.part + ")" : "") + " is here ("
                        + ClipIndex.sizeText(t.info.size) + ", checked); phone deletes its copy.");
                Listener l = listener;
                if (l != null) l.onClips(s.drone());
            }
            case MISMATCH -> log(s.drone() + ": clip damaged (SHA-256 differs): fetching again (" + t.clip.mismatches()
                    + " of " + ClipTransfer.MAX_RETRIES + " retries).");
            case FAILED -> {
                send(s, OverwatchProtocol.clipDone(s.token, event, false, t.info.startedAt));
                t.close();
                s.transfer = null;
                updateMeta(s, t.id, m -> m.setProperty(BAD_SHA + sfx, hex(t.info.sha256)));
                log(s.drone() + ": clip damaged " + t.clip.mismatches() + " times: gave up. The preview is kept as the clip.");
                Listener l = listener;
                if (l != null) l.onClips(s.drone());
            }
        }
    }

    /** The event's facts: the SHA-256 of its clip (".2", ".3" ... added for later parts), checked when it came (1.0.49: no reading 50 MB again at every resend). */
    static final String CHECKED_SHA = "clipSha256";
    /** The SHA-256 of a clip given up on after its retries: announced again, it is not fetched again. */
    static final String BAD_SHA = "clipBadSha256";
    /** A checked clip whose rename failed is renamed again this often. */
    static final long RENAME_RETRY_MS = 1000;

    /**
     * The clip at done is this one: the hash checked when it came, in the event's facts, says so (and the size
     * agrees); a clip from 1.0.47 / 1.0.48 has none - it is hashed once and the hash kept.
     */
    private boolean hasChecked(Session s, String id, int part, Path done, OverwatchProtocol.ClipInfo info, String hex) throws IOException {
        String key = CHECKED_SHA + ClipIndex.partSuffix(part);
        ClipIndex.Clip c = index.clip(s.drone(), id);
        String stored = c == null ? null : c.meta().getProperty(key);
        if (stored != null) return stored.equals(hex) && Files.size(done) == info.size;
        byte[] actual = ClipTransfer.sha256(done);
        updateMeta(s, id, m -> m.setProperty(key, hex(actual)));
        return Arrays.equals(actual, info.sha256);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    private void onStatus(Session s, int[] st) {
        if (st == null) return;
        send(s, OverwatchProtocol.command(s.token, 0, OverwatchProtocol.CMD_STATUS_ACK));
        if (st[0] != OverwatchProtocol.STATE_DROPPED || s.droppedSaid) return;
        s.droppedSaid = true;
        Listener l = listener;
        if (l != null) l.onDropped(s.drone(), st[1], st[2], st[3]);
    }

    private void tick(long now) {
        for (Session s : sessions.values()) {
            boolean flies = isFlying(); // the uplink is the sticks' and the picture's meanwhile: no preview, no clip
            if (s.card.previewWanted(now) && now - s.previewAskedAt >= 1000 && !flies) askPreview(s, now);
            if (s.card.ended(now) && (s.recorder != null || s.decoder != null)) s.closeMedia(); // lost: what came is kept
            if (s.live && now - s.liveAt > 3000) s.live = false;
            Transfer t = s.transfer;
            if (t != null && t.clip.checked() && now - t.renameTriedAt >= RENAME_RETRY_MS) {
                finishClip(s, t, now); // in and checked, its rename failed: again (1.0.49; it stuck forever)
            } else if (t != null) {
                long[] ask = t.clip.next(now, flies);
                if (ask != null) send(s, OverwatchProtocol.command(s.token, t.info.event, OverwatchProtocol.CMD_CLIP_GET, ask[0], (int) ask[1]));
            }
        }
        publish();
    }

    private void publish() {
        long now = now();
        long wallOffset = System.currentTimeMillis() - now;
        boolean flies = isFlying();
        String held = flies ? heldWhy() : null;
        List<View> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            if (s.event < 0) continue;
            String clipLine = null;
            Transfer t = s.transfer;
            if (t != null) {
                clipLine = "Clip " + (flies ? "paused" : "coming") + ": " + ClipIndex.sizeText(t.clip.received()) + " of " + ClipIndex.sizeText(t.clip.size());
            }
            long ended = s.card.endedAt(now);
            out.add(new View(s.drone(), s.card.shown(now), s.card.ended(now), s.card.alertAt() + wallOffset, ended < 0 ? -1 : ended + wallOffset,
                    s.picture, s.alert, s.watched.pose(), clipLine, s.live, held, s.preRollSaved,
                    now - s.boxesAt <= BOXES_FRESH_MS ? s.boxes : null));
        }
        views = List.copyOf(out);
    }

    // ------------------------------------------------------------------ the event's files

    private String idForOrNull(Session s, int event, long startedAt) {
        try {
            return idFor(s, event, startedAt);
        } catch (IOException e) {
            log(s.drone() + ": event can't be stored: " + e.getMessage());
            return null;
        }
    }

    /** The id of this drone's event: the one whose facts name the phone's start time, else a new one. */
    private String idFor(Session s, int event, long startedAt) throws IOException {
        if (s.id != null && s.event == event && s.alert != null && s.alert.startedAt == startedAt) return s.id;
        String known = s.idsByStart.get(startedAt);
        if (known != null) return known;
        for (ClipIndex.Clip c : index.clips(s.drone(), true)) { // removed ones too: a removed event must not come back
            if (c.longOf("phoneStartedAt", -1) == startedAt) {
                s.idsByStart.put(startedAt, c.id());
                return c.id();
            }
        }
        String id = index.newId(s.drone(), startedAt);
        Properties m = new Properties();
        m.setProperty("drone", s.drone());
        m.setProperty("event", Integer.toString(event));
        m.setProperty("phoneStartedAt", Long.toString(startedAt));
        m.setProperty("startedAt", Long.toString(startedAt));
        index.save(s.drone(), id, m);
        s.idsByStart.put(startedAt, id);
        return id;
    }

    private void saveMeta(Session s, OverwatchProtocol.Alert a) {
        CameraFootprint.Pose p = s.watched.pose();
        updateMeta(s, m -> {
            m.setProperty("startedAt", Long.toString(a.startedAt));
            m.setProperty("phoneStartedAt", Long.toString(a.startedAt));
            m.setProperty("light", Boolean.toString(a.light()));
            m.setProperty("angleDeg", Float.toString(a.angleDeg));
            if (a.hfovDeg > 0) m.setProperty("hfovDeg", Float.toString(a.hfovDeg));
            if (p != null) {
                m.setProperty("lat", Double.toString(p.lat()));
                m.setProperty("lon", Double.toString(p.lon()));
                m.setProperty("altM", Double.toString(p.altM()));
                m.setProperty("headingDeg", Double.toString(p.headingDeg()));
                m.setProperty("pitchDeg", Double.toString(p.pitchDeg()));
                m.setProperty("rollDeg", Double.toString(p.rollDeg()));
                m.setProperty("tiltDeg", Double.toString(p.tiltDeg()));
                if (a.hfovDeg <= 0) m.setProperty("hfovDeg", Double.toString(p.hfovDeg()));
                m.setProperty("vfovDeg", Double.toString(p.vfovDeg()));
            } else if (a.hasPosition()) {
                m.setProperty("lat", Double.toString(a.latE7 / 1e7));
                m.setProperty("lon", Double.toString(a.lonE7 / 1e7));
            }
        });
    }

    private void updateMeta(Session s, Consumer<Properties> change) {
        if (s.id != null) updateMeta(s, s.id, change);
    }

    private void updateMeta(Session s, String id, Consumer<Properties> change) {
        try {
            ClipIndex.Clip c = index.clip(s.drone(), id);
            Properties m = c == null ? new Properties() : c.meta();
            change.accept(m);
            if (s.id != null && s.id.equals(id) && s.recordStart > 0 && s.recordEnd > s.recordStart) {
                m.setProperty("previewDurationMs", Long.toString(s.recordEnd - s.recordStart));
            }
            index.save(s.drone(), id, m);
        } catch (IOException e) {
            log(s.drone() + ": event details not saved: " + e.getMessage());
        }
    }

    private void send(Session s, byte[] p) {
        if (sendHook != null) {
            sendHook.accept(p);
            return;
        }
        DatagramSocket sock = socket;
        if (sock == null || s.from == null) return;
        try {
            sock.send(new DatagramPacket(p, p.length, s.from, s.port));
        } catch (IOException ignored) {
            // the phone's again in a moment
        }
    }

    private void log(String line) {
        Listener l = listener;
        if (l != null) l.log(line);
    }

    // ------------------------------------------------------------------ per drone

    private static final class Transfer {
        final OverwatchProtocol.ClipInfo info;
        final String id;
        final ClipTransfer clip;
        long renameTriedAt = Long.MIN_VALUE / 2;
        boolean renameFailedSaid;

        Transfer(OverwatchProtocol.ClipInfo info, String id, ClipTransfer clip) {
            this.info = info;
            this.id = id;
            this.clip = clip;
        }

        void close() {
            clip.close();
        }
    }

    private final class Session {
        Watched watched;
        int token;
        InetAddress from;
        int port;
        final AlertCard card = new AlertCard();
        final UnitAssembler assembler = new UnitAssembler();
        final Map<Long, String> idsByStart = new HashMap<>();
        int event = -1;
        String id;
        OverwatchProtocol.Alert alert;
        long previewAskedAt = Long.MIN_VALUE / 2;
        byte[] config;
        VideoDecoder decoder;
        StreamRecorder recorder;
        boolean recordFailed;
        long recordStart, recordEnd;
        volatile BufferedImage picture;
        long pictureAt = Long.MIN_VALUE / 2, preRollShownAt;
        boolean live;
        long liveAt;
        /** The phone's boxes now (pc.40, with the preview) and when they came. */
        float[][] boxes;
        long boxesAt = Long.MIN_VALUE / 2;
        int preRollSaved;
        boolean endedSaid, droppedSaid;
        Transfer transfer;

        Session(Watched w) {
            this.watched = w;
            this.token = w.token();
        }

        String drone() {
            return watched.drone();
        }

        void closeDecoder() {
            if (decoder != null) decoder.close();
            decoder = null;
        }

        void closeRecorder() {
            if (recorder == null) return;
            try {
                recorder.close();
            } catch (RuntimeException ignored) {
            }
            recorder = null;
        }

        void closeMedia() {
            boolean recorded = recorder != null;
            closeRecorder();
            closeDecoder();
            config = null;
            live = false;
            if (recorded && id != null) updateMeta(this, id, m -> { });
        }
    }

    /** Tests: datagrams dropped because the queue was full. */
    long dropped() {
        return dropped;
    }
}
