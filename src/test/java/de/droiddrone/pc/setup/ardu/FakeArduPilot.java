package de.droiddrone.pc.setup.ardu;

import com.MAVLink.MAVLinkPacket;
import com.MAVLink.Parser;
import com.MAVLink.common.msg_command_ack;
import com.MAVLink.common.msg_command_long;
import com.MAVLink.common.msg_param_request_list;
import com.MAVLink.common.msg_param_request_read;
import com.MAVLink.common.msg_param_set;
import com.MAVLink.common.msg_param_value;
import com.MAVLink.minimal.msg_heartbeat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A made-up ArduPilot Copter on the far end of a {@link MavSession.Wire}: parameters, heartbeats, command answers and
 * the accelerometer calibration's position questions - enough of ArduPilot for the setup's tests. Names and numbers
 * are made up.
 */
public final class FakeArduPilot implements MavSession.Wire {
    public final Map<String, Float> params = new LinkedHashMap<>();
    public final List<Integer> commands = new ArrayList<>();
    final List<float[]> commandParams = new ArrayList<>();
    public volatile boolean armed;
    public volatile int sysId = 1;
    /** PARAM_VALUEs of the whole list not sent the first time (to be asked again by index). */
    public volatile int dropEvery;
    /** A parameter that stays at its value whatever is set (reads back the old one). */
    public volatile String stuck;
    /** The board pulled off USB after this many PARAM_SETs (-1: never): writes fail, reads end. */
    public volatile int unplugAfterSets = -1;
    /** It arms itself after this many PARAM_SETs (-1: never): the writer must stop. */
    public volatile int armAfterSets = -1;
    /** Never answers a log's data (a link that drops everything). */
    public volatile boolean silentLogData;
    /** Log list entries lost the first time they are sent (asked again, they come). */
    public final java.util.Set<Integer> dropEntriesOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Each LOG_REQUEST_LIST as "start-end". */
    public final java.util.List<String> listAsks = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The whole list was asked for from Swing's thread (a frozen window while it comes). */
    public volatile boolean listAskedOnSwing;
    /** PARAM_SETs so far. */
    public volatile int sets;
    private volatile boolean unplugged;
    /** AP-10: the logs on its card (id 1, 2 ...) and every n-th LOG_DATA dropped on the way (0: none). */
    public final java.util.List<byte[]> logs = new java.util.ArrayList<>();
    public volatile int dropLogEvery;
    private int logSent;

    /** Answers to COMMAND_LONG by command (default 0 = accepted). */
    public final Map<Integer, Integer> results = new java.util.HashMap<>();
    private final BlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
    private final Parser parser = new Parser();
    private volatile boolean closed;
    private int accelStep;
    private final Thread beat;

    public FakeArduPilot() {
        params.put("SYSID_THISMAV", 1f);
        params.put("SYSID_MYGCS", 255f);
        params.put("FS_THR_ENABLE", 1f);
        params.put("RC_OVERRIDE_TIME", 3f);
        params.put("FS_GCS_ENABLE", 0f);
        params.put("FS_GCS_TIMEOUT", 5f);
        params.put("FS_CRASH_CHECK", 1f);
        params.put("FS_VIBE_ENABLE", 1f);
        params.put("BATT_CAPACITY", 3300f);
        params.put("BATT_LOW_VOLT", 10.5f);
        params.put("BATT_CRT_VOLT", 0f);
        params.put("BATT_FS_LOW_ACT", 2f);
        params.put("BATT_FS_CRT_ACT", 0f);
        params.put("BATT_ARM_VOLT", 0f);
        params.put("BATT_ARM_MAH", 0f);
        params.put("TERRAIN_ENABLE", 1f);
        params.put("RTL_ALT", 1500f);
        params.put("RTL_ALT_TYPE", 0f);
        params.put("WPNAV_SPEED", 1000f);
        params.put("WPNAV_SPEED_UP", 250f);
        params.put("WPNAV_SPEED_DN", 150f);
        params.put("RC5_OPTION", 0f);
        params.put("FLTMODE_CH", 5f);
        for (int i = 1; i <= 6; i++) params.put("FLTMODE" + i, 0f);
        params.put("AVD_ENABLE", 0f);
        params.put("AVD_F_ACTION", 0f);
        params.put("AVD_F_DIST_XY", 5f);
        params.put("AVD_F_DIST_Z", 100f);
        params.put("AVD_W_DIST_XY", 300f);
        params.put("AVD_W_DIST_Z", 300f);
        // ADSB_TYPE left out: an "older firmware" lacks it
        for (int i = 0; i < 60; i++) params.put("FILLER_" + i, i * 1.5f);
        beat = new Thread(() -> {
            while (!closed) {
                emit(new msg_heartbeat(5, (short) 2, (short) 3, (short) (armed ? 128 | 1 : 1), (short) 3, (short) 3, sysId, 1, true).pack());
                try {
                    Thread.sleep(100); // ten a second: the tests needn't wait for the real one-a-second
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fakeArduPilotBeat");
        beat.setDaemon(true);
        beat.start();
    }

    private synchronized void emit(MAVLinkPacket p) {
        out.add(p.encodePacket());
    }

    @Override
    public void write(byte[] data) throws java.io.IOException {
        if (unplugged || closed) throw new java.io.IOException("write failed");
        for (byte b : data) {
            MAVLinkPacket p = parser.mavlink_parse_char(b & 0xff);
            if (p != null) handle(MavSession.pad(p));
        }
    }

    private void handle(MAVLinkPacket p) {
        switch (p.msgid) {
            case msg_param_request_list.MAVLINK_MSG_ID_PARAM_REQUEST_LIST -> {
                if (javax.swing.SwingUtilities.isEventDispatchThread()) listAskedOnSwing = true;
                List<String> names = new ArrayList<>(params.keySet());
                for (int i = 0; i < names.size(); i++) {
                    if (dropEvery > 0 && i % dropEvery == 0) continue;
                    value(names.get(i), i, names.size());
                }
            }
            case msg_param_request_read.MAVLINK_MSG_ID_PARAM_REQUEST_READ -> {
                msg_param_request_read r = new msg_param_request_read(p);
                List<String> names = new ArrayList<>(params.keySet());
                if (r.param_index >= 0) {
                    if (r.param_index < names.size()) value(names.get(r.param_index), r.param_index, names.size());
                } else {
                    String n = r.getParam_Id();
                    if (params.containsKey(n)) value(n, names.indexOf(n), names.size());
                }
            }
            case msg_param_set.MAVLINK_MSG_ID_PARAM_SET -> {
                msg_param_set s = new msg_param_set(p);
                String n = s.getParam_Id();
                sets++;
                if (sets == armAfterSets) {
                    armed = true; // and says so at once, before it answers the set
                    emit(new msg_heartbeat(5, (short) 2, (short) 3, (short) (128 | 1), (short) 3, (short) 3, sysId, 1, true).pack());
                }
                if (unplugAfterSets >= 0 && sets > unplugAfterSets) {
                    unplugged = true;
                    return;
                }
                if (!params.containsKey(n)) return; // ArduPilot says nothing for a name it hasn't
                if (!n.equals(stuck)) params.put(n, s.param_value);
                List<String> names = new ArrayList<>(params.keySet());
                value(n, names.indexOf(n), names.size());
            }
            case msg_command_long.MAVLINK_MSG_ID_COMMAND_LONG -> {
                msg_command_long c = new msg_command_long(p);
                commands.add(c.command);
                commandParams.add(new float[]{c.param1, c.param2, c.param3, c.param4, c.param5, c.param6, c.param7});
                if (c.command == ArduTools.MAV_CMD_ACCELCAL_VEHICLE_POS) {
                    accelStep++;
                    askPosition(accelStep < 6 ? accelStep + 1 : ArduTools.ACCELCAL_SUCCESS);
                    return;
                }
                int result = results.getOrDefault(c.command, 0);
                emit(new msg_command_ack(c.command, (short) result, (short) 0, 0, (short) 255, (short) 190, sysId, 1, true).pack());
                if (c.command == ArduTools.MAV_CMD_PREFLIGHT_CALIBRATION && Math.round(c.param5) == 1 && result == 0) {
                    accelStep = 0;
                    askPosition(1);
                }
            }
            case com.MAVLink.common.msg_log_request_list.MAVLINK_MSG_ID_LOG_REQUEST_LIST -> {
                com.MAVLink.common.msg_log_request_list r = new com.MAVLink.common.msg_log_request_list(p);
                listAsks.add(r.start + "-" + r.end);
                if (logs.isEmpty()) emit(new com.MAVLink.common.msg_log_entry(0, 0, 0, 0, 0, sysId, 1, true).pack());
                for (int i = 0; i < logs.size(); i++) {
                    int id = i + 1;
                    if (id < r.start || id > r.end) continue;
                    if (dropEntriesOnce.remove(id)) continue; // lost on the way, this once
                    emit(new com.MAVLink.common.msg_log_entry(1_800_000_000L + i * 600, logs.get(i).length, id, logs.size(), logs.size(), sysId, 1, true).pack());
                }
            }
            case com.MAVLink.common.msg_log_request_data.MAVLINK_MSG_ID_LOG_REQUEST_DATA -> {
                com.MAVLink.common.msg_log_request_data r = new com.MAVLink.common.msg_log_request_data(p);
                if (r.id < 1 || r.id > logs.size() || silentLogData) return;
                byte[] log = logs.get(r.id - 1);
                long end = Math.min(log.length, r.ofs + r.count);
                for (long o = r.ofs; o < end; o += 90) {
                    if (dropLogEvery > 0 && ++logSent % dropLogEvery == 0) continue;
                    short[] d = new short[90];
                    int n = (int) Math.min(90, end - o);
                    for (int i = 0; i < n; i++) d[i] = (short) (log[(int) o + i] & 0xff);
                    emit(new com.MAVLink.common.msg_log_data(o, r.id, (short) n, d, sysId, 1, true).pack());
                }
            }
            case com.MAVLink.common.msg_rc_channels_override.MAVLINK_MSG_ID_RC_CHANNELS_OVERRIDE -> {
                // the ARM switch is whichever channel has RCn_OPTION 153: high arms, low disarms
                com.MAVLink.common.msg_rc_channels_override o = new com.MAVLink.common.msg_rc_channels_override(p);
                int[] ch = {o.chan1_raw, o.chan2_raw, o.chan3_raw, o.chan4_raw, o.chan5_raw, o.chan6_raw, o.chan7_raw, o.chan8_raw};
                for (int n = 1; n <= 8; n++) {
                    Float opt = params.get("RC" + n + "_OPTION");
                    if (opt == null || opt != 153f) continue;
                    if (ch[n - 1] > 1800 && ch[n - 1] != 65535) armed = true;
                    else if (ch[n - 1] > 0 && ch[n - 1] < 1200) armed = false;
                }
            }
            default -> {
            }
        }
    }

    private void askPosition(int pos) {
        emit(new msg_command_long(pos, 0, 0, 0, 0, 0, 0, ArduTools.MAV_CMD_ACCELCAL_VEHICLE_POS, (short) 255, (short) 190, (short) 0,
                sysId, 1, true).pack());
    }

    private void value(String name, int index, int count) {
        emit(new msg_param_value(params.get(name), count, index, MavSession.id16(name), (short) 9, sysId, 1, true).pack());
    }

    @Override
    public int read(byte[] buf, int timeoutMs) throws java.io.IOException {
        if (closed || unplugged) return -1;
        byte[] chunk;
        try {
            chunk = out.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return 0;
        }
        if (chunk == null) return 0;
        int n = Math.min(buf.length, chunk.length);
        System.arraycopy(chunk, 0, buf, 0, n);
        if (n < chunk.length) {
            byte[] rest = java.util.Arrays.copyOfRange(chunk, n, chunk.length);
            ((LinkedBlockingQueue<byte[]>) out).add(rest); // order isn't kept for an over-long chunk; frames are short
        }
        return n;
    }

    @Override
    public void close() {
        closed = true;
        beat.interrupt();
    }
}
