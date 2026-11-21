package de.droiddrone.bench.hw;

import de.droiddrone.common.BeeperQuiet;
import de.droiddrone.common.FcCommon;
import de.droiddrone.pc.setup.MotorTest;

import java.util.HexFormat;

/**
 * Betaflight's MSP numbers and byte order for the hardware rig's tools and its made-up board. The app has most of
 * them where they are used (MotorTest, EscLink: not public there); the numbers are Betaflight's own
 * (msp_protocol.h, msp_protocol_v2_betaflight.h) and the same in both places.
 */
public final class Msp {
    public static final int API_VERSION = FcCommon.MSP_API_VERSION, FC_VARIANT = FcCommon.MSP_FC_VARIANT,
            FC_VERSION = FcCommon.MSP_FC_VERSION, BOARD_INFO = 4, BUILD_INFO = 5, MODE_RANGES = 34, FEATURE_CONFIG = 36,
            SET_FEATURE_CONFIG = 37, MIXER_CONFIG = FcCommon.MSP_MIXER_CONFIG, SET_MIXER_CONFIG = 43,
            RX_MAP = FcCommon.MSP_RX_MAP, REBOOT = 68, ADVANCED_CONFIG = 90, STATUS = FcCommon.MSP_STATUS, MOTOR = 104,
            ANALOG = FcCommon.MSP_ANALOG, BOXIDS = FcCommon.MSP_BOXIDS, BATTERY_STATE = FcCommon.MSP_BATTERY_STATE,
            MOTOR_CONFIG = 131, UID = 160, BEEPER_CONFIG = BeeperQuiet.MSP_BEEPER_CONFIG,
            SET_BEEPER_CONFIG = BeeperQuiet.MSP_SET_BEEPER_CONFIG, SET_RAW_RC = FcCommon.MSP_SET_RAW_RC, SET_MOTOR = 214,
            SET_PASSTHROUGH = 245, EEPROM_WRITE = 250;
    /**
     * The flight controller keeper's arming lock (R3): MSP_ARMING_DISABLE, one byte - not 0 sets Betaflight's arming
     * block "MSP" and disarms an armed board, 0 takes the block off again. It lives in the board's memory only: a
     * restart or a power cycle clears it.
     */
    public static final int ARMING_DISABLE = 99;
    /** MSP_FAILSAFE_CONFIG: what the board does when its sticks stop (read only, for the keeper's report). */
    public static final int FAILSAFE_CONFIG = 75;
    /** Where Betaflight 4.4 shows the arming block "MSP" among MSP_STATUS' arming-disable flags; the keeper measures it on each board. */
    public static final int ARMING_DISABLED_MSP_BIT = 16;
    /** The arming block "ARM switch": the switch was on while something else blocked - it has to go off once before it counts. */
    public static final int ARMING_DISABLED_ARM_SWITCH_BIT = 25;
    /** MSP v2 only: the motor output order, and DShot commands to the ESCs. */
    public static final int MOTOR_OUTPUT_REORDERING = 0x3001, SET_MOTOR_OUTPUT_REORDERING = 0x3002, SEND_DSHOT_COMMAND = 0x3003;
    /** 3D mode in the feature mask: there 1000 is full reverse and 1500 is stop. */
    public static final int FEATURE_3D = 1 << 12;
    /** A motor's stop in 3D mode (Betaflight Configurator's motor sliders rest there too). */
    public static final int STOP_3D = 1500;

    private Msp() {
    }

    /** MSP_STATUS: the first flight mode flag is ARM. */
    public static boolean armed(byte[] status) {
        return status.length > 6 && (status[6] & 1) != 0;
    }

    /**
     * MSP_STATUS: Betaflight's arming-disable flags (a bit set = that block holds), or -1 when the answer is too short
     * to have them: cycle time, I2C errors, sensors (2 bytes each), 4 bytes of mode flags, profile, load (2), 2 unused,
     * then a count of further mode-flag bytes (its low 4 bits) and those bytes, the count of arming-disable flags, and
     * the flags in 4 bytes.
     */
    public static int armingDisableFlags(byte[] status) {
        if (status == null || status.length < 16) return -1;
        int at = 16 + (status[15] & 0x0f);
        return status.length < at + 5 ? -1 : u32(status, at + 1);
    }

    /** Betaflight 4.4's arming blocks in the order of their flags (runtime_config.h), as its OSD names them: for a person to read, never to act on. */
    private static final String[] ARMING_BLOCKS = {"NOGYRO", "FAILSAFE", "RXLOSS", "BADRX", "BOXFAILSAFE", "RUNAWAY", "CRASH", "THROTTLE", "ANGLE", "BOOTGRACE",
            "NOPREARM", "LOAD", "CALIB", "CLI", "CMS", "BST", "MSP", "PARALYZE", "GPS", "RESCUE_SW", "RPMFILTER", "REBOOT_REQD", "DSHOT_BBANG", "ACC_CALIB",
            "MOTOR_PROTO", "ARMSWITCH"};

    /** The arming blocks that hold, by Betaflight 4.4's names: "MSP+ARMSWITCH"; "none"; "?" when the flags are not known. */
    public static String armingBlocks(int flags) {
        if (flags == -1) return "?";
        StringBuilder s = new StringBuilder();
        for (int bit = 0; bit < 32; bit++) {
            if ((flags >> bit & 1) == 0) continue;
            s.append(s.length() > 0 ? "+" : "").append(bit < ARMING_BLOCKS.length ? ARMING_BLOCKS[bit] : "FLAG" + bit);
        }
        return s.length() == 0 ? "none" : s.toString();
    }

    /** MSP_ANALOG's or MSP_BATTERY_STATE's pack voltage (Betaflight 4.0 on: hundredths of a volt at the end; before: tenths), or NaN. */
    public static double volts(int code, byte[] p) {
        if (p == null) return Double.NaN;
        if (code == ANALOG) return p.length >= 9 ? u16(p, 7) / 100.0 : p.length >= 1 ? (p[0] & 0xff) / 10.0 : Double.NaN;
        if (code == BATTERY_STATE) return p.length >= 11 ? u16(p, 9) / 100.0 : p.length >= 4 ? (p[3] & 0xff) / 10.0 : Double.NaN;
        return Double.NaN;
    }

    /** MSP_SET_RAW_RC's channels as the board takes them (its own raw order: MSP_RX_MAP says which is which). */
    public static int[] channels(byte[] payload) {
        int[] c = new int[payload == null ? 0 : payload.length / 2];
        for (int i = 0; i < c.length; i++) c[i] = u16(payload, 2 * i);
        return c;
    }

    /** MSP_MOTOR: up to eight outputs; one the mixer doesn't have reads 0. */
    public static int[] motors(byte[] payload) {
        int[] m = new int[Math.min(MotorTest.MAX_MOTORS, payload.length / 2)];
        for (int i = 0; i < m.length; i++) m[i] = u16(payload, 2 * i);
        return m;
    }

    /** MSP_SET_MOTOR: every output at this value. */
    public static byte[] allMotors(int value) {
        byte[] p = new byte[MotorTest.MAX_MOTORS * 2];
        for (int i = 0; i < MotorTest.MAX_MOTORS; i++) putU16(p, 2 * i, value);
        return p;
    }

    public static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    public static int u32(byte[] b, int at) {
        return u16(b, at) | u16(b, at + 2) << 16;
    }

    public static void putU16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    public static void putU32(byte[] b, int at, int v) {
        putU16(b, at, v);
        putU16(b, at + 2, v >>> 16);
    }

    public static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    public static byte[] unhex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
