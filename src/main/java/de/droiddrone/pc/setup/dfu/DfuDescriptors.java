package de.droiddrone.pc.setup.dfu;

import java.nio.charset.StandardCharsets;

/** Reading a DFU device's USB descriptors: the configuration's interfaces and DFU functional descriptor, a string. */
public final class DfuDescriptors {
    static final int TYPE_INTERFACE = 4, TYPE_DFU_FUNCTIONAL = 0x21;

    private DfuDescriptors() {
    }

    /** wTransferSize of the DFU functional descriptor in a configuration descriptor, or def. */
    public static int transferSize(byte[] cfg, int len, int def) {
        for (int at = 0; at + 1 < len && (cfg[at] & 0xff) > 0; at += cfg[at] & 0xff) {
            int l = cfg[at] & 0xff;
            if ((cfg[at + 1] & 0xff) == TYPE_DFU_FUNCTIONAL && l >= 7 && at + 7 <= len) {
                int ts = (cfg[at + 5] & 0xff) | (cfg[at + 6] & 0xff) << 8;
                return ts > 0 ? ts : def;
            }
        }
        return def;
    }

    /** The string index (iInterface) of interface 0's alternate setting alt, or 0. */
    public static int altName(byte[] cfg, int len, int alt) {
        for (int at = 0; at + 1 < len && (cfg[at] & 0xff) > 0; at += cfg[at] & 0xff) {
            int l = cfg[at] & 0xff;
            if ((cfg[at + 1] & 0xff) == TYPE_INTERFACE && l >= 9 && at + 9 <= len
                    && (cfg[at + 2] & 0xff) == 0 && (cfg[at + 3] & 0xff) == alt) return cfg[at + 8] & 0xff;
        }
        return 0;
    }

    /** A string descriptor's text (UTF-16LE after its two-byte header). */
    public static String string(byte[] d, int len) {
        int l = Math.min(len, d.length > 0 ? d[0] & 0xff : 0);
        if (l < 2) return "";
        return new String(d, 2, l - 2, StandardCharsets.UTF_16LE);
    }
}
