package de.droiddrone.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which app an APK installs is read from the APK ({@link ApkPackage}) - the phone rig's guard decides by it whether a
 * real phone's released app would be changed. The APKs here are made up byte by byte in the shape Android's build
 * tools write a manifest (binary XML, its strings as UTF-8 or as UTF-16). Against a manifest the real tools wrote it
 * was tried once by hand (2026-11-21: the Android SDK's own android.jar, which holds one - "android"); a DRAUPNE
 * Flight APK was not at hand, so the first install on a real phone is its first of those.
 */
class PhoneRigApkPackageTest {
    private static final int RES_XML = 0x0003, RES_STRINGS = 0x0001, RES_MAP = 0x0180, NS_START = 0x0100, NS_END = 0x0101, EL_START = 0x0102, EL_END = 0x0103;

    /** An APK (a zip with a binary manifest in it) that installs that package. */
    static Path apk(Path file, String app, boolean utf8) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(new byte[]{'d', 'e', 'x', '\n'});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(manifest("manifest", app, utf8));
            zip.closeEntry();
        }
        return file;
    }

    /** A manifest as the build tools write it: {@code <root xmlns:android=... android:versionCode="2030055" package="app">}. */
    static byte[] manifest(String root, String app, boolean utf8) {
        // (a string of 200 characters among them: its length takes two bytes, as a long package's would)
        String[] strings = {"versionCode", "package", root, "x".repeat(200), app, "android", "http://schemas.android.com/apk/res/android"};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(stringPool(strings, utf8));
        body.writeBytes(chunk(RES_MAP, 8, ints(0x0101021b)));
        body.writeBytes(chunk(NS_START, 16, ints(1, -1, 5, 6)));
        ByteBuffer el = ByteBuffer.allocate(8 + 20 + 2 * 20).order(ByteOrder.LITTLE_ENDIAN);
        el.putInt(2).putInt(-1);                                    // line, no comment
        el.putInt(-1).putInt(2);                                    // no namespace, the element's name
        el.putShort((short) 20).putShort((short) 20).putShort((short) 2).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        el.putInt(6).putInt(0).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) 0x10).putInt(2030055); // android:versionCode, a number
        el.putInt(-1).putInt(1).putInt(4).putShort((short) 8).put((byte) 0).put((byte) 3).putInt(4);          // package, a string
        body.writeBytes(chunk(EL_START, 16, el.array()));
        body.writeBytes(chunk(EL_END, 16, ints(9, -1, -1, 2)));
        body.writeBytes(chunk(NS_END, 16, ints(9, -1, 5, 6)));
        return chunk(RES_XML, 8, body.toByteArray());
    }

    private static byte[] ints(int... values) {
        ByteBuffer b = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) b.putInt(v);
        return b.array();
    }

    /** A chunk: its type, its head's size, its whole size, then what is in it. */
    private static byte[] chunk(int type, int headerSize, byte[] rest) {
        ByteBuffer b = ByteBuffer.allocate(8 + rest.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) type).putShort((short) headerSize).putInt(8 + rest.length).put(rest);
        return b.array();
    }

    private static byte[] stringPool(String[] strings, boolean utf8) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            String s = strings[i];
            if (utf8) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                data.writeBytes(length8(s.length()));
                data.writeBytes(length8(bytes.length));
                data.writeBytes(bytes);
                data.write(0);
            } else {
                ByteBuffer b = ByteBuffer.allocate(2 + 2 * s.length() + 2).order(ByteOrder.LITTLE_ENDIAN);
                b.putShort((short) s.length());
                for (char c : s.toCharArray()) b.putShort((short) c);
                b.putShort((short) 0);
                data.writeBytes(b.array());
            }
        }
        while (data.size() % 4 != 0) data.write(0);
        ByteBuffer rest = ByteBuffer.allocate(20 + 4 * strings.length + data.size()).order(ByteOrder.LITTLE_ENDIAN);
        rest.putInt(strings.length).putInt(0).putInt(utf8 ? 1 << 8 : 0).putInt(28 + 4 * strings.length).putInt(0);
        for (int o : offsets) rest.putInt(o);
        rest.put(data.toByteArray());
        return chunk(RES_STRINGS, 28, rest.array());
    }

    private static byte[] length8(int n) {
        return n < 0x80 ? new byte[]{(byte) n} : new byte[]{(byte) (0x80 | n >> 8), (byte) n};
    }

    @Test
    void thePackageIsReadFromTheManifestWhateverTheFileIsCalled(@TempDir Path dir) throws Exception {
        for (boolean utf8 : new boolean[]{true, false}) {
            assertEquals(BenchControl.PACKAGE, ApkPackage.read(apk(dir.resolve("DRAUPNE-Flight-released-" + utf8 + ".apk"), BenchControl.PACKAGE, utf8)));
            assertEquals(PhoneRig.APP, ApkPackage.read(apk(dir.resolve("bench-" + utf8 + ".apk"), PhoneRig.APP, utf8)));
            String longName = "de.droiddrone." + "a".repeat(180) + ".flight";
            assertEquals(longName, ApkPackage.ofManifest(manifest("manifest", longName, utf8)));
        }
    }

    @Test
    void whatIsNoApkHasNoPackageAndNothingIsThrown(@TempDir Path dir) throws Exception {
        assertNull(ApkPackage.read(dir.resolve("not-there.apk")));
        assertNull(ApkPackage.read(Files.writeString(dir.resolve("text.apk"), "not a zip")));
        Path noManifest = dir.resolve("no-manifest.apk");
        try (OutputStream out = Files.newOutputStream(noManifest); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.closeEntry();
        }
        assertNull(ApkPackage.read(noManifest));
        assertNull(ApkPackage.ofManifest("<manifest package=\"de.droiddrone.flight.pc\"/>".getBytes(StandardCharsets.UTF_8)), "a manifest as text is not what an APK holds");
        assertNull(ApkPackage.ofManifest(manifest("application", PhoneRig.APP, true)), "the first element is not the manifest");
        byte[] whole = manifest("manifest", PhoneRig.APP, true);
        for (int cut : new int[]{0, 4, 9, 40, whole.length / 2, whole.length - 30, whole.length - 1}) {
            assertNull(ApkPackage.ofManifest(Arrays.copyOf(whole, cut)), "cut off after " + cut + " bytes");
        }
        Path cutApk = dir.resolve("cut.apk");
        byte[] zipBytes = Files.readAllBytes(apk(dir.resolve("whole.apk"), PhoneRig.APP, true));
        Files.write(cutApk, Arrays.copyOf(zipBytes, zipBytes.length / 2));
        assertNull(ApkPackage.read(cutApk));
    }
}
