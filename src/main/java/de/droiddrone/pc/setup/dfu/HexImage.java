package de.droiddrone.pc.setup.dfu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A firmware image as blocks at flash addresses: from an Intel HEX file (ArduPilot's "_with_bl.hex": its bootloader
 * and Copter, from 0x08000000) or a plain .bin at a base address. Touching blocks are joined.
 */
public record HexImage(List<Segment> segments) {
    /** The STM32's flash start: where a .bin goes. */
    public static final long FLASH_BASE = 0x08000000L;

    public record Segment(long address, byte[] data) {
        public long end() {
            return address + data.length;
        }
    }

    public long first() {
        return segments.get(0).address();
    }

    public long end() {
        return segments.get(segments.size() - 1).end();
    }

    public int bytes() {
        int n = 0;
        for (Segment s : segments) n += s.data().length;
        return n;
    }

    /** A .hex (starts with ':') or else a .bin at {@link #FLASH_BASE}. */
    public static HexImage read(byte[] file) throws IOException {
        if (file.length > 0 && file[0] == ':') return parseHex(new String(file, StandardCharsets.US_ASCII));
        if (file.length == 0) throw new IOException("empty file");
        return new HexImage(List.of(new Segment(FLASH_BASE, file.clone())));
    }

    public static HexImage parseHex(String text) throws IOException {
        TreeMap<Long, ByteArrayOutputStream> blocks = new TreeMap<>();
        long upper = 0;
        long nextAt = -1;
        ByteArrayOutputStream cur = null;
        boolean eof = false;
        int lineNo = 0;
        for (String raw : text.split("\\R")) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (eof) break;
            if (line.charAt(0) != ':' || line.length() < 11 || line.length() % 2 == 0) throw new IOException("line " + lineNo + " isn't a HEX record");
            byte[] r = new byte[(line.length() - 1) / 2];
            int sum = 0;
            for (int i = 0; i < r.length; i++) {
                int v;
                try {
                    v = Integer.parseInt(line.substring(1 + 2 * i, 3 + 2 * i), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("line " + lineNo + " isn't hex");
                }
                r[i] = (byte) v;
                sum += v;
            }
            if ((sum & 0xff) != 0) throw new IOException("line " + lineNo + ": checksum wrong");
            int len = r[0] & 0xff;
            if (r.length != len + 5) throw new IOException("line " + lineNo + ": length wrong");
            int off = (r[1] & 0xff) << 8 | (r[2] & 0xff);
            int type = r[3] & 0xff;
            switch (type) {
                case 0 -> {
                    long at = upper + off;
                    if (cur == null || at != nextAt) {
                        cur = blocks.computeIfAbsent(at, k -> new ByteArrayOutputStream());
                        if (cur.size() > 0) throw new IOException("line " + lineNo + ": address written twice");
                    }
                    cur.write(r, 4, len);
                    nextAt = at + len;
                }
                case 1 -> eof = true;
                case 2 -> upper = (long) ((r[4] & 0xff) << 8 | (r[5] & 0xff)) << 4;
                case 4 -> upper = (long) ((r[4] & 0xff) << 8 | (r[5] & 0xff)) << 16;
                case 3, 5 -> {
                    // start address: not needed (the bootloader starts the flash's own vector table)
                }
                default -> throw new IOException("line " + lineNo + ": record type " + type);
            }
        }
        if (!eof) throw new IOException("the HEX file has no end record: cut off?");
        List<Segment> out = new ArrayList<>();
        for (Map.Entry<Long, ByteArrayOutputStream> e : blocks.entrySet()) {
            byte[] d = e.getValue().toByteArray();
            if (d.length == 0) continue;
            Segment last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && e.getKey() < last.end()) throw new IOException("overlapping blocks at 0x" + Long.toHexString(e.getKey()));
            if (last != null && e.getKey() == last.end()) {
                byte[] j = java.util.Arrays.copyOf(last.data(), last.data().length + d.length);
                System.arraycopy(d, 0, j, last.data().length, d.length);
                out.set(out.size() - 1, new Segment(last.address(), j));
            } else {
                out.add(new Segment(e.getKey(), d));
            }
        }
        if (out.isEmpty()) throw new IOException("the HEX file holds no data");
        return new HexImage(List.copyOf(out));
    }
}
