package de.droiddrone.pc.setup.dfu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DfuSe's memory layout, from the interface's string: "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg" =
 * four 16 KB sectors, one of 64 KB, seven of 128 KB from 0x08000000; the letter says what may be done (a-g: bit 0
 * readable, bit 1 erasable, bit 2 writable). Several address blocks may follow, each "/0x.../..." again.
 */
public record DfuLayout(String name, List<Sector> sectors) {
    public static final int READABLE = 1, ERASABLE = 2, WRITABLE = 4;

    public record Sector(long address, int size, int flags) {
        public long end() {
            return address + size;
        }

        public boolean can(int flag) {
            return (flags & flag) != 0;
        }
    }

    private static final Pattern BLOCK = Pattern.compile("/\\s*0x([0-9A-Fa-f]+)\\s*/([^/]+)");
    private static final Pattern PART = Pattern.compile("\\s*(\\d+)\\s*\\*\\s*(\\d+)\\s*([ BKMbkm]?)\\s*([a-gA-G])\\s*");

    public static DfuLayout parse(String s) throws IOException {
        if (s == null || !s.startsWith("@")) throw new IOException("not a DfuSe layout: " + s);
        int slash = s.indexOf('/');
        if (slash < 0) throw new IOException("no address in the layout: " + s);
        String name = s.substring(1, slash).trim();
        List<Sector> out = new ArrayList<>();
        Matcher b = BLOCK.matcher(s);
        while (b.find()) {
            long at = Long.parseLong(b.group(1), 16);
            for (String part : b.group(2).split(",")) {
                Matcher p = PART.matcher(part);
                if (!p.matches()) throw new IOException("can't read the layout part \"" + part + "\"");
                int count = Integer.parseInt(p.group(1));
                int unit = switch (p.group(3).toUpperCase(Locale.ROOT)) {
                    case "K" -> 1024;
                    case "M" -> 1024 * 1024;
                    default -> 1;
                };
                int size = Integer.parseInt(p.group(2)) * unit;
                int flags = Character.toLowerCase(p.group(4).charAt(0)) - 'a' + 1;
                for (int i = 0; i < count; i++) {
                    out.add(new Sector(at, size, flags));
                    at += size;
                }
            }
        }
        if (out.isEmpty()) throw new IOException("no sectors in the layout: " + s);
        return new DfuLayout(name, List.copyOf(out));
    }

    /** All its bytes. */
    public long total() {
        long t = 0;
        for (Sector x : sectors) t += x.size();
        return t;
    }

    /** The sector holding this address, or null. */
    public Sector at(long address) {
        for (Sector x : sectors) if (address >= x.address() && address < x.end()) return x;
        return null;
    }

    /** Every byte from..to-1 lies in writable sectors. */
    public boolean writable(long from, long to) {
        long a = from;
        while (a < to) {
            Sector x = at(a);
            if (x == null || !x.can(WRITABLE)) return false;
            a = x.end();
        }
        return true;
    }
}
