package de.droiddrone.pc.setup.ardu;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Parser;
import com.MAVLink.common.msg_command_ack;
import com.MAVLink.common.msg_command_long;
import com.MAVLink.common.msg_param_request_list;
import com.MAVLink.common.msg_param_request_read;
import com.MAVLink.common.msg_param_set;
import com.MAVLink.common.msg_param_value;
import com.MAVLink.common.msg_statustext;
import com.MAVLink.minimal.msg_heartbeat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fleet PRD AP-7 / area H: a MAVLink conversation with an ArduPilot flight controller on the bench - over USB from this
 * PC (ArduUsb), or any other {@link Wire} (the tests' made-up ArduPilot). This PC is a ground station: system 255,
 * component 190 (Mission Planner's), a heartbeat once a second. Parameters are read whole (PARAM_REQUEST_LIST, the
 * missing ones asked again by index), written one at a time and each read back (PARAM_SET answers with PARAM_VALUE);
 * commands wait for their COMMAND_ACK. Never used for a drone in the air: the setup tools ask {@link #armed()} first.
 * One reader thread; the calls block their caller (a setup tool's own worker thread, never Swing's).
 */
public final class MavSession implements AutoCloseable {
    /** The bytes in and out: a serial port, or a test's made-up flight controller. */
    public interface Wire {
        void write(byte[] data) throws IOException;

        /** Up to buf.length bytes, waiting at most timeoutMs; 0 when nothing came, -1 when the wire is gone. */
        int read(byte[] buf, int timeoutMs) throws IOException;

        void close();
    }

    public static final int GCS_SYSID = 255, GCS_COMPID = 190;
    /** MAV_PARAM_TYPE_REAL32: ArduPilot takes every value as a float and rounds it to the parameter's own type. */
    static final short PARAM_REAL32 = 9;

    private final Wire wire;
    private final Parser parser = new Parser();
    private final Object lock = new Object();
    private final List<Consumer<MAVLinkPacket>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> texts = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;
    private volatile int fcSysId = -1, fcCompId = 1;
    private volatile boolean armed;
    private volatile long heartbeatAt;
    private volatile int seq;
    private final Thread reader, beat;

    /** A ground station on the wire: this PC sends heartbeats once a second (USB on the bench). */
    public MavSession(Wire wire) {
        this(wire, true);
    }

    /** @param heartbeats false through the phone's tunnel: the phone is the drone's ground station there */
    public MavSession(Wire wire, boolean heartbeats) {
        this.wire = wire;
        reader = new Thread(this::readLoop, "mavSessionReader");
        reader.setDaemon(true);
        reader.start();
        beat = new Thread(this::beatLoop, "mavSessionHeartbeat");
        beat.setDaemon(true);
        if (heartbeats) beat.start();
    }

    /** The FC's system id from its heartbeat, -1 before the first. */
    public int fcSysId() {
        return fcSysId;
    }

    /** The FC's armed flag from a heartbeat no older than 3 s; true (not known) without one - nothing is written then. */
    public boolean armed() {
        return heartbeatAt == 0 || System.currentTimeMillis() - heartbeatAt > 3000 || armed;
    }

    private volatile int customMode = -1, gpsFix = -1;
    private volatile double[] position;

    /** The FC's flight mode (its heartbeat's custom mode), -1 before the first. */
    public int customMode() {
        return customMode;
    }

    /** GPS_RAW_INT's fix type (3: 3D), -1 before the first. */
    public int gpsFix() {
        return gpsFix;
    }

    /** {lat, lon, height above sea level m, height above home m, climb m/s} from GLOBAL_POSITION_INT, or null. */
    public double[] position() {
        return position;
    }

    /** Asks the FC to send a message this many times a second (MAV_CMD_SET_MESSAGE_INTERVAL). */
    public int stream(int msgId, double hz) throws IOException, InterruptedException {
        return command(511, msgId, (float) (1_000_000 / hz), 0, 0, 0, 0, 0, 2000);
    }

    /** The FC's own true armed flag from its last heartbeat (no "not known" folding). */
    public boolean armedFlag() {
        return armed;
    }

    /** The flight controller's last heartbeat is no older than 3 s. */
    public boolean heartbeatFresh() {
        long at = heartbeatAt;
        return at != 0 && System.currentTimeMillis() - at <= 3000;
    }

    /** A heartbeat came from the flight controller within timeoutMs. */
    public boolean awaitHeartbeat(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (fcSysId < 0 && open) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return false;
                lock.wait(left);
            }
        }
        return fcSysId >= 0;
    }

    public void onText(Consumer<String> t) {
        texts.add(t);
    }

    public void onPacket(Consumer<MAVLinkPacket> l) {
        listeners.add(l);
    }

    public void removePacketListener(Consumer<MAVLinkPacket> l) {
        listeners.remove(l);
    }

    // ---------------------------------------------------------------- parameters

    /**
     * Every parameter of the flight controller, by name, in its own order. Missing indexes are asked again one by one
     * (a USB hiccup drops a few of ~1000); throws when the list isn't whole by the deadline.
     */
    public Map<String, Float> readAllParams(long timeoutMs) throws IOException, InterruptedException {
        Map<Integer, String> names = new java.util.TreeMap<>();
        Map<String, Float> values = new java.util.HashMap<>();
        int[] count = {-1};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_param_value.MAVLINK_MSG_ID_PARAM_VALUE) return;
            msg_param_value v = new msg_param_value(p);
            synchronized (got) {
                count[0] = v.param_count;
                if (v.param_index >= 0 && v.param_index < 65535) names.put(v.param_index, v.getParam_Id());
                values.put(v.getParam_Id(), v.param_value);
                got.notifyAll();
            }
        };
        onPacket(l);
        try {
            send(new msg_param_request_list(target(), (short) fcCompId, GCS_SYSID, GCS_COMPID, true).pack());
            long end = System.currentTimeMillis() + timeoutMs;
            long lastAsk = System.currentTimeMillis();
            while (true) {
                synchronized (got) {
                    if (count[0] >= 0 && names.size() >= count[0]) break;
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("parameters: " + names.size() + " of " + Math.max(count[0], 0) + " read");
                    got.wait(Math.min(left, 300));
                }
                // quiet for a moment with some missing: ask for those by index
                if (count[0] > 0 && System.currentTimeMillis() - lastAsk > 1000) {
                    lastAsk = System.currentTimeMillis();
                    List<Integer> missing = new java.util.ArrayList<>();
                    synchronized (got) {
                        for (int i = 0; i < count[0] && missing.size() < 20; i++) if (!names.containsKey(i)) missing.add(i);
                    }
                    for (int i : missing) {
                        send(new msg_param_request_read((short) i, target(), (short) fcCompId, new byte[16], GCS_SYSID, GCS_COMPID, true).pack());
                    }
                }
            }
            Map<String, Float> ordered = new LinkedHashMap<>();
            synchronized (got) {
                for (String n : names.values()) ordered.put(n, values.get(n));
            }
            return ordered;
        } finally {
            removePacketListener(l);
        }
    }

    /**
     * One parameter set and read back: PARAM_SET, then the PARAM_VALUE the FC answers with (three tries). Returns the
     * value the FC now holds; the caller compares it with what it asked for.
     */
    public float setParam(String name, float value, long timeoutMs) throws IOException, InterruptedException {
        if (name.length() > 16) throw new IOException(name + ": a name longer than 16");
        float[] back = {Float.NaN};
        boolean[] answered = {false};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_param_value.MAVLINK_MSG_ID_PARAM_VALUE) return;
            msg_param_value v = new msg_param_value(p);
            if (!name.equals(v.getParam_Id())) return;
            synchronized (got) {
                back[0] = v.param_value;
                answered[0] = true;
                got.notifyAll();
            }
        };
        onPacket(l);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                send(new msg_param_set(value, target(), (short) fcCompId, id16(name), PARAM_REAL32, GCS_SYSID, GCS_COMPID, true).pack());
                long end = System.currentTimeMillis() + timeoutMs;
                synchronized (got) {
                    while (!answered[0]) {
                        long left = end - System.currentTimeMillis();
                        if (left <= 0) break;
                        got.wait(left);
                    }
                    if (answered[0]) return back[0];
                }
            }
            throw new IOException(name + ": no answer");
        } finally {
            removePacketListener(l);
        }
    }

    /** One parameter read (PARAM_REQUEST_READ by name); NaN when the FC has none by that name. */
    public float readParam(String name, long timeoutMs) throws IOException, InterruptedException {
        float[] back = {Float.NaN};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_param_value.MAVLINK_MSG_ID_PARAM_VALUE) return;
            msg_param_value v = new msg_param_value(p);
            if (!name.equals(v.getParam_Id())) return;
            synchronized (got) {
                back[0] = v.param_value;
                got.notifyAll();
            }
        };
        onPacket(l);
        try {
            send(new msg_param_request_read((short) -1, target(), (short) fcCompId, id16(name), GCS_SYSID, GCS_COMPID, true).pack());
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (got) {
                while (Float.isNaN(back[0])) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    got.wait(left);
                }
                return back[0];
            }
        } finally {
            removePacketListener(l);
        }
    }

    // ---------------------------------------------------------------- commands

    /** COMMAND_LONG and its COMMAND_ACK's result (MAV_RESULT), or -1 when none came. */
    public int command(int command, float p1, float p2, float p3, float p4, float p5, float p6, float p7, long timeoutMs)
            throws IOException, InterruptedException {
        int[] result = {-1};
        Object got = new Object();
        Consumer<MAVLinkPacket> l = p -> {
            if (p.msgid != msg_command_ack.MAVLINK_MSG_ID_COMMAND_ACK) return;
            msg_command_ack a = new msg_command_ack(p);
            if (a.command != command) return;
            synchronized (got) {
                result[0] = a.result;
                got.notifyAll();
            }
        };
        onPacket(l);
        try {
            send(new msg_command_long(p1, p2, p3, p4, p5, p6, p7, command, target(), (short) fcCompId, (short) 0,
                    GCS_SYSID, GCS_COMPID, true).pack());
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (got) {
                while (result[0] < 0) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    got.wait(left);
                }
                return result[0];
            }
        } finally {
            removePacketListener(l);
        }
    }

    /** A packet as it is (the accelerometer calibration's position answers, the motor test). */
    public void send(MAVLinkPacket p) throws IOException {
        synchronized (wire) {
            p.seq = seq++ & 0xff;
            wire.write(p.encodePacket());
        }
    }

    // ---------------------------------------------------------------- the threads

    private short target() {
        int id = fcSysId;
        return (short) (id > 0 ? id : 1);
    }

    /**
     * MAVLink 2 leaves a payload's trailing zero bytes out; upstream's Parser keeps the payload as short as it came and
     * its messages then read past its end (an IndexOutOfBoundsException on a real ArduPilot's COMMAND_ACK). The payload
     * is given its full room here, the bytes not sent reading as 0 - as MAVLink says they are.
     */
    static MAVLinkPacket pad(MAVLinkPacket p) {
        if (p.payload == null || p.payload.payload.capacity() >= com.MAVLink.Messages.MAVLinkPayload.MAX_PAYLOAD_SIZE) return p;
        com.MAVLink.Messages.MAVLinkPayload full = new com.MAVLink.Messages.MAVLinkPayload();
        java.nio.ByteBuffer src = p.payload.payload;
        for (int i = 0; i < src.position(); i++) full.add(src.get(i));
        p.payload = full;
        return p;
    }

    static byte[] id16(String name) {
        byte[] id = new byte[16];
        byte[] b = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, id, 0, Math.min(16, b.length));
        return id;
    }

    private void beatLoop() {
        while (open) {
            try {
                send(new msg_heartbeat(0, (short) 6, (short) 8, (short) 0, (short) 4, (short) 3, GCS_SYSID, GCS_COMPID, true).pack());
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                if (!open) return;
            }
        }
    }

    private void readLoop() {
        byte[] buf = new byte[1024];
        while (open) {
            int n;
            try {
                n = wire.read(buf, 200);
            } catch (IOException e) {
                n = -1;
            }
            if (n < 0) {
                close();
                return;
            }
            for (int i = 0; i < n; i++) {
                MAVLinkPacket p = parser.mavlink_parse_char(buf[i] & 0xff);
                if (p == null) continue;
                try {
                    dispatch(pad(p));
                } catch (RuntimeException e) {
                    // one frame the library can't read never stops the reader
                }
            }
        }
    }

    private void dispatch(MAVLinkPacket p) {
        if (p.msgid == com.MAVLink.common.msg_global_position_int.MAVLINK_MSG_ID_GLOBAL_POSITION_INT) {
            com.MAVLink.common.msg_global_position_int g = new com.MAVLink.common.msg_global_position_int(p);
            position = new double[]{g.lat / 1e7, g.lon / 1e7, g.alt / 1000.0, g.relative_alt / 1000.0, g.vz / -100.0};
        } else if (p.msgid == com.MAVLink.common.msg_gps_raw_int.MAVLINK_MSG_ID_GPS_RAW_INT) {
            gpsFix = new com.MAVLink.common.msg_gps_raw_int(p).fix_type;
        }
        if (p.msgid == msg_heartbeat.MAVLINK_MSG_ID_HEARTBEAT) {
            msg_heartbeat h = new msg_heartbeat(p);
            if (h.type != 6) { // not another ground station's
                customMode = (int) h.custom_mode;
                armed = (h.base_mode & 128) != 0; // MAV_MODE_FLAG_SAFETY_ARMED
                heartbeatAt = System.currentTimeMillis();
                fcCompId = p.compid;
                synchronized (lock) {
                    fcSysId = p.sysid;
                    lock.notifyAll();
                }
            }
        } else if (p.msgid == msg_statustext.MAVLINK_MSG_ID_STATUSTEXT) {
            String t = new msg_statustext(p).getText();
            if (t != null) for (Consumer<String> c : texts) c.accept(t.trim());
        }
        for (Consumer<MAVLinkPacket> l : listeners) {
            try {
                l.accept(p);
            } catch (RuntimeException ignored) {
                // a listener's own problem
            }
        }
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        wire.close();
        beat.interrupt();
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
