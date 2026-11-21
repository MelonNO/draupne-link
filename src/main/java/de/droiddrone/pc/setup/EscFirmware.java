package de.droiddrone.pc.setup;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * What runs on an ESC, read from its settings memory through Betaflight's ESC passthrough ({@link EscLink}), and the
 * one setting DRAUPNE cares about there: the ESC's own beacon (1.0.71; the pilot, 2026-11-19: "all drones beep after
 * 10 min"). BLHeli_S and what is built on it (Bluejay, JESC, BLHeli_M) beep through the motors once the throttle
 * signal has been zero for "Beacon Delay" - 10 minutes as shipped - whatever the sticks or Betaflight say; a drone
 * resting on its phone (standby, deep sleep, overwatch) does exactly that. Only the ESC's own setting ends it:
 * Beacon Delay = Infinite.
 * <p>
 * The layout is BLHeli_S's (its source, and esc-configurator's tables): settings at the chip's "EEPROM" address,
 * MAIN_REVISION 0x00, SUB_REVISION 0x01, LAYOUT_REVISION 0x02, MOTOR_DIRECTION 0x0B, BEACON_DELAY 0x1D (1 = 1 min,
 * 2 = 2 min, 3 = 5 min, 4 = 10 min, 5 = Infinite), LAYOUT 0x40 / MCU 0x50 / NAME 0x60 (16 characters each). Bluejay
 * keeps all of that where it was. AM32 has another layout and no beacon timer; BLHeli_32 keeps its layout to itself.
 * Pure: {@link EscLink} reads and writes the bytes.
 */
public final class EscFirmware {
    public enum Kind {
        BLHELI_S("BLHeli_S"), BLUEJAY("Bluejay"), JESC("JESC"), BLHELI_M("BLHeli_M"), AM32("AM32"),
        /** An ARM ESC that is not AM32: BLHeli_32 as a rule. Its settings can't be read here. */
        ARM_OTHER("BLHeli_32"), UNKNOWN("unknown");

        public final String label;

        Kind(String label) {
            this.label = label;
        }

        /** BLHeli_S and what is built on it: the beacon delay is where BLHeli_S has it. */
        public boolean blheliS() {
            return this == BLHELI_S || this == BLUEJAY || this == JESC || this == BLHELI_M;
        }
    }

    /** The ESC's processor: where its settings are and how big a page of its memory is (what an erase takes). */
    public record Chip(String name, boolean silabs, int settingsAt, int pageSize) {
    }

    public static final int BEACON_DELAY = 0x1D, MOTOR_DIRECTION = 0x0B;
    public static final int BEACON_INFINITE = 5;
    /** What is read of a SiLabs ESC's settings: Bluejay's are this long (BLHeli_S's the first 0x70 of it). */
    public static final int SILABS_SETTINGS = 0x100;
    public static final int AM32_SETTINGS = 0xB0;
    private static final String[] BEACON = {"", "1 min", "2 min", "5 min", "10 min", "off"};

    private EscFirmware() {
    }

    /** By what the ESC's bootloader says of itself (signature, the interface's mode); null: one this app doesn't know. */
    public static Chip chip(int signature, int mode) {
        if (mode == EscLink.MODE_SILABS) {
            return switch (signature) {
                case 0xE8B1 -> new Chip("EFM8BB1", true, 0x1A00, 512);
                case 0xE8B2 -> new Chip("EFM8BB2", true, 0x1A00, 512);
                case 0xE8B5 -> new Chip("EFM8BB51", true, 0x3000, 2048);
                default -> null;
            };
        }
        if (mode == EscLink.MODE_ARM) {
            return switch (signature) {
                case 0x1F06 -> new Chip("STM32F051", false, 0x7C00, 1024);
                case 0x3506 -> new Chip("ARM 64K", false, 0xF800, 1024);
                default -> null;
            };
        }
        return null;
    }

    /**
     * One ESC as Drone setup shows it. beacon: BLHeli_S's value 1 - 5, or 0 when this firmware has no such timer or
     * it can't be read. problem: why nothing can be said or done (null: none).
     */
    public record Info(int index, Kind kind, String version, String layout, String chip, int beacon, String problem) {
        /** "Bluejay 0.19", "BLHeli_S 16.7", "AM32 2.16", "BLHeli_32". */
        public String firmware() {
            return version.isEmpty() ? kind.label : kind.label + " " + version;
        }

        /** The ESC's own beacon will sound on a resting drone. */
        public boolean beeps() {
            return beacon >= 1 && beacon < BEACON_INFINITE;
        }

        /** This app can switch its beacon off (and it is on). */
        public boolean canSilence() {
            return problem == null && kind.blheliS() && beeps();
        }

        /** "10 min", "off", or "" when there is none to speak of. */
        public String beaconText() {
            return beacon >= 1 && beacon <= BEACON_INFINITE ? BEACON[beacon] : "";
        }

        public static Info failed(int index, String problem) {
            return new Info(index, Kind.UNKNOWN, "", "", "", 0, problem);
        }
    }

    /**
     * A SiLabs ESC from its settings (at least 0x70 bytes from the chip's settings address). jesc: the firmware's
     * bytes 0x80 - 0xFF, where JESC writes its name (null: not read).
     */
    public static Info silabs(int index, Chip chip, byte[] settings, byte[] jesc) {
        if (settings == null || settings.length < 0x70) return Info.failed(index, "settings not read");
        if (blank(settings, 0, 0x70)) return new Info(index, Kind.UNKNOWN, "", "", chip.name(), 0, "no settings (blank)");
        String layout = text(settings, 0x40, 16), mcu = text(settings, 0x50, 16), name = text(settings, 0x60, 16);
        int main = settings[0] & 0xff, sub = settings[1] & 0xff, beacon = settings[BEACON_DELAY] & 0xff;
        Kind kind;
        if (name.toLowerCase(Locale.ROOT).startsWith("bluejay")) kind = Kind.BLUEJAY;
        else if (jesc != null && contains(jesc, "JESC")) kind = Kind.JESC;
        else if (main == 16 && (sub == 8 || sub == 9)) kind = Kind.BLHELI_M;
        else kind = Kind.BLHELI_S;
        String version = main + "." + sub;
        // the settings are BLHeli_S's only when they say so themselves: "#BLHELI$EFM8B21#" and a layout like "#S_H_50#"
        boolean known = mcu.startsWith("#BLHELI$") && layout.startsWith("#") && layout.indexOf('#', 1) > 1;
        if (!known) return new Info(index, Kind.UNKNOWN, "", layout, chip.name(), 0, "settings not understood");
        String tidy = layout.substring(1, layout.indexOf('#', 1)).replace('_', '-');
        if (beacon < 1 || beacon > BEACON_INFINITE) return new Info(index, kind, version, tidy, chip.name(), 0, "beacon setting not understood (" + beacon + ")");
        return new Info(index, kind, version, tidy, chip.name(), beacon, null);
    }

    /** An ARM ESC from its settings (0xB0 bytes from the chip's settings address): AM32, or something else. */
    public static Info arm(int index, Chip chip, byte[] settings) {
        if (settings == null || settings.length < 0x30) return Info.failed(index, "settings not read");
        String name = text(settings, 5, 12);
        int main = settings[3] & 0xff, sub = settings[4] & 0xff;
        // AM32: boot byte 1, a small layout number, a version that is one (blank memory reads 255.255)
        boolean am32 = settings[0] == 1 && (settings[1] & 0xff) < 0x40 && main < 100 && sub < 100;
        if (am32) return new Info(index, Kind.AM32, main + "." + (sub < 10 ? "0" + sub : sub), name, chip.name(), 0, null);
        return new Info(index, Kind.ARM_OTHER, "", "", chip.name(), 0, null);
    }

    /**
     * The settings with the beacon off, for a BLHeli_S kind: the same bytes, Beacon Delay = Infinite. Null when they
     * are not what {@link #silabs} understands, or the beacon is off already - nothing to write then.
     */
    public static byte[] beaconOff(Chip chip, byte[] settings) {
        Info now = silabs(0, chip, settings, null);
        if (!now.canSilence()) return null;
        byte[] out = Arrays.copyOf(settings, settings.length);
        out[BEACON_DELAY] = BEACON_INFINITE;
        return out;
    }

    /** One line for the pilot of several ESCs: "4 x Bluejay 0.19 - beacon 10 min", or each when they differ. */
    public static String summary(java.util.List<Info> escs) {
        if (escs.isEmpty()) return "No ESC answered";
        boolean same = true;
        for (Info i : escs) if (!one(i).equals(one(escs.get(0)))) same = false;
        if (same) return (escs.size() > 1 ? escs.size() + " x " : "") + one(escs.get(0));
        StringBuilder b = new StringBuilder();
        for (Info i : escs) b.append(b.length() == 0 ? "" : "; ").append(i.index() + 1).append(": ").append(one(i));
        return b.toString();
    }

    private static String one(Info i) {
        if (i.problem() != null && i.kind() == Kind.UNKNOWN) return i.problem();
        String s = i.firmware();
        if (i.problem() != null) return s + " - " + i.problem();
        if (i.kind().blheliS()) return s + " - beacon " + i.beaconText();
        if (i.kind() == Kind.AM32) return s + " - no beacon timer";
        return s + " - beacon not readable";
    }

    static boolean blank(byte[] b, int from, int len) {
        for (int i = from; i < from + len && i < b.length; i++) if (b[i] != (byte) 0xFF) return false;
        return true;
    }

    /** The printable characters of a fixed field, trimmed. */
    static String text(byte[] b, int at, int len) {
        StringBuilder s = new StringBuilder();
        for (int i = at; i < at + len && i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c >= 0x20 && c < 0x7F) s.append((char) c);
        }
        return s.toString().trim();
    }

    private static boolean contains(byte[] b, String ascii) {
        return new String(b, StandardCharsets.ISO_8859_1).contains(ascii);
    }
}
