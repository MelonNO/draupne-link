package de.droiddrone.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which app an APK installs: the {@code package} of its manifest, read from the file itself. Before anything is
 * installed on a real phone the bench has to know whether it is the bench build or the released app - and the file's
 * name does not say. (An APK's manifest is Android's binary XML: a table of strings, then the elements with their
 * attributes as numbers into it.)
 */
final class ApkPackage {
    private ApkPackage() {
    }

    private static final int XML = 0x0003, STRINGS = 0x0001, START_ELEMENT = 0x0102, UTF8 = 1 << 8;

    /** The package of that APK; null when the file is no APK or its manifest cannot be read. */
    static String read(Path apk) {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            if (manifest == null) return null;
            try (InputStream in = zip.getInputStream(manifest)) {
                return ofManifest(in.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The package attribute of the manifest element in Android's binary XML; null when there is none, or the bytes are no whole manifest. */
    static String ofManifest(byte[] xml) {
        try {
            return parse(xml);
        } catch (RuntimeException e) { // numbers that point outside the file
            return null;
        }
    }

    private static String parse(byte[] xml) {
        ByteBuffer b = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        if (xml.length < 8 || (b.getShort(0) & 0xffff) != XML || b.getInt(4) > xml.length) return null; // (a file cut short says a size it has not)
        String[] strings = null;
        int at = b.getShort(2) & 0xffff;
        while (at + 8 <= xml.length) {
            int type = b.getShort(at) & 0xffff, size = b.getInt(at + 4);
            if (size < 8 || at + size > xml.length) return null;
            if (type == STRINGS) {
                strings = strings(b, at);
            } else if (type == START_ELEMENT && strings != null) {
                // the chunk's head (8), line (4), comment (4); then namespace (4), name (4), where the attributes start (2),
                // an attribute's size (2), their count (2)
                int ext = at + 16;
                String name = string(strings, b.getInt(ext + 4));
                if (!"manifest".equals(name)) return null; // the first element is the manifest, or this is none
                int start = ext + (b.getShort(ext + 8) & 0xffff), each = b.getShort(ext + 10) & 0xffff, count = b.getShort(ext + 12) & 0xffff;
                for (int i = 0; i < count; i++) {
                    int attr = start + i * each; // namespace (4), name (4), the value as written (4), its size (2), 0 (1), type (1), data (4)
                    if (!"package".equals(string(strings, b.getInt(attr + 4)))) continue;
                    String raw = string(strings, b.getInt(attr + 8));
                    if (raw != null) return raw;
                    return (b.get(attr + 15) & 0xff) == 3 ? string(strings, b.getInt(attr + 16)) : null; // type 3: a string
                }
                return null;
            }
            at += size;
        }
        return null;
    }

    private static String string(String[] strings, int index) {
        return index >= 0 && index < strings.length ? strings[index] : null;
    }

    private static String[] strings(ByteBuffer b, int at) {
        int headerSize = b.getShort(at + 2) & 0xffff, count = b.getInt(at + 8), flags = b.getInt(at + 16), stringsStart = b.getInt(at + 20);
        boolean utf8 = (flags & UTF8) != 0;
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            int p = at + stringsStart + b.getInt(at + headerSize + 4 * i);
            if (utf8) {
                p += (b.get(p) & 0x80) != 0 ? 2 : 1; // its length in characters: not needed
                int len = b.get(p++) & 0xff;
                if ((len & 0x80) != 0) len = (len & 0x7f) << 8 | b.get(p++) & 0xff;
                out[i] = new String(b.array(), p, len, StandardCharsets.UTF_8);
            } else {
                int len = b.getShort(p) & 0xffff;
                p += 2;
                if ((len & 0x8000) != 0) {
                    len = (len & 0x7fff) << 16 | b.getShort(p) & 0xffff;
                    p += 2;
                }
                out[i] = new String(b.array(), p, len * 2, StandardCharsets.UTF_16LE);
            }
        }
        return out;
    }
}
