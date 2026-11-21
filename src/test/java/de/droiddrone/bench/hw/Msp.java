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
