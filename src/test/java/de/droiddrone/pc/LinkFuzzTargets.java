package de.droiddrone.pc;

import de.droiddrone.bench.fuzz.LinkInput;
import de.droiddrone.bench.fuzz.LinkInput.Packet;
import de.droiddrone.bench.fuzz.LinkInput.Target;
import de.droiddrone.common.Bytes;
import de.droiddrone.common.DataReader;
import de.droiddrone.common.DetectionAreas;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.Fec;
import de.droiddrone.common.FecSender;
import de.droiddrone.common.FleetGate;
import de.droiddrone.common.FleetProtocol;
import de.droiddrone.common.NameAssignGate;
import de.droiddrone.common.Observe;
import de.droiddrone.common.OverwatchProtocol;
import de.droiddrone.common.PacketIds;
import de.droiddrone.common.PcAudio;
import de.droiddrone.common.RcPath;
import de.droiddrone.common.ReceiverBuffer;
import de.droiddrone.common.SavedPacket;
import de.droiddrone.common.SessionGate;
import de.droiddrone.common.SleepWake;
import de.droiddrone.common.TelemetryData;
import de.droiddrone.common.TelemetryRecords;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.common.UdpPacketData;
import de.droiddrone.common.UdpSender;
import de.droiddrone.common.UnitAssembler;
import de.droiddrone.common.UpdateSource;
import de.droiddrone.pc.video.FrameAssembler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The protocol fuzz's targets ({@code de.droiddrone.bench.fuzz.ProtocolFuzz}): each of the link's receive paths fed a
 * stream of datagrams the way its receive thread hands them on, and checked against what the code promises - not only
 * "no throw", but no session without the key, no numbered packet acted on twice, no stick frame taken that is not newer
 * than the last one, no whole video frame that differs from the one sent, the readers' values inside their stated
 * ranges. Seeds ({@link #seeds}): valid streams of every kind, made by the apps' own senders (ControlLink's builders and
 * the transport's UdpSender for what the PC sends; the transport, TelemetryRecords and FecSender for what the phone
 * sends; the protocol classes' own encoders for the rest).
 * <p>
 * Both directions. The PC's side is its real code: ControlLink's receive thread runs as it is, on a socket that hands
 * it the input's datagrams, and its buffer thread's work is done after each. The phone's receive thread is Android
 * code; its rules live in Common (SessionGate, RcPath, ReceiverBuffer, the gates and the readers), and
 * {@link PhoneModel} calls them in the order the phone's Udp.receiverRun does (flight-app/overlay, its receive thread
 * and processData).
 * <p>
 * Nothing leaves the process: the sockets are stand-ins that only collect what is sent. Single-threaded but for the
 * transport's resend timer, which sends into the stand-in.
 */
public final class LinkFuzzTargets {
    public static final String KEY = "fuzz-key";
    static final InetAddress PC_IP = address(1), PHONE_IP = address(2), OTHER_IP = address(3);
    /** Made-up ports, as the addresses: nothing is bound, every socket here is a stand-in. */
    static final int PC_PORT = 290, PHONE_PORT = 286, OTHER_PORT = 291;

    // flags of an input (bit 0 is LinkInput.BYTES_INTACT)
    /** The phone: its flight controller is known DISARMED. */
    public static final int PHONE_DISARMED = 2;
    /** The phone listens asleep (deep sleep's window). */
    public static final int PHONE_ASLEEP = 4;
    /** The phone is in standby: stick frames are dropped. */
    public static final int PHONE_STANDBY = 8;
    /** The PC's frame assembler waits for whole key frames (bad coverage mode). */
    public static final int VIDEO_WAIT_KEY = 2;

    private LinkFuzzTargets() {
    }

    /** What a run of one input found and did. */
    public interface Report {
        /** An exception out of a reader or a receive path that should never throw. */
        void exception(String where, Throwable t);

        /**
         * An exception a receive thread's own catch takes, losing only the damaged packet (the PC's and the phone's
         * buffer threads reading a packet cut short): listed, not a failure.
         */
        void caught(String where, String what);

        /** A promise of the code broken. */
        void broken(String rule, String detail);

        /** What the valid path did (a session up, sticks taken, a frame whole): counted, so a run shows its inputs got in. */
        void count(String what);
    }

    private static InetAddress address(int last) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, (byte) last}); // TEST-NET-1: never routed
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static InetAddress ipOf(int from) {
        return from == LinkInput.FROM_OTHER ? OTHER_IP : PC_IP;
    }

    // ================================================================================================ run

    /** Runs one input through its target. */
    public static void run(LinkInput in, Report report) {
        switch (in.target()) {
            case PHONE_LINK, PHONE_OUTSIDE -> new PhoneModel(in, report).run();
            case PC_LINK -> new PcModel(in, report).run();
            case PC_VIDEO -> video(in, report);
            case PC_TELEMETRY -> telemetry(in, report);
            case PC_OUTSIDE -> pcOutside(in, report);
        }
        logs(report); // what the transport's timer said meanwhile
    }

    // ================================================================================================ the app's log

    private static final ConcurrentLinkedQueue<String> LOG = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final int LOG_MAX = 2000;

    /** The app's log (Common's Log.log writes to System.out) into a queue from now on; returns what System.out was. */
    public static synchronized PrintStream captureLogs() {
        PrintStream was = System.out;
        System.setOut(new PrintStream(new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public synchronized void write(int b) {
                if (b == '\n') {
                    if (LOGGED.incrementAndGet() <= LOG_MAX) LOG.add(line.toString());
                    line.setLength(0);
                } else if (b != '\r' && line.length() < 2000) {
                    line.append((char) (b & 0xff));
                }
            }
        }, true, StandardCharsets.ISO_8859_1));
        return was;
    }

    public static synchronized void restoreLogs(PrintStream was) {
        System.setOut(was);
    }

    /**
     * The log lines since the last look: one that carries an exception is a receive thread's catch at work (the
     * phone's and the PC's threads log "Receiver error: ...", the buffer "checkPacket error: ...").
     */
    static void logs(Report report) {
        String line;
        while ((line = LOG.poll()) != null) {
            LOGGED.decrementAndGet();
            if (!line.matches(".*\\b[a-zA-Z_.]*(Exception|Error)\\b.*")) continue;
            // ReceiverBuffer.checkPacket reads the transport's own packets (ping, pong, acknowledgement) in a catch of its
            // own: one cut short is passed on as it is, and read again by processData, which the checks see themselves
            if (line.startsWith("checkPacket error:")) report.caught("ReceiverBuffer.checkPacket", line.replaceAll("\\d+", "#"));
            else report.exception("logged by a receive thread", new Logged(line));
        }
    }

    /** A log line that says an exception was caught, as a throwable for the report. */
    public static final class Logged extends RuntimeException {
        Logged(String line) {
            super(line, null, false, false);
        }
    }

    // ================================================================================================ the stand-in socket

    /** A socket that never touches the network: what is sent is counted (and kept, a few), what is received is handed in. */
    static final class FakeSocket extends DatagramSocket {
        final List<byte[]> sent = new ArrayList<>();
        int sends;
        Feeder feeder;

        interface Feeder {
            void next(DatagramPacket p) throws IOException;
        }

        FakeSocket() throws SocketException {
            super((SocketAddress) null); // unbound: no port taken, nothing sent
        }

        @Override
        public synchronized void send(DatagramPacket p) {
            sends++;
            if (sent.size() < 2000) sent.add(Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength()));
        }

        @Override
        public void receive(DatagramPacket p) throws IOException {
            Feeder f = feeder;
            if (f == null) throw new SocketException("no input");
            f.next(p);
        }

        synchronized List<byte[]> takeSent() {
            List<byte[]> out = new ArrayList<>(sent);
            sent.clear();
            return out;
        }

        static FakeSocket create() {
            try {
                return new FakeSocket();
            } catch (SocketException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A datagram as a socket hands it on: its bytes in the receive buffer, the length short of it. */
    static DatagramPacket datagram(byte[] d, InetAddress ip, int port) {
        byte[] buf = Arrays.copyOf(d, Math.max(d.length, UdpCommon.packetLength + 64));
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        p.setLength(d.length);
        p.setAddress(ip);
        p.setPort(port);
        return p;
    }

    // ================================================================================================ independent checks

    /** The key a packet carries at keyAt ([len u16][bytes]) is this one's, byte for byte (UTF-8 as both apps write it). */
    static boolean keyBytes(byte[] d, int len, int keyAt) {
        byte[] k = KEY.getBytes(StandardCharsets.UTF_8);
        if (len < keyAt + 2 + k.length) return false;
        if ((((d[keyAt] & 0xff) << 8) | (d[keyAt + 1] & 0xff)) != k.length) return false;
        for (int i = 0; i < k.length; i++) if (d[keyAt + 2 + i] != k[i]) return false;
        return true;
    }

    /**
     * The key at keyAt, read as Java's DataInputStream reads it (modified UTF-8: it also takes a letter written in two
     * bytes): some of the readers do, so a key written so is this one's there too - no weakness, the key is known.
     */
    static boolean keyRead(byte[] d, int len, int keyAt) {
        if (len < keyAt + 2) return false;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(d, keyAt, len - keyAt))) {
            return KEY.equals(in.readUTF());
        } catch (IOException e) {
            return false;
        }
    }

    // ================================================================================================ the phone

    /**
     * The phone's receive thread (flight-app/overlay/Flight/.../Udp.java: receiverRun, handleRcFrame, answerName,
     * onFleetMode, onObserve, otherPcWhileConnected, newSession, and processData for what the buffer hands on), in that
     * order, with Common's classes for every decision; the Android parts (camera, power modes, the flight controller)
     * are left out. Direct mode (not over upstream's relay): the buffer is the server.
     */
    static final class PhoneModel {
        final LinkInput in;
        final Report report;
        final SessionGate gate = new SessionGate(KEY);
        final FakeSocket socket = FakeSocket.create();
        final UdpSender udpSender = new UdpSender(socket);
        ReceiverBuffer buffer;
        final RcPath rcPath = new RcPath();
        final boolean disarmed, asleep, standby;
        InetAddress lastPcIp;
        int lastPcPort;
        long now = 100_000;
        int fleetToken;
        short[] idleSticks;
        /** NAME_QUERY with the key: who asked this phone's name, when (KnownPcs). */
        final Map<InetAddress, Long> askedName = new HashMap<>();
        // the checks' memory
        /**
         * What the buffer handed on in its session, by number (the last packet under each): anew only with a new session
         * (a new buffer, or the buffer's Connect while it was down). A numbered packet is never handed on twice - the
         * same bytes under the same number again - without exceptions: the far side's resends of a packet whose
         * acknowledgement was lost, copies, replays, all (F-111: the ninth resend started the buffer over and the packet
         * was handed on again). Another packet under a number taken is the far side's numbering started over, which
         * the buffer mends (recoverCounter): handed on, it is the one under that number from then on.
         */
        Map<Short, byte[]> delivered = new HashMap<>();
        /** The last stick frame taken: its layout (bytes after the channels), sequence, time; -1: none in this session. */
        int lastTail = -1, lastStickSeq;
        long lastStickAt;
        byte[] lastStick;

        PhoneModel(LinkInput in, Report report) {
            this.in = in;
            this.report = report;
            disarmed = (in.flags() & PHONE_DISARMED) != 0;
            asleep = (in.flags() & PHONE_ASLEEP) != 0;
            standby = (in.flags() & PHONE_STANDBY) != 0;
            buffer = newBuffer();
        }

        ReceiverBuffer newBuffer() {
            return new ReceiverBuffer(udpSender, true, KEY, null, () -> now);
        }

        void run() {
            try {
                for (Packet p : in.packets()) {
                    now += Math.max(0, p.dtMs());
                    byte[] d = p.data().clone();
                    int port = p.from() == LinkInput.FROM_PEER ? PC_PORT : p.from() == LinkInput.FROM_PEER_ADDRESS ? PC_PORT + 1 : OTHER_PORT;
                    InetAddress ip = ipOf(p.from());
                    try {
                        packet(d, d.length, ip, port);
                    } catch (RuntimeException e) {
                        report.exception("the phone's receive thread", e);
                    }
                    try {
                        drain();
                        buffer.processTimer();
                    } catch (RuntimeException e) {
                        report.exception("the phone's buffer", e);
                    }
                    logs(report);
                }
            } finally {
                buffer.close();
                udpSender.close();
                socket.close();
            }
        }

        /** Udp.receiverRun, one datagram. */
        void packet(byte[] d, int len, InetAddress ip, int port) {
            if (len < 1) return;
            if (d[0] == PacketIds.NAME_QUERY || d[0] == PacketIds.NAME_ASSIGN) {
                answerName(d, len, ip);
                return;
            }
            if (SleepWake.kind(d[0]) && asleep) {
                boolean holds = SleepWake.holdsAwake(d, len, gate, ip.equals(lastPcIp));
                if (holds) {
                    boolean keyed = d[0] == UdpCommon.Connect ? keyBytes(d, len, 4) || keyRead(d, len, 4)
                            : d[0] == PacketIds.SLEEP_ANSWER ? ip.equals(lastPcIp) && len >= 2 : keyBytes(d, len, 1) || keyRead(d, len, 1);
                    if (!keyed) report.broken("a packet without this phone's key kept the sleeping phone awake", hex(d, len));
                    report.count("phone held awake");
                }
            }
            if (d[0] == FleetProtocol.MODE) {
                fleetMode(d, len, ip);
                return;
            }
            if (d[0] == Observe.OBSERVE || d[0] == Observe.STOP) {
                observe(d, len);
                return;
            }
            boolean keyed = d[0] == UdpCommon.Connect && (keyBytes(d, len, 4) || keyRead(d, len, 4));
            if (buffer.isConnected()) {
                if (ip.equals(udpSender.getIp()) && port == udpSender.getPort()) {
                    lastPcIp = udpSender.getIp();
                    lastPcPort = udpSender.getPort();
                    SessionGate.Verdict v = gate.fromPilot(d, len, now, SessionGate.sessionId(d, len));
                    if (v == SessionGate.Verdict.NEW_SESSION && !keyed) report.broken("a session started without this phone's key", "from the pilot: " + hex(d, len));
                    if (v == SessionGate.Verdict.SAME_SESSION) return;
                    if (v == SessionGate.Verdict.NEW_SESSION) newSession(true);
                } else {
                    long live = gate.liveSessionId(), silent = gate.silentMs(now);
                    SessionGate.Verdict v = gate.fromOther(d, len, ip.equals(udpSender.getIp()), disarmed, now, SessionGate.sessionId(d, len));
                    if (v == SessionGate.Verdict.NEW_SESSION && !keyed) report.broken("a session started without this phone's key", "from another PC: " + hex(d, len));
                    if (v != SessionGate.Verdict.NEW_SESSION && (gate.liveSessionId() != live || gate.silentMs(now) != silent)) {
                        report.broken("a packet another PC's session gate refused changed the session", v + ": " + hex(d, len));
                    }
                    if (v != SessionGate.Verdict.NEW_SESSION) return;
                    report.count("phone taken over");
                    newSession(false);
                }
            } else {
                if (d[0] == PacketIds.SPECTATE_PILOT_QUERY || d[0] == PacketIds.SLEEP_ANSWER) return;
                boolean pilotKnown = lastPcIp != null, sameAddress = ip.equals(lastPcIp), samePc = sameAddress && port == lastPcPort;
                long live = gate.liveSessionId(), silent = gate.silentMs(now);
                SessionGate.Verdict v = gate.noSession(d, len, pilotKnown, samePc, sameAddress, () -> disarmed, now, SessionGate.sessionId(d, len));
                if (v == SessionGate.Verdict.NEW_SESSION && !keyed) report.broken("a session started without this phone's key", "no session: " + hex(d, len));
                if (v != SessionGate.Verdict.NEW_SESSION && (gate.liveSessionId() != live || gate.silentMs(now) != silent)) {
                    report.broken("a packet the session gate refused changed the session", v + ": " + hex(d, len));
                }
                if (v != SessionGate.Verdict.NEW_SESSION) return;
                rcPath.restart(now, samePc);
                sticksAnew();
                idleSticks = null;
            }
            if (d[0] == PacketIds.IDLE_STICKS) {
                if (buffer.isConnected()) idle(d, len);
                return;
            }
            if (d[0] == PacketIds.BEEP) return;
            if (d[0] == UdpCommon.RcFrame) {
                if (buffer.isConnected() && !standby) sticks(d, len);
                return;
            }
            if (d[0] == UdpCommon.Ping && len >= 10) Bytes.readInt(d, 6); // the frame times' echo (pingEcho.received)
            if (d[0] == UdpCommon.Connect) SessionGate.markAnswer(d, len);
            boolean was = buffer.isConnected();
            buffer.addPacket(datagram(Arrays.copyOf(d, len), ip, port));
            // the buffer's numbering starts over only with its session (a Connect while it is down; one while it is up
            // is the gate's to judge - a new session is a new buffer): the record of what it handed on, only then
            if (buffer.isConnected() && !was) {
                if (!keyed) report.broken("the phone's buffer took a session without this phone's key", hex(d, len));
                delivered = new HashMap<>();
                report.count("phone session up");
            }
        }

        void newSession(boolean samePc) {
            ReceiverBuffer old = buffer;
            buffer = newBuffer();
            old.close();
            rcPath.restart(now, samePc);
            sticksAnew();
            idleSticks = null;
            delivered = new HashMap<>();
            report.count("phone new session");
        }

        void sticksAnew() {
            lastTail = -1;
            lastStick = null;
        }

        /** The buffer thread: every packet it hands on, to processData. */
        void drain() {
            SavedPacket p;
            int guard = 0;
            while ((p = buffer.getNextPacket()) != null && guard++ < 10_000) {
                if (UdpCommon.isPacketNumbered(p.packetName) && p.packetNum >= 0 && Arrays.equals(delivered.put(p.packetNum, p.data), p.data)) {
                    report.broken("a numbered packet was handed on twice in one session", "number " + p.packetNum + ": " + hex(p.data, p.data.length));
                }
                if (in.intact() && p.packetName != UdpCommon.Connect && !sentAsIs(p.data)) {
                    report.broken("a packet the buffer handed on is not one that came", hex(p.data, p.data.length));
                }
                report.count("phone packet handed on");
                try {
                    processData(p.data);
                } catch (IndexOutOfBoundsException e) {
                    // DataReader past the end of a packet cut short: the phone's buffer thread catches it, the packet is lost
                    report.caught("the phone's processData", e.getClass().getSimpleName() + " on packet " + (p.data.length > 0 ? p.data[0] : -1));
                }
            }
        }

        boolean sentAsIs(byte[] data) {
            for (Packet q : in.packets()) if (Arrays.equals(q.data(), data)) return true;
            return false;
        }

        /** Udp.processData: the readers of Common it calls, in its order; the Android work left out. */
        void processData(byte[] data) {
            DataReader buffer = new DataReader(data, true);
            byte packetName = buffer.readByte();
            if (UdpCommon.isPacketNumbered(packetName)) buffer.readShort();
            switch (packetName) {
                case UdpCommon.StartVideo -> buffer.readBoolean();
                case PacketIds.SET_BITRATE -> {
                    if (buffer.getRemaining() > 0) buffer.readUnsignedByteAsInt();
                }
                case PcAudio.PACKET -> {
                    PcAudio.Request r = PcAudio.decode(data, data.length);
                    if (r != null && (r.kbps < PcAudio.MIN_KBPS || r.kbps > PcAudio.MAX_KBPS)) report.broken("the sound's bitrate outside its range", r.kbps + " kbps");
                }
                case PacketIds.VIDEO_PROFILE -> {
                    if (buffer.getRemaining() > 0) buffer.readByte();
                }
                case PacketIds.VIDEO_FEC -> {
                    if (buffer.getRemaining() < 2) return;
                    int plain = FecSender.parityOf(buffer.readByte()), key = FecSender.parityOf(buffer.readByte());
                    if (plain < 0 || plain > Fec.MAX_PARITY || key < 0 || key > Fec.MAX_PARITY) report.broken("RS parity asked outside 0.." + Fec.MAX_PARITY, plain + "/" + key);
                }
                case UdpCommon.ChangeBitRate -> buffer.readBoolean();
                case PacketIds.DETECTION_SET -> {
                    if (buffer.getRemaining() > 0) {
                        int sensitivity = buffer.readUnsignedByteAsInt();
                        areas(DetectionAreas.read(buffer), "DETECTION_SET " + sensitivity);
                    }
                }
                case PacketIds.REMOTE_UPDATE -> {
                    String version = buffer.readUTF(), url = buffer.readUTF(), sha = buffer.readUTF();
                    if (UpdateSource.fromReleases(url)) {
                        report.count("phone update address taken");
                        if (!releaseAddress(url)) report.broken("an update address outside the project's release downloads was taken", url);
                    }
                }
                case PacketIds.POWER_MODE -> {
                    if (buffer.getRemaining() < 1) return;
                    int mode = buffer.readUnsignedByteAsInt();
                    if (buffer.getRemaining() > 0) buffer.readUnsignedByteAsInt();
                    if (mode == OverwatchProtocol.MODE) {
                        if (buffer.getRemaining() < 6) return;
                        int sensitivity = buffer.readUnsignedByteAsInt();
                        buffer.readUnsignedByteAsInt();
                        buffer.readInt();
                        areas(DetectionAreas.read(buffer), "POWER_MODE 3, sensitivity " + sensitivity);
                    }
                    report.count("phone power mode " + Math.min(mode, 4));
                }
                case UdpCommon.Ping -> {
                    buffer.readBoolean();
                    buffer.readLong();
                    if (buffer.getRemaining() > 0) buffer.readByte();
                }
                case UdpCommon.StartStopRecording -> buffer.readBoolean();
                case UdpCommon.Config -> config(buffer);
                case UdpCommon.MavlinkRawPacket -> {
                    byte[] b = new byte[buffer.getRemaining()];
                    buffer.read(b, 0, b.length);
                }
                default -> {
                    // GetVideoConfig, KEY_FRAME_REQUEST, FcInfo, OsdConfig, BatteryConfig, BoxIds, BoxNames, Disconnect,
                    // ChangeCamera: no bytes read
                }
            }
        }

        /** The Config's reads (the phone's Config.processReceivedConfig: upstream's SettingsCommon layout). */
        void config(DataReader buffer) {
            report.count("phone config");
            // read as far as it goes, as the phone does (it answers VersionMismatch to a version it doesn't know)
            if (buffer.getRemaining() >= 2) buffer.readShort();
        }

        void areas(DetectionAreas a, String what) {
            if (a == null) return;
            if (a.list().size() > DetectionAreas.MAX) report.broken("more detection areas than " + DetectionAreas.MAX, what + ": " + a.list().size());
            for (DetectionAreas.Area x : a.list()) {
                if (!(x.x >= 0 && x.y >= 0 && x.w > 0 && x.h > 0 && x.x + x.w <= 1.0001f && x.y + x.h <= 1.0001f && x.level >= 0 && x.level <= 3)) {
                    report.broken("a detection area outside the picture or its levels", what + ": " + x.x + "," + x.y + "," + x.w + "," + x.h + " level " + x.level);
                }
            }
        }

        /** Udp.handleRcFrame's take, and what RcPath promises of a frame taken. */
        void sticks(byte[] d, int len) {
            int count = rcPath.take(d, len, now);
            if (count < 0) return;
            report.count("phone sticks taken");
            int c = d[1] & 0xff, tail = len - 2 - 2 * c;
            if (count != c || c < 4 || c > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT || tail != 0 && tail != 1 && tail != 2 && tail != 5) {
                report.broken("a stick frame of another layout was taken", count + " channels from " + hex(d, len));
                return;
            }
            short[] frame = rcPath.frame();
            for (int i = 0; i < c; i++) {
                if (frame[i] != (short) (((d[2 + 2 * i] & 0xff) << 8) | (d[3 + 2 * i] & 0xff))) {
                    report.broken("a stick frame's channel taken as another value", "channel " + i + ": " + frame[i] + " from " + hex(d, len));
                    break;
                }
            }
            int seq = tail == 5 ? ((d[len - 5] & 0xff) << 8) | (d[len - 4] & 0xff) : tail == 2 ? ((d[len - 2] & 0xff) << 8) | (d[len - 1] & 0xff)
                    : tail == 1 ? d[len - 1] & 0xff : -1;
            if (lastTail >= 2 && tail >= 2) {
                if ((short) (seq - lastStickSeq) <= 0 && now - lastStickAt < RcPath.SEQ16_FORGET_MS) {
                    report.broken("a stick frame not newer than the last one taken was taken",
                            "sequence " + seq + " after " + lastStickSeq + ", " + (now - lastStickAt) + " ms later: " + hex(d, len));
                }
            } else if (lastTail == 1 && tail == 1) {
                if ((byte) (seq - lastStickSeq) <= 0 && now - lastStickAt <= RcPath.SEQ_RESET_MS) {
                    report.broken("a stick frame not newer than the last one taken was taken",
                            "8-bit sequence " + seq + " after " + lastStickSeq + ", " + (now - lastStickAt) + " ms later: " + hex(d, len));
                }
            }
            if (tail == 5) {
                int maxLag = (d[len - 1] & 0xff) * 10;
                if (maxLag > 0 && rcPath.lagMs() > maxLag) report.broken("a stick frame later than its limit was taken", rcPath.lagMs() + " ms late, limit " + maxLag);
            }
            rcPath.taken(now);
            lastTail = tail;
            lastStickSeq = seq;
            lastStickAt = now;
            lastStick = Arrays.copyOf(d, len);
        }

        void idle(byte[] d, int len) {
            short[] frame = RcPath.idleFrame(d, len);
            if (frame == null) return;
            int c = d[1] & 0xff;
            if (frame.length != c || len != 2 + 2 * c || c < 4 || c > FcCommon.MAX_SUPPORTED_RC_CHANNEL_COUNT) {
                report.broken("idle sticks of another layout were taken", hex(d, len));
            }
            idleSticks = frame;
            report.count("phone idle sticks");
        }

        /** Udp.answerName: a keyed NAME_QUERY or NAME_ASSIGN in any state. */
        void answerName(byte[] d, int len, InetAddress from) {
            if (!gate.keyMatches(d, len, 1)) return;
            if (!keyBytes(d, len, 1)) report.broken("a name packet without this phone's key was taken", hex(d, len));
            if (d[0] == PacketIds.NAME_QUERY) {
                askedName.put(from, now);
                report.count("phone name asked");
                return;
            }
            try (DataInputStream inStream = new DataInputStream(new ByteArrayInputStream(d, 1, len - 1))) {
                inStream.readUTF();
                String name = inStream.readUTF();
                if (name.isEmpty()) return;
                boolean connected = buffer.isConnected();
                Long asked = askedName.get(from);
                boolean askedLately = asked != null && now - asked <= de.droiddrone.common.KnownPcs.NOTICE_MS;
                int v = NameAssignGate.verdict(disarmed, connected, connected && from.equals(udpSender.getIp()), askedLately);
                if (v == NameAssignGate.TAKEN) {
                    report.count("phone named");
                    if (!disarmed || (connected ? !from.equals(udpSender.getIp()) : !askedLately)) report.broken("a name was taken against its gate", name);
                }
            } catch (IOException e) {
                // the phone logs "answerName error" and answers as before
            }
        }

        /** Udp.onFleetMode. */
        void fleetMode(byte[] d, int len, InetAddress from) {
            FleetProtocol.Request r = FleetProtocol.parseRequest(d, len, KEY);
            if (r == null) return;
            if (!keyBytes(d, len, 1)) {
                if (keyRead(d, len, 1)) report.count("phone key read in two-byte letters");
                else report.broken("a fleet mode without this phone's key was read", hex(d, len));
            }
            if (r.token == 0 || r.mode < 1 || r.mode > 3 || r.pcPort == 0) report.broken("a fleet mode outside its values was read", r.mode + "/" + r.token + "/" + r.pcPort);
            boolean connected = buffer.isConnected();
            int before = fleetToken;
            FleetGate.Verdict v = FleetGate.request(r.token, fleetToken, connected, () -> disarmed);
            if (v != FleetGate.Verdict.TAKE) return;
            if (connected || !disarmed || r.token == before) report.broken("a fleet mode was taken against its gate", "token " + r.token + ", before " + before);
            fleetToken = r.token;
            lastPcIp = from;
            lastPcPort = r.pcPort;
            report.count("phone fleet mode taken");
        }

        /** Udp.onObserve: the key, then the request's values. */
        void observe(byte[] d, int len) {
            String k = Observe.keyOf(d, len);
            if (k == null || !k.equals(KEY)) return;
            if (!keyBytes(d, len, 1)) report.broken("an observer's packet without this phone's key was taken", hex(d, len));
            if (d[0] == Observe.STOP) return;
            Observe.Request r = Observe.decodeRequest(d, len);
            if (r == null) return;
            report.count("phone observer");
            if (r.width < 16 || r.width > 4096 || r.height < 16 || r.height > 4096 || r.fps < 1 || r.fps > 60 || r.maxKbps < 100 || r.maxKbps > 20_000
                    || r.detection < 0 || r.detection > 3) {
                report.broken("an observer's request outside its ranges", r.width + "x" + r.height + " " + r.fps + " fps " + r.maxKbps + " kbps detection " + r.detection);
            }
        }
    }

    /** An update's address: github.com, this project's release downloads, a tag and an APK - read as a URI, not as text. */
    static boolean releaseAddress(String url) {
        try {
            URI u = new URI(url);
            String path = u.getPath();
            return "https".equals(u.getScheme()) && "github.com".equals(u.getHost()) && u.getPort() == -1 && u.getUserInfo() == null
                    && u.getQuery() == null && u.getFragment() == null && path != null && path.startsWith("/MelonNO/draupne-link/releases/download/")
                    && path.endsWith(".apk") && !path.contains("..") && path.chars().filter(ch -> ch == '/').count() == 6;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================================================ the PC's link

    private static final Method RECEIVER_RUN, PROCESS_DATA;
    private static final Field SOCKET, SENDER, BUFFER, RUNNING, SESSION_IDS, ANSWERED_ID, ANSWERED_AT;
    private static volatile PcConfig pcConfig;

    static {
        try {
            RECEIVER_RUN = ControlLink.class.getDeclaredMethod("receiverRun");
            PROCESS_DATA = ControlLink.class.getDeclaredMethod("processData", byte[].class);
            SOCKET = ControlLink.class.getDeclaredField("socket");
            SENDER = ControlLink.class.getDeclaredField("udpSender");
            BUFFER = ControlLink.class.getDeclaredField("receiverBuffer");
            RUNNING = ControlLink.class.getDeclaredField("running");
            SESSION_IDS = ControlLink.class.getDeclaredField("sessionIds");
            ANSWERED_ID = ControlLink.class.getDeclaredField("answeredId");
            ANSWERED_AT = ControlLink.class.getDeclaredField("connectAnsweredAt");
            for (java.lang.reflect.AccessibleObject a : new java.lang.reflect.AccessibleObject[]{RECEIVER_RUN, PROCESS_DATA, SOCKET, SENDER, BUFFER, RUNNING,
                    SESSION_IDS, ANSWERED_ID, ANSWERED_AT}) {
                a.setAccessible(true);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ControlLink changed: the protocol fuzz reaches its receive thread by these names", e);
        }
    }

    /**
     * The session ids of the PC's link in the PC_LINK target, the seeds' (PcSender's) and the model's alike: a phone
     * answers its PC's own Connects, so the seeds' answers carry ids the model's link gave out. From a fixed seed,
     * not SecureRandom, just below the wrap of the 32-bit id: the ids given are 0xFFFFFFFE, 0xFFFFFFFF and 0.
     */
    static final int PC_LINK_IDS_SEED = -3;
    /** The tries the model's link made before the input (the seeds' answers are to the first): its ids given out. */
    static final int PC_LINK_TRIES = 3;

    /** A link's session ids set to these: the same ids, in the same order, wherever they are given out. */
    static void sharedIds(ControlLink link, int seed) {
        try {
            SESSION_IDS.set(link, new SessionGate.Ids(seed));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static PcConfig config() {
        PcConfig c = pcConfig;
        if (c != null) return c;
        try {
            c = PcConfig.defaults();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        c.set("key", KEY);
        pcConfig = c;
        return c;
    }

    /**
     * The PC's link to the phone: ControlLink's own receive thread (receiverRun) on a socket that hands it the input's
     * datagrams one by one, and after each what its buffer thread does (getNextPacket, processData). Its video to a
     * frame assembler. Never started: no session thread, no pings, no Connects of its own.
     */
    static final class PcModel {
        final LinkInput in;
        final Report report;
        final ControlLink link = new ControlLink(config(), PHONE_IP, PHONE_PORT, PC_PORT);
        final FakeSocket socket = FakeSocket.create();
        final UdpSender sender = new UdpSender(socket);
        final ReceiverBuffer buffer;
        final VideoCheck video;
        long now = 100_000;
        int next;
        /**
         * What the buffer handed on in its session, by number: anew only when its numbering started over with a session
         * (see after). No numbered packet handed on twice, without exceptions - the phone's resends included (as the
         * phone's model, PhoneModel.delivered).
         */
        Map<Short, byte[]> delivered = new HashMap<>();
        long lastMarkedId = -1;
        long starts;
        /** The session ids the link gave out (its Connects of PC_LINK_TRIES tries, before the input). */
        final Set<Long> given = new HashSet<>();

        PcModel(LinkInput in, Report report) {
            this.in = in;
            this.report = report;
            buffer = new ReceiverBuffer(sender, false, KEY, KEY, () -> now);
            video = new VideoCheck(in, report, (in.flags() & VIDEO_WAIT_KEY) != 0);
            link.setVideoListener(video.listener(() -> now), true);
            link.setNameReply(() -> Names.reply(KEY, Names.Kind.PC, "PCAlpha", "fuzz", "1.0"));
            link.setForeignSleepHandler((from, hello) -> Boolean.TRUE);
            link.setFleetHello((from, hello) -> null);
            link.setEvents(msg -> { });
            sharedIds(link, PC_LINK_IDS_SEED);
            try {
                for (int i = 0; i < PC_LINK_TRIES; i++) { // each try given up unanswered before the next
                    byte[] c = link.buildConnectPacket(40_000 + i * 10_000L, 0);
                    given.add(SessionGate.sessionId(c, c.length));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void run() {
            try {
                sender.connect(PHONE_IP, PHONE_PORT);
                SOCKET.set(link, socket);
                SENDER.set(link, sender);
                BUFFER.set(link, buffer);
                RUNNING.set(link, true);
                socket.feeder = this::next;
                RECEIVER_RUN.invoke(link);
            } catch (InvocationTargetException e) {
                report.exception("ControlLink.receiverRun", e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } finally {
                buffer.close();
                sender.close();
                socket.close();
            }
        }

        /** The socket's receive: the last datagram's aftermath (the buffer thread, the checks), then the next one. */
        void next(DatagramPacket p) throws IOException {
            if (next > 0) after(in.packets().get(next - 1));
            if (next >= in.packets().size()) {
                try {
                    RUNNING.set(link, false);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                throw new SocketException("the input is over");
            }
            Packet q = in.packets().get(next++);
            now += Math.max(0, q.dtMs());
            before(q);
            byte[] buf = p.getData();
            int n = Math.min(q.data().length, buf.length);
            System.arraycopy(q.data(), 0, buf, 0, n);
            p.setLength(n);
            p.setAddress(q.from() == LinkInput.FROM_OTHER ? OTHER_IP : PHONE_IP);
            p.setPort(q.from() == LinkInput.FROM_PEER ? PHONE_PORT : q.from() == LinkInput.FROM_PEER_ADDRESS ? PHONE_PORT + 1 : OTHER_PORT);
        }

        boolean wasConnected;
        long startsBefore, answeredIdBefore, answeredAtBefore;
        int unansweredBefore;

        void before(Packet q) {
            wasConnected = buffer.isConnected();
            startsBefore = link.getSessionStarts();
            answeredIdBefore = field(ANSWERED_ID);
            answeredAtBefore = field(ANSWERED_AT);
            unansweredBefore = buffer.getUnansweredStarts();
        }

        long field(Field f) {
            try {
                return f.getLong(link);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        void after(Packet q) {
            byte[] d = q.data();
            int len = d.length;
            boolean fromPhone = q.from() == LinkInput.FROM_PEER;
            boolean keyedAnswer = len > 0 && d[0] == UdpCommon.Connect && (keyBytes(d, len, 4) || keyRead(d, len, 4));
            if (!wasConnected && buffer.isConnected()) {
                if (!fromPhone || !keyedAnswer) report.broken("the PC's buffer took a session without the key, or from another address", q.from() + ": " + hex(d, len));
                report.count("pc session up");
            }
            // the buffer's numbering started over: the session up, an answer taken (the link counts it, and only then
            // hands it to the buffer, which starts over on it), or the phone's new session without its answer (F-057)
            if (!wasConnected && buffer.isConnected() || link.getSessionStarts() != startsBefore || buffer.getUnansweredStarts() != unansweredBefore) {
                delivered = new HashMap<>();
            }
            if (fromPhone && len > 0 && d[0] == UdpCommon.Connect && SessionGate.answeredById(d, len) && !given.contains(SessionGate.sessionId(d, len))) {
                // an answer told by the id counts only for an id this PC gave out lately: any other changes nothing
                report.count("pc answer to no try of its own");
                if (link.getSessionStarts() != startsBefore || field(ANSWERED_ID) != answeredIdBefore || field(ANSWERED_AT) != answeredAtBefore
                        || !wasConnected && buffer.isConnected()) {
                    report.broken("an answer to no Connect of this PC's changed the link", "id " + SessionGate.sessionId(d, len) + ": " + hex(d, len));
                }
            }
            long s = link.getSessionStarts();
            if (s != startsBefore) {
                report.count("pc session started");
                if (!fromPhone || !keyedAnswer && len > 0 && d[0] == UdpCommon.Connect) report.broken("a session start counted for a packet not the phone's answer", hex(d, len));
                if (fromPhone && len > 0 && d[0] == UdpCommon.Connect && SessionGate.answeredById(d, len)) {
                    long id = SessionGate.sessionId(d, len);
                    if (lastMarkedId >= 0 && (int) (id - lastMarkedId) <= 0) {
                        report.broken("an answer not newer than the one taken started the PC's session again", "id " + id + " after " + lastMarkedId);
                    }
                    lastMarkedId = id;
                } else if (fromPhone && len > 0 && d[0] == UdpCommon.Connect) {
                    lastMarkedId = -1; // an answer without the mark: taken as ever, the ids start over
                }
            } else if (fromPhone && len > 0 && d[0] == UdpCommon.Connect && !SessionGate.answeredById(d, len) && keyedAnswer) {
                lastMarkedId = -1;
            }
            if (!fromPhone && link.getSessionStarts() != startsBefore) report.broken("another address started the PC's session", hex(d, len));
            drain();
            video.after();
            try {
                buffer.processTimer();
            } catch (RuntimeException e) {
                report.exception("the PC's buffer timer", e);
            }
            logs(report);
        }

        /** ControlLink.bufferRun: every packet the buffer hands on, to processData. */
        void drain() {
            SavedPacket p;
            int guard = 0;
            while ((p = buffer.getNextPacket()) != null && guard++ < 10_000) {
                if (UdpCommon.isPacketNumbered(p.packetName) && p.packetNum >= 0 && Arrays.equals(delivered.put(p.packetNum, p.data), p.data)) {
                    report.broken("a numbered packet was handed on twice in one session", "number " + p.packetNum + ": " + hex(p.data, p.data.length));
                }
                report.count("pc packet handed on");
                try {
                    PROCESS_DATA.invoke(link, (Object) p.data);
                } catch (InvocationTargetException e) {
                    Throwable t = e.getCause();
                    if (thrownBy(t, "processData") && t instanceof IndexOutOfBoundsException) {
                        // ControlLink's own reads past a packet cut short: bufferRun logs it, the packet is lost
                        report.caught("ControlLink.processData", t.getClass().getSimpleName() + " on packet " + p.packetName);
                    } else {
                        report.exception("ControlLink.processData (packet " + p.packetName + ")", t);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** The first frame of the app's own code that t came from is in a method of that name (not deeper). */
    static boolean thrownBy(Throwable t, String method) {
        // the JIT's fast throw (no stack) comes only after the same place threw many times with one: judged then
        if (t.getStackTrace().length == 0) return true;
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("de.droiddrone") && !e.getClassName().equals(DataReader.class.getName())) return e.getMethodName().equals(method);
        }
        return false;
    }

    // ================================================================================================ the PC's video

    /**
     * A frame assembler fed as the link's video listener feeds it, and checked: what it holds stays inside its budget,
     * and a frame it gives out whole is - where the input's datagrams are the phone's, unchanged - the frame the phone
     * sent: every fragment of it in the input agrees with it, byte for byte.
     */
    static final class VideoCheck {
        final LinkInput in;
        final Report report;
        final FrameAssembler a = new FrameAssembler();
        final List<FrameAssembler.Frame> out = new ArrayList<>();
        /** The intact input's fragments by frame number: {offset, frame size or -1, packet index}. */
        final Map<Short, List<int[]>> fragments = new HashMap<>();

        VideoCheck(LinkInput in, Report report, boolean waitKey) {
            this.in = in;
            this.report = report;
            a.setWaitForWholeKeyFrames(waitKey);
            if (in.intact()) {
                for (int i = 0; i < in.packets().size(); i++) {
                    byte[] d = in.packets().get(i).data();
                    if (d.length < 7 || d[0] != UdpCommon.VideoFrame && d[0] != UdpCommon.KeyFrame) continue;
                    short f = (short) (((d[1] & 0xff) << 8) | (d[2] & 0xff));
                    int offset = Bytes.readInt(d, 3);
                    int size = offset == 0 && d.length >= 11 ? Bytes.readInt(d, 7) : -1;
                    fragments.computeIfAbsent(f, k -> new ArrayList<>()).add(new int[]{offset, size, i});
                }
            }
        }

        ControlLink.VideoListener listener(java.util.function.LongSupplier clock) {
            return new ControlLink.VideoListener() {
                @Override
                public void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
                    report.count("pc video config");
                }

                @Override
                public void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
                    took(a.put(keyFrame, frameNum, offset, frameSize, buf, dataOffset, length, clock.getAsLong()));
                }

                @Override
                public void onVideoParity(boolean keyFrame, short frameNum, int frameSize, byte[] buf, int dataOffset, int length) {
                    took(a.putParity(keyFrame, frameNum, frameSize, buf, dataOffset, length, clock.getAsLong()));
                }

                @Override
                public void onVideoParityGroup(boolean keyFrame, short frameNum, int frameSize, int group, byte[] buf, int dataOffset, int length) {
                    took(a.putParityGroup(keyFrame, frameNum, frameSize, group, buf, dataOffset, length, clock.getAsLong()));
                }

                @Override
                public void onVideoParityRs(byte[] d, int len) {
                    took(a.putParityRs(d, len, clock.getAsLong()));
                }
            };
        }

        void took(FrameAssembler.Frame f) {
            if (f != null) out.add(f);
            out.addAll(a.takeFlushed());
        }

        /** After each datagram: the budget, and each frame given out since. */
        void after() {
            long held = a.pendingBytes();
            if (held > FrameAssembler.PENDING_BUDGET) report.broken("the frame assembler holds more than its budget", held + " bytes");
            for (FrameAssembler.Frame f : out) check(f);
            out.clear();
        }

        void check(FrameAssembler.Frame f) {
            if (f.data() == null) return;
            if (f.data().length > FrameAssembler.PLAUSIBLE_FRAME_BYTES) report.broken("a frame larger than a frame can be came out", f.data().length + " bytes");
            if (f.damaged()) {
                report.count("pc frame damaged");
                return;
            }
            report.count("pc frame whole");
            if (!in.intact()) return;
            List<int[]> parts = fragments.get(f.number());
            if (parts == null) return;
            for (int[] p : parts) {
                byte[] d = in.packets().get(p[2]).data();
                if (p[1] >= 0 && p[1] != f.data().length) {
                    report.broken("a whole frame is not the size the phone sent", "frame " + f.number() + ": " + f.data().length + " bytes, sent " + p[1]);
                    return;
                }
                int header = p[0] == 0 ? 11 : 7;
                int n = d.length - header;
                if (p[0] < 0 || p[0] + n > f.data().length) {
                    report.broken("a whole frame is shorter than a fragment the phone sent of it", "frame " + f.number() + " at " + p[0]);
                    return;
                }
                if (!Arrays.equals(d, header, header + n, f.data(), p[0], p[0] + n)) {
                    report.broken("a whole frame differs from the one the phone sent", "frame " + f.number() + " at " + p[0] + ", " + n + " bytes"
                            + (f.keyFrame() ? ", a key frame" : ""));
                    return;
                }
            }
        }
    }

    static void video(LinkInput in, Report report) {
        VideoCheck v = new VideoCheck(in, report, (in.flags() & VIDEO_WAIT_KEY) != 0);
        long[] clock = {100_000};
        ControlLink.VideoListener l = v.listener(() -> clock[0]);
        for (Packet p : in.packets()) {
            clock[0] += Math.max(0, p.dtMs());
            byte[] buf = Arrays.copyOf(p.data(), Math.max(p.data().length, UdpCommon.packetLength + Spectate.STREAM_HEADER + 16));
            try {
                ControlLink.deliverVideo(l, buf, p.data().length);
            } catch (RuntimeException e) {
                report.exception("ControlLink.deliverVideo", e);
            }
            v.after();
        }
    }

    // ================================================================================================ the PC's telemetry

    /** No flight controller known yet, a Betaflight one, an INAV one: the records read differently for each. */
    static final FcInfo[] FLIGHT_CONTROLLERS = {null, new FcInfo(FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0),
            new FcInfo(FcInfo.FC_VARIANT_INAV, 7, 1, 0, 0, 2, 5, 0)};

    static void telemetry(LinkInput in, Report report) {
        FcInfo fc = FLIGHT_CONTROLLERS[(in.flags() >> 1) % FLIGHT_CONTROLLERS.length];
        FcTelemetry t = new FcTelemetry();
        for (Packet p : in.packets()) {
            byte[] d = p.data();
            if (d.length < 1) continue;
            DataReader r = new DataReader(d.clone(), true);
            r.readByte();
            try {
                t.parse(r, fc);
                t.getStatus();
                t.getPhoneState();
                t.getPhoneAppVersion();
                t.getMotion().shown(100_000);
                report.count("pc telemetry read");
            } catch (RuntimeException e) {
                report.exception("FcTelemetry.parse", e);
            }
        }
    }

    // ================================================================================================ the PC's other readers

    /**
     * What reaches the PC outside the link's session: a phone's overwatch packets (OverwatchHub: the alert, the
     * preview's fragments into a unit assembler, the clip's packets, the status), a sleeping phone's check-in
     * (SleepHello, its status, Where and Notice blocks), name replies, the phone's spectator answers, a spectator's or
     * the pilot PC's packets, an observed phone's state - each read by the reader its receive thread uses.
     */
    static void pcOutside(LinkInput in, Report report) {
        UnitAssembler units = new UnitAssembler();
        Map<Long, List<int[]>> pieces = new HashMap<>(); // the intact input's preview fragments by unit
        if (in.intact()) {
            for (int i = 0; i < in.packets().size(); i++) {
                byte[] d = in.packets().get(i).data();
                OverwatchProtocol.Fragment f = OverwatchProtocol.readFragment(d, d.length);
                if (f != null) pieces.computeIfAbsent(((long) f.event << 16) | f.unit, k -> new ArrayList<>()).add(new int[]{(int) f.offset, i});
            }
        }
        for (Packet p : in.packets()) {
            byte[] d = p.data();
            int len = d.length;
            if (len < 1) continue;
            try {
                outside(d, len, units, pieces, in, report);
            } catch (RuntimeException e) {
                report.exception("a reader of the PC's (packet " + String.format("0x%02x", d[0] & 0xff) + ")", e);
            }
        }
    }

    private static void outside(byte[] d, int len, UnitAssembler units, Map<Long, List<int[]>> pieces, LinkInput in, Report report) {
        byte code = d[0];
        if (OverwatchProtocol.isFromPhone(d, len)) {
            switch (code) {
                case OverwatchProtocol.ALERT -> {
                    if (OverwatchProtocol.readAlert(d, len) != null) report.count("pc overwatch alert");
                }
                case OverwatchProtocol.PREVIEW -> {
                    OverwatchProtocol.Fragment f = OverwatchProtocol.readFragment(d, len);
                    if (f == null) return;
                    if (f.offset + f.dataLength > f.total || f.dataOffset + f.dataLength > len) report.broken("a preview fragment read past its unit", f.offset + "+" + f.dataLength + " of " + f.total);
                    UnitAssembler.Unit u = units.add(f);
                    if (u == null) return;
                    report.count("pc preview unit whole");
                    if (u.data.length > UnitAssembler.MAX_UNIT) report.broken("a preview unit over its most", u.data.length + " bytes");
                    List<int[]> sent = pieces.get(((long) u.event << 16) | u.unit);
                    if (in.intact() && sent != null) {
                        for (int[] s : sent) {
                            byte[] q = in.packets().get(s[1]).data();
                            int n = q.length - 18;
                            if (s[0] + n > u.data.length || !Arrays.equals(q, 18, 18 + n, u.data, s[0], s[0] + n)) {
                                report.broken("a whole preview unit differs from the one the phone sent", "event " + u.event + " unit " + u.unit + " at " + s[0]);
                                break;
                            }
                        }
                    }
                }
                case OverwatchProtocol.CLIP -> { // OverwatchHub.onClip: the type first, a chunk only with its offset
                    int type = OverwatchProtocol.clipType(d, len);
                    if (type < 0 || type == OverwatchProtocol.CLIP_CHUNK && len < OverwatchProtocol.CHUNK_DATA_AT) return;
                    OverwatchProtocol.clipEvent(d);
                    if (type == OverwatchProtocol.CLIP_INFO) {
                        OverwatchProtocol.ClipInfo c = OverwatchProtocol.readClipInfo(d, len);
                        if (c != null) report.count("pc clip info");
                    } else if (type == OverwatchProtocol.CLIP_CHUNK) {
                        OverwatchProtocol.chunkOffset(d);
                    } else if (type == OverwatchProtocol.CLIP_ENDED) {
                        OverwatchProtocol.readClipEnded(d, len);
                    } else if (type == OverwatchProtocol.CLIP_NONE) {
                        OverwatchProtocol.readClipNone(d, len);
                    }
                }
                case OverwatchProtocol.STATUS -> OverwatchProtocol.readStatus(d, len);
                default -> {
                }
            }
            OverwatchProtocol.token(d);
            return;
        }
        if (code == PacketIds.SLEEP_HELLO && len >= 6) {
            ControlLink.SleepHello h = ControlLink.SleepHello.read(d, len, 0);
            h.overwatch();
            h.watchesBlind();
            if (len >= 8 + FleetProtocol.STATUS_BYTES) {
                FleetProtocol.Where w = FleetProtocol.Where.read(d, 8 + FleetProtocol.STATUS_BYTES, len, KEY);
                if (w != null) {
                    report.count("pc sleep hello read");
                    if (!keyBytes(d, len, 8 + FleetProtocol.STATUS_BYTES + 1) && !keyRead(d, len, 8 + FleetProtocol.STATUS_BYTES + 1)) {
                        report.broken("a check-in without this PC's key was read", hex(d, len));
                    }
                    FleetProtocol.Notice n = FleetProtocol.Notice.read(d, len, KEY);
                    if (n != null && n.headingUsable() && !(n.headingDeg >= 0 && n.headingDeg < 360)) report.broken("a heading outside 0..360", "" + n.headingDeg);
                }
            }
            return;
        }
        if (code == PacketIds.NAME_REPLY) {
            Names.Reply r = Names.parseReply(d, len, KEY);
            if (r != null) {
                report.count("pc name reply read");
                if (!keyBytes(d, len, 1)) report.broken("a name reply without this PC's key was read", hex(d, len));
            }
            return;
        }
        if (code == PacketIds.NAME_QUERY || code == PacketIds.NAME_ASSIGN) {
            Names.keyOf(d, len);
            if (Names.parseAssign(d, len, KEY) != null && !keyBytes(d, len, 1)) report.broken("a name without this PC's key was read", hex(d, len));
            return;
        }
        if (code == Spectate.PILOT_AT) {
            Spectate.Address a = Spectate.readPilotAt(d, len);
            if (a != null && (a.port() < 0 || a.port() > 65535)) report.broken("a pilot's port outside 0..65535", "" + a.port());
            return;
        }
        if (code == Spectate.PILOT_VIEW) {
            ControlLink.PilotView v = Spectate.readPilotView(d, len);
            if (v != null) report.count("pc pilot view read");
            return;
        }
        if (code == Spectate.HELLO || code == Spectate.PILOT_QUERY) {
            String k = Spectate.readKey(d, len);
            Spectate.helloFlags(d, len);
            if (KEY.equals(k) && !keyBytes(d, len, 1)) report.broken("a spectator's key read from other bytes", hex(d, len));
            return;
        }
        if (Spectate.isSpectatePacket(code)) {
            Spectate.readPort(d, len);
            return;
        }
        if (code == Observe.STATE) {
            if (Observe.decodeState(d, len) != null) report.count("pc observed state read");
            return;
        }
        if (code == FleetProtocol.MODE) {
            FleetProtocol.Request r = FleetProtocol.parseRequest(d, len, KEY);
            if (r != null && !keyBytes(d, len, 1) && !keyRead(d, len, 1)) report.broken("a fleet mode without the key was read", hex(d, len));
        }
    }

    // ================================================================================================ seeds

    /**
     * Valid streams of every target, made by the apps' own senders; the same for the same seed. The PC's packets to the
     * phone: ControlLink's builders, numbered by the transport (UdpSender) as they go out; the phone's to the PC: the
     * transport, TelemetryRecords, FecSender; the others: their protocol class's encoder.
     */
    public static List<LinkInput> seeds(long seed) {
        Random r = new Random(seed);
        List<LinkInput> out = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) out.add(phoneSession(r, i));
            for (int i = 0; i < 3; i++) out.add(phoneOutside(r, i));
            for (int i = 0; i < 4; i++) out.add(pcLink(r, i));
            for (int i = 0; i < 6; i++) out.add(pcVideo(r, i));
            for (int i = 0; i < 3; i++) out.add(pcTelemetry(r, i));
            for (int i = 0; i < 3; i++) out.add(pcOutsideSeed(r, i));
            // F-111 (a Random of their own: the seeds above stay as they were)
            Random f = new Random(seed ^ 0xF111L);
            out.add(phoneResends(f));
            out.add(pcResends(f));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /**
     * F-111, the phone's side: a session a second old (its buffer settled at its least, on sparse numbered traffic), then
     * the PC's Config #0 and StartVideo #1, their acknowledgements lost - resent as the PC's transport does, 8 times
     * each about 125 ms apart, nothing else numbered meanwhile - then the session goes on (#2). Numbered here, not by
     * the transport, whose resend timer would put copies in by the clock.
     */
    static LinkInput phoneResends(Random r) throws IOException {
        PcSender pc = new PcSender(r);
        pc.connect(0);
        for (int i = 0; i < 120; i++) pc.sticks(r, 3, 10);
        byte[] config = pc.link.buildConfigPacket(); // [21][number 0][...]
        UdpPacketData sv = new UdpPacketData(UdpCommon.StartVideo, 1);
        sv.daos.writeBoolean(true);
        byte[] startVideo = sv.getData();
        resent(pc.packets, config, startVideo, () -> {
            for (int i = 0; i < 12; i++) pc.sticks(r, 3, 10);
        });
        pc.raw(new UdpPacketData(UdpCommon.FcInfo, 2).getData(), 5);
        for (int i = 0; i < 10; i++) pc.sticks(r, 3, 10);
        pc.close();
        return new LinkInput(Target.PHONE_LINK, LinkInput.BYTES_INTACT | PHONE_DISARMED, pc.packets);
    }

    /** F-111, the PC's side: the same with the phone's ConfigReceived #0 and VideoInitialFrame #1, among its telemetry. */
    static LinkInput pcResends(Random r) throws IOException {
        PcSender pc = new PcSender(r);
        sharedIds(pc.link, PC_LINK_IDS_SEED);
        PhoneSender phone = new PhoneSender();
        byte[] answer = pc.link.buildConnectPacket(pc.clockMs, 0);
        SessionGate.markAnswer(answer, answer.length);
        phone.raw(answer, 0);
        for (int i = 0; i < 24; i++) phone.raw(telemetryPacket(r, FLIGHT_CONTROLLERS[1], 1 + r.nextInt(3)), 50);
        UdpPacketData vc = new UdpPacketData(UdpCommon.VideoInitialFrame, 1);
        vc.daos.writeShort(1280);
        vc.daos.writeShort(720);
        vc.daos.writeBoolean(false);
        vc.daos.writeBoolean(false);
        byte[] cfg = new byte[20 + r.nextInt(30)];
        r.nextBytes(cfg);
        vc.daos.write(cfg);
        resent(phone.packets, new UdpPacketData(UdpCommon.ConfigReceived, 0).getData(), vc.getData(), () -> {
            for (int i = 0; i < 3; i++) phone.raw(telemetryPacket(r, FLIGHT_CONTROLLERS[1], 1 + r.nextInt(3)), 40);
        });
        UdpPacketData fi = new UdpPacketData(UdpCommon.FcInfo, 2);
        for (int v : new int[]{FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0}) fi.daos.writeByte(v);
        phone.raw(fi.getData(), 5);
        for (int i = 0; i < 5; i++) phone.raw(telemetryPacket(r, FLIGHT_CONTROLLERS[1], 1 + r.nextInt(3)), 40);
        pc.close();
        phone.close();
        return new LinkInput(Target.PC_LINK, LinkInput.BYTES_INTACT, phone.packets);
    }

    /** Two numbered packets sent, then resent 8 times each, about 125 ms apart, with the sender's other traffic between. */
    private static void resent(List<Packet> out, byte[] first, byte[] second, IoRun between) throws IOException {
        out.add(new Packet(LinkInput.FROM_PEER, 10, first));
        out.add(new Packet(LinkInput.FROM_PEER, 5, second));
        for (int round = 0; round < 8; round++) {
            between.run(); // about 120 ms of it
            out.add(new Packet(LinkInput.FROM_PEER, 5, first));
            out.add(new Packet(LinkInput.FROM_PEER, 0, second));
        }
    }

    private interface IoRun {
        void run() throws IOException;
    }

    /** The PC's sender for the phone: a ControlLink's builders, numbered by the transport as a link sends them. */
    static final class PcSender {
        final FakeSocket socket = FakeSocket.create();
        final UdpSender transport = new UdpSender(socket);
        final ControlLink link = new ControlLink(config(), PHONE_IP, PHONE_PORT, PC_PORT);
        final List<Packet> packets = new ArrayList<>();
        int seq;
        long clockMs = 40_000;

        PcSender(Random r) {
            transport.connect(PHONE_IP, PHONE_PORT);
            try { // the session ids from the seed, not from SecureRandom: the same seeds every time
                Field ids = ControlLink.class.getDeclaredField("sessionIds");
                ids.setAccessible(true);
                ids.set(link, new SessionGate.Ids(r.nextInt()));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Out through the transport (a numbered kind gets its number), as ControlLink's udpSender.sendPacket. */
        void send(byte[] data, int dtMs) {
            transport.sendPacket(data);
            for (byte[] d : socket.takeSent()) packets.add(new Packet(LinkInput.FROM_PEER, dtMs, d));
            clockMs += dtMs;
        }

        /** Straight out of the socket, as ControlLink sends sticks and the video's packets. */
        void raw(byte[] data, int dtMs) {
            packets.add(new Packet(LinkInput.FROM_PEER, dtMs, data));
            clockMs += dtMs;
        }

        void connect(int dtMs) throws IOException {
            raw(link.buildConnectPacket(clockMs, 0), dtMs);
        }

        void sticks(Random r, int layout, int dtMs) throws IOException {
            short[] ch = new short[8 + (layout == 3 ? 8 : 0)];
            for (int i = 0; i < ch.length; i++) ch[i] = (short) (1000 + r.nextInt(1001));
            ch[4] = 1000; // ARM off
            byte[] d = switch (layout) {
                case 0 -> ControlLink.rcFrame(ch, -1, false);
                case 1 -> ControlLink.rcFrame(ch, seq & 0xff, false);
                case 2 -> ControlLink.rcFrame(ch, seq, true);
                default -> ControlLink.rcFrame(ch, seq, (int) (clockMs + dtMs), r.nextInt(3) * 100);
            };
            seq = (seq + 1) & 0xffff;
            raw(d, dtMs);
        }

        void close() {
            transport.close();
            socket.close();
        }
    }

    static byte[] withByte(byte code, int... values) throws IOException {
        UdpPacketData p = new UdpPacketData(code);
        for (int v : values) p.daos.writeByte(v);
        return p.getData();
    }

    static byte[] remoteUpdate(String version, String url, String sha) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(PacketIds.REMOTE_UPDATE);
        out.writeUTF(version);
        out.writeUTF(url);
        out.writeUTF(sha);
        return bytes.toByteArray();
    }

    /** DETECTION_SET [0x7C][sensitivity u8][the areas' block], as ControlLink.sendDetectionIfDue builds it. */
    static byte[] detectionSet(Random r) {
        byte[] areas = someAreas(r).encode();
        byte[] p = new byte[2 + areas.length];
        p[0] = PacketIds.DETECTION_SET;
        p[1] = (byte) r.nextInt(4);
        System.arraycopy(areas, 0, p, 2, areas.length);
        return p;
    }

    static DetectionAreas someAreas(Random r) {
        List<DetectionAreas.Area> l = new ArrayList<>();
        for (int i = 1 + r.nextInt(3); i > 0; i--) l.add(new DetectionAreas.Area(r.nextFloat() * 0.8f, r.nextFloat() * 0.8f, 0.05f + r.nextFloat() * 0.2f, 0.05f + r.nextFloat() * 0.2f, r.nextInt(4)));
        return DetectionAreas.of(l);
    }

    /** A pilot PC's session with the phone: Connect, the handshake's requests, the config, sticks, the commands of a flight. */
    static LinkInput phoneSession(Random r, int kind) throws IOException {
        PcSender pc = new PcSender(r);
        int layout = kind == 3 ? r.nextInt(3) : 3; // the last one: an older PC's stick frames
        pc.connect(0);
        if (kind == 3) pc.packets.set(0, new Packet(LinkInput.FROM_PEER, 0, Arrays.copyOf(pc.packets.get(0).data(), 8 + KEY.length()))); // an older PC: no session id
        pc.connect(150); // the next Connect of the same try
        pc.send(pc.link.buildConfigPacket(), 20);
        UdpPacketData sv = new UdpPacketData(UdpCommon.StartVideo);
        sv.daos.writeBoolean(true);
        pc.send(sv.getData(), 5);
        pc.send(new UdpPacketData(UdpCommon.FcInfo).getData(), 5);
        pc.send(new UdpPacketData(UdpCommon.BoxNames).getData(), 5);
        pc.send(new byte[]{PacketIds.VIDEO_PROFILE, (byte) (2 | 8 | 16)}, 3);
        pc.send(new byte[]{PacketIds.VIDEO_FEC, 2, 4}, 3);
        UdpPacketData ping = new UdpPacketData(UdpCommon.Ping); // as UdpSender.sendPing, its stamp from the seed (not the clock)
        ping.daos.writeBoolean(true);
        ping.daos.writeLong(1_000_000 + r.nextInt(1000));
        pc.send(ping.getData(), 4);
        for (int i = 0; i < 30; i++) {
            pc.sticks(r, layout, 4 + r.nextInt(8));
            if (i % 5 == 0) pc.send(PcAudio.encode(true, 48, i == 0, false), 1);
            if (i % 10 == 3) pc.raw(detectionSet(r), 1);
            if (i == 7) pc.send(new UdpPacketData(UdpCommon.GetVideoConfig).getData(), 1);
            if (i == 9) pc.send(new byte[]{PacketIds.KEY_FRAME_REQUEST}, 1);
            if (i == 12) pc.send(new byte[]{PacketIds.SET_BITRATE, 3}, 1);
            if (i == 14) {
                UdpPacketData cb = new UdpPacketData(UdpCommon.ChangeBitRate);
                cb.daos.writeBoolean(true);
                pc.send(cb.getData(), 1);
            }
        }
        switch (kind) {
            case 0 -> { // the end of a flight: idle sticks, standby, beeper, sleep, overwatch
                for (int i = 0; i < 4; i++) {
                    short[] idle = {1500, 1500, 1000, 1500, 1000, 1000, 1000, 1000};
                    pc.raw(ControlLink.idleSticksFrame(idle), 250);
                }
                pc.send(new byte[]{PacketIds.POWER_MODE, 1, 5}, 50);
                pc.raw(new byte[]{PacketIds.BEEP, 5}, 20);
                pc.send(OverwatchProtocol.request(PacketIds.POWER_MODE, 10, 2, 1, 0x1234567, someAreas(r)), 50);
                pc.send(new byte[]{PacketIds.POWER_MODE, 0, 0}, 50);
                pc.send(new byte[]{PacketIds.POWER_MODE, 2, 30}, 50);
            }
            case 1 -> { // an update offered, recording, the camera changed, the link's end
                pc.send(remoteUpdate("2.3.0-pc.56", UpdateSource.RELEASE_DOWNLOADS + "flight-2.3.0-pc.56/draupne-flight-2.3.0-pc.56.apk",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"), 30);
                UdpPacketData rec = new UdpPacketData(UdpCommon.StartStopRecording);
                rec.daos.writeBoolean(true);
                pc.send(rec.getData(), 10);
                pc.send(new UdpPacketData(UdpCommon.ChangeCamera).getData(), 10);
                for (int i = 0; i < 10; i++) pc.sticks(r, layout, 5);
                pc.send(new UdpPacketData(UdpCommon.Disconnect).getData(), 10);
            }
            case 2 -> { // the link lost and back: a new try's Connect after a second and a half, the session anew
                pc.link.getSessionStarts();
                pc.packets.add(new Packet(LinkInput.FROM_PEER, 1500, newTry(pc)));
                pc.transport.connect(PHONE_IP, PHONE_PORT); // the PC's numbering starts over with the answer
                pc.send(pc.link.buildConfigPacket(), 20);
                for (int i = 0; i < 15; i++) pc.sticks(r, layout, 4);
                // another PC tries meanwhile: its Connect, with this key, from another address
                PcSender other = new PcSender(r);
                other.connect(0);
                pc.packets.add(new Packet(LinkInput.FROM_OTHER, 10, other.packets.get(0).data()));
                other.close();
                for (int i = 0; i < 5; i++) pc.sticks(r, layout, 4);
            }
            default -> {
                for (int i = 0; i < 10; i++) pc.sticks(r, layout, 4 + r.nextInt(30));
            }
        }
        pc.close();
        int flags = LinkInput.BYTES_INTACT | (kind != 1 ? PHONE_DISARMED : 0);
        return new LinkInput(Target.PHONE_LINK, flags, pc.packets);
    }

    /** A Connect of a new try: the ids move on after a second unanswered. */
    static byte[] newTry(PcSender pc) throws IOException {
        pc.link.buildConnectPacket(pc.clockMs, 0);
        return pc.link.buildConnectPacket(pc.clockMs + SessionGate.Ids.UNHEARD_KEEP_MS + 1, 0);
    }

    static List<Packet> asPeer(List<byte[]> sent, int dtMs) {
        List<Packet> out = new ArrayList<>();
        for (byte[] d : sent) out.add(new Packet(LinkInput.FROM_PEER, dtMs, d));
        return out;
    }

    /** What reaches a phone in any state: names, fleet modes, observers, a spectator's question, the sleep check's answer. */
    static LinkInput phoneOutside(Random r, int kind) throws IOException {
        List<Packet> p = new ArrayList<>();
        int from = kind == 1 ? LinkInput.FROM_OTHER : LinkInput.FROM_PEER;
        p.add(new Packet(from, 0, Names.query(KEY)));
        p.add(new Packet(from, 50, Names.assign(KEY, "MobileBravo")));
        p.add(new Packet(LinkInput.FROM_OTHER, 50, Names.assign(KEY, "MobileCharlie"))); // from a PC that never asked
        int token = 1 + r.nextInt(1_000_000);
        p.add(new Packet(from, 50, FleetProtocol.request(KEY, new FleetProtocol.Request(token, 1 + r.nextInt(3), 5, 2, 0, 0x777, PC_PORT))));
        p.add(new Packet(from, 1500, FleetProtocol.request(KEY, new FleetProtocol.Request(token, 2, 5, 2, 0, 0x777, PC_PORT)))); // the repeat
        p.add(new Packet(from, 50, Observe.encodeRequest(KEY, 640, 360, 15, 1000, Observe.FLAG_VIDEO_CONFIG, 2)));
        p.add(new Packet(from, 1000, Observe.encodeRequest(KEY, 1280, 720, 30, 2500, 0, 0)));
        p.add(new Packet(from, 50, Observe.encodeStop(KEY)));
        p.add(new Packet(from, 50, Spectate.keyed(Spectate.PILOT_QUERY, KEY)));
        p.add(new Packet(LinkInput.FROM_PEER, 50, new byte[]{PacketIds.SLEEP_ANSWER, 1}));
        if (kind == 2) { // in a session: the pilot's Connect first, then a fleet mode (refused) and a name from the pilot
            PcSender pc = new PcSender(r);
            pc.connect(0);
            p.add(0, pc.packets.get(0));
            pc.close();
            p.add(new Packet(LinkInput.FROM_PEER, 20, FleetProtocol.request(KEY, new FleetProtocol.Request(token + 1, 2, 5, 2, 0, 0, PC_PORT))));
        }
        p.add(new Packet(from, 20, Names.query(KEY)));
        int flags = LinkInput.BYTES_INTACT | PHONE_DISARMED | (kind == 0 ? PHONE_ASLEEP : 0);
        return new LinkInput(Target.PHONE_OUTSIDE, flags, p);
    }

    /** The phone's sender for the PC: the transport numbers what the phone's Udp sends with it. */
    static final class PhoneSender {
        final FakeSocket socket = FakeSocket.create();
        final UdpSender transport = new UdpSender(socket);
        final List<Packet> packets = new ArrayList<>();

        PhoneSender() {
            transport.connect(PC_IP, PC_PORT);
        }

        void send(byte[] data, int dtMs) {
            transport.sendPacket(data);
            List<byte[]> sent = socket.takeSent();
            for (int i = 0; i < sent.size(); i++) packets.add(new Packet(LinkInput.FROM_PEER, i == 0 ? dtMs : 0, sent.get(i)));
        }

        void raw(byte[] data, int dtMs) {
            packets.add(new Packet(LinkInput.FROM_PEER, dtMs, data));
        }

        void close() {
            transport.close();
            socket.close();
        }
    }

    /** The phone's telemetry packet: [19] and a few records, as TelemetryRecords writes them from the flight controller's replies. */
    static byte[] telemetryPacket(Random r, FcInfo fc, int records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(UdpCommon.TelemetryData);
        short[] codes = ProtocolFuzzTest.telemetryCodes();
        for (int k = 0; k < records; k++) {
            int code = codes[r.nextInt(codes.length)] & 0xffff;
            byte[] reply = new byte[r.nextInt(4) == 0 ? 8 + r.nextInt(40) : 120 + r.nextInt(200)];
            r.nextBytes(reply);
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            try {
                TelemetryRecords.write(new TelemetryData(code, reply), fc, new DataOutputStream(one));
            } catch (IOException | RuntimeException e) {
                continue; // a reply too short for its record: the phone sends what it wrote before - left out here
            }
            out.write(one.toByteArray());
        }
        return bytes.toByteArray();
    }

    /** A video frame as the phone sends it (Udp.sendFragments, as VirtualVideo): fragments, and XOR group parity or RS parity. */
    static void frame(List<Packet> out, Random r, int frameNum, int size, boolean key, int parityKind, int dtMs) throws IOException {
        byte[] buf = new byte[size];
        r.nextBytes(buf);
        int rs = parityKind == 2 ? 1 + r.nextInt(Fec.MAX_PARITY) : 0;
        FecSender fec = new FecSender();
        int rsPackets = rs > 0 ? fec.begin(buf, size, frameNum, key, rs) : 0;
        byte[] parity = parityKind == 1 && rsPackets == 0 ? new byte[UdpCommon.packetLength - 9] : null;
        int offset = 0, packet = 0;
        boolean first = true;
        while (offset < size) {
            UdpPacketData p = new UdpPacketData(key ? UdpCommon.KeyFrame : UdpCommon.VideoFrame);
            p.daos.writeShort(frameNum);
            p.daos.writeInt(offset);
            int header = 9;
            if (offset == 0) {
                p.daos.writeInt(size);
                header += 4;
            }
            int n = Math.min(UdpCommon.packetLength - header, size - offset);
            p.daos.write(buf, offset, n);
            if (parity != null) for (int i = 0; i < n; i++) parity[i] ^= buf[offset + i];
            offset += n;
            out.add(new Packet(LinkInput.FROM_PEER, first ? dtMs : 0, p.getData()));
            first = false;
            packet++;
            if (parity != null && (packet % FrameAssembler.PARITY_GROUP == 0 || offset >= size)) {
                UdpPacketData q = new UdpPacketData(PacketIds.VIDEO_PARITY_GROUP);
                q.daos.writeShort(frameNum);
                q.daos.writeInt(size);
                q.daos.writeByte(key ? 1 : 0);
                q.daos.writeByte((packet - 1) / FrameAssembler.PARITY_GROUP);
                q.daos.write(parity, 0, parity.length);
                out.add(new Packet(LinkInput.FROM_PEER, 0, q.getData()));
                Arrays.fill(parity, (byte) 0);
            }
            int block = rsPackets > 0 ? fec.blockEndingAt(packet - 1) : -1;
            if (block >= 0) {
                int length = fec.encode(block);
                for (int i = 0; i < fec.m(); i++) out.add(new Packet(LinkInput.FROM_PEER, 0, Arrays.copyOf(fec.packet(i), length)));
            }
        }
    }

    /** The phone's side of a session: the answer, the stream's config, FcInfo, telemetry, video, sound, pongs. */
    static LinkInput pcLink(Random r, int kind) throws IOException {
        PcSender pc = new PcSender(r);
        sharedIds(pc.link, PC_LINK_IDS_SEED); // the model's link's ids: the answer is to its first try
        PhoneSender phone = new PhoneSender();
        byte[] answer = pc.link.buildConnectPacket(pc.clockMs, 0);
        if (kind != 3) SessionGate.markAnswer(answer, answer.length); // a phone that tells sessions by the id (pc.53 on)
        phone.raw(answer, 0);
        if (kind == 2) phone.raw(answer.clone(), 120); // the same answer again: a copy on the way
        UdpPacketData cr = new UdpPacketData(UdpCommon.ConfigReceived);
        phone.send(cr.getData(), 30);
        UdpPacketData vc = new UdpPacketData(UdpCommon.VideoInitialFrame);
        vc.daos.writeShort(1280);
        vc.daos.writeShort(720);
        vc.daos.writeBoolean(false);
        vc.daos.writeBoolean(false);
        byte[] cfg = new byte[20 + r.nextInt(30)];
        r.nextBytes(cfg);
        vc.daos.write(cfg);
        phone.send(vc.getData(), 10);
        UdpPacketData ac = new UdpPacketData(UdpCommon.AudioInitialFrame);
        ac.daos.writeInt(48_000);
        ac.daos.writeByte(1);
        ac.daos.writeByte(2);
        ac.daos.write(new byte[]{0x11, (byte) 0x88});
        phone.send(ac.getData(), 10);
        UdpPacketData fi = new UdpPacketData(UdpCommon.FcInfo);
        for (int v : new int[]{FcInfo.FC_VARIANT_BETAFLIGHT, 4, 5, 1, 0, 1, 46, 0}) fi.daos.writeByte(v);
        phone.send(fi.getData(), 10);
        phone.transport.sendPong(true, 12345, (byte) -1);
        phone.packets.addAll(asPeer(phone.socket.takeSent(), 3));
        int frameNum = 1 + r.nextInt(30_000);
        for (int i = 0; i < 12; i++) {
            List<Packet> f = new ArrayList<>();
            frame(f, r, frameNum++, 200 + r.nextInt(i % 4 == 0 ? 9000 : 2500), i % 6 == 0, kind == 1 ? 2 : 1, 30);
            phone.packets.addAll(f);
            phone.send(telemetryPacket(r, FLIGHT_CONTROLLERS[1], 1 + r.nextInt(4)), 2);
            if (i % 3 == 0) phone.raw(new byte[]{UdpCommon.AudioFrame, 0x21, 0x10, 0x05, 0x00}, 1);
            if (i % 4 == 1) { // a frame's times (FRAME_TIME), to a PC that asked
                byte[] t = new byte[ControlLink.FRAME_TIME_BYTES];
                t[0] = PacketIds.FRAME_TIME;
                t[1] = (byte) (frameNum >> 8);
                t[2] = (byte) frameNum;
                Bytes.putInt(t, 5, 1000 + i);
                phone.raw(t, 0);
            }
        }
        if (kind == 0) { // the phone's own word outside the session's numbering: a check-in, a spectator answer
            phone.raw(sleepHello(r, true), 100);
            phone.raw(Spectate.pilotAt(OTHER_IP, PC_PORT), 10);
            phone.packets.add(new Packet(LinkInput.FROM_OTHER, 10, Names.query(KEY)));
            phone.packets.add(new Packet(LinkInput.FROM_OTHER, 10, answer.clone())); // the answer from elsewhere: no session from it
        }
        pc.close();
        phone.close();
        return new LinkInput(Target.PC_LINK, LinkInput.BYTES_INTACT | (kind == 1 ? VIDEO_WAIT_KEY : 0), phone.packets);
    }

    static byte[] sleepHello(Random r, boolean notice) {
        byte[] head = {PacketIds.SLEEP_HELLO, (byte) r.nextInt(101), 0, 5, 0x0f, (byte) 0xa0,
                (byte) (OverwatchProtocol.HELLO_OVERWATCH | (notice ? OverwatchProtocol.HELLO_NOTICE : 0)), 0};
        FleetProtocol.Status s = new FleetProtocol.Status(80, false, 3, 1, 9, 250, 3, 10, 77);
        FleetProtocol.Where w = new FleetProtocol.Where("MobileAlpha", 1, 59.9, 10.7, 30);
        FleetProtocol.Notice n = notice ? new FleetProtocol.Notice(123.4, FleetProtocol.Notice.HEADING_FC, 3, 5, 600) : null;
        return FleetProtocol.hello(head, s, w, n, KEY);
    }

    /** Frames as the phone sends them, now and then one lost, doubled or late - every datagram the phone's own. */
    static LinkInput pcVideo(Random r, int kind) throws IOException {
        List<Packet> p = new ArrayList<>();
        int frameNum = kind == 5 ? 32760 : 1 + r.nextInt(30_000); // one run across the numbers' wrap
        for (int i = 0; i < 10; i++) {
            int size = i == 0 || kind == 4 && i == 5 ? 20_000 + r.nextInt(40_000) : 100 + r.nextInt(i % 3 == 0 ? 8000 : 2000);
            frame(p, r, frameNum, size, i == 0 || i == 5, kind % 3, 33);
            frameNum = (frameNum + 1) & 0x7fff;
        }
        if (kind == 1) p.add(new Packet(LinkInput.FROM_PEER, 0, Arrays.copyOf(p.get(3).data(), p.get(3).data().length))); // a copy, late
        return new LinkInput(Target.PC_VIDEO, LinkInput.BYTES_INTACT | (kind >= 3 ? VIDEO_WAIT_KEY : 0), p);
    }

    static LinkInput pcTelemetry(Random r, int kind) throws IOException {
        List<Packet> p = new ArrayList<>();
        for (int i = 0; i < 20; i++) p.add(new Packet(LinkInput.FROM_PEER, 50, telemetryPacket(r, FLIGHT_CONTROLLERS[kind], 1 + r.nextInt(6))));
        return new LinkInput(Target.PC_TELEMETRY, LinkInput.BYTES_INTACT | (kind << 1), p);
    }

    /** An overwatch event (alert, preview, clip), check-ins, names, the spectator's and observer's packets. */
    static LinkInput pcOutsideSeed(Random r, int kind) throws IOException {
        List<Packet> p = new ArrayList<>();
        int token = r.nextInt(), event = r.nextInt(1000);
        p.add(new Packet(LinkInput.FROM_OTHER, 0, OverwatchProtocol.alert(new OverwatchProtocol.Alert(token, event, 1, OverwatchProtocol.ALERT_LIGHT,
                1_700_000_000_000L, 0.2f, 0.3f, 0.1f, 0.2f, 12.5f, 66f, 599_000_000, 107_000_000, 30, 80, "MobileAlpha"))));
        for (int u = 0; u < 3; u++) {
            byte[] unit = new byte[u == 0 ? 3000 + r.nextInt(5000) : 200 + r.nextInt(900)];
            r.nextBytes(unit);
            for (byte[] f : OverwatchProtocol.fragments(token, event, u, u == 0 ? OverwatchProtocol.UNIT_KEY_FRAME : OverwatchProtocol.UNIT_FRAME, unit)) {
                p.add(new Packet(LinkInput.FROM_OTHER, 5, f));
            }
        }
        byte[] sha = new byte[32];
        r.nextBytes(sha);
        p.add(new Packet(LinkInput.FROM_OTHER, 10, OverwatchProtocol.clipInfo(new OverwatchProtocol.ClipInfo(token, event, 5000, sha, 10_000, 1_700_000_000_000L,
                true, 1280, 720, 1, 1_700_000_000_000L))));
        byte[] chunk = new byte[OverwatchProtocol.FRAGMENT];
        r.nextBytes(chunk);
        p.add(new Packet(LinkInput.FROM_OTHER, 10, OverwatchProtocol.clipChunk(token, event, 0, chunk, 0, chunk.length)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, OverwatchProtocol.clipEnded(token, event, 1_700_000_010_000L, true)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, OverwatchProtocol.clipNone(token, event + 1, "no clip")));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, OverwatchProtocol.status(token, OverwatchProtocol.STATE_WATCHING, 0, 77, 251)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, sleepHello(r, kind == 0)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Names.reply(KEY, Names.Kind.PHONE, "MobileAlpha", "Pixel", "2.3.0-pc.55")));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Names.reply(KEY, Names.Kind.PC, "PCBravo", "Laptop", "1.0.71", "192.0.2.9:5000")));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Spectate.pilotAt(OTHER_IP, PC_PORT)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Spectate.pilotView(new ControlLink.PilotView(true, true, false, false, kind == 1, false, "THROTTLE DOWN",
                "LINK BACK", "hint", new short[]{1500, 1500, 1000, 1500, 1000, 1000, 1000, 1000}, "MobileAlpha", false))));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Spectate.hello(KEY, Spectate.HELLO_MENDS_RS)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Spectate.withPort(Spectate.WELCOME, PC_PORT)));
        p.add(new Packet(LinkInput.FROM_OTHER, 10, Observe.encodeState(Observe.STREAMING, 640, 360, 15, 1, Observe.STATE_SOUND_HEARD)));
        return new LinkInput(Target.PC_OUTSIDE, LinkInput.BYTES_INTACT, p);
    }

    // ================================================================================================ helpers

    static String hex(byte[] d, int len) {
        StringBuilder s = new StringBuilder().append(len).append(" bytes:");
        for (int i = 0; i < Math.min(len, 48); i++) s.append(String.format(" %02x", d[i] & 0xff));
        if (len > 48) s.append(" ...");
        return s.toString();
    }
}
