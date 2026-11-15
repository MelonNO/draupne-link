package de.droiddrone.pc.video;

import de.droiddrone.pc.AppPaths;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.68: the STANAG streams' encoders in a process of their own (the app crashed in the native encoder on 2026-11-14):
 * pictures through it decode as from the encoder in this process; its end - a crash - is an IOException here, said
 * once, and the next encoder starts it again; it ends with the app; only the app can use it.
 */
public class EncoderProcessTest {
    private static final int W = 1280, H = 720;

    private static int[] plain(int rgb) {
        int[] p = new int[W * H];
        Arrays.fill(p, rgb);
        return p;
    }

    private static int[] middle(VideoDecoder dec) {
        int[] out = new int[W * H];
        dec.convert(out, W, H);
        int rgb = out[H / 2 * W + W / 2];
        return new int[]{(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff};
    }

    private static Path runIn;

    /**
     * Where the test's encoder processes run: a folder of its own, not JUnit's temporary one - Windows may hold the
     * process's log a moment after its end (a virus scan of a new file), and JUnit doesn't try again to delete.
     */
    public static synchronized Path runIn() throws IOException {
        if (runIn == null) {
            runIn = java.nio.file.Files.createTempDirectory("draupne-encoder-test");
            runIn.toFile().deleteOnExit();
        }
        return runIn;
    }

    /** Closed, and gone. */
    public static void end(EncoderProcess process) throws InterruptedException {
        Process p = process.process();
        process.close();
        if (p != null) p.waitFor(10, TimeUnit.SECONDS);
    }

    @Test
    void picturesGoThroughTheProcessAndDecodeAsFromTheEncoderHere() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        EncoderProcess process = new EncoderProcess(AppPaths::appCommand, runIn(), log::add);
        try (PictureEncoder enc = process.open(W, H, 5, 200_000, 5 * 3600)) {
            assertTrue(enc.config().length > 0, "the codec config");
            VideoDecoder dec = VideoDecoder.oneThread(false, enc.config());
            List<H264Encoder.Packet> first = enc.encode(plain(0x2060c0), true);
            assertEquals(1, first.size());
            assertTrue(first.get(0).keyFrame());
            assertTrue(dec.decode(first.get(0).data(), first.get(0).data().length));
            for (int i = 0; i < 10; i++) {
                List<H264Encoder.Packet> p = enc.encodeAgain(false);
                assertEquals(1, p.size());
                assertFalse(p.get(0).keyFrame());
                assertTrue(dec.decode(p.get(0).data(), p.get(0).data().length));
            }
            int[] rgb = middle(dec);
            assertEquals(0x20, rgb[0], 12, () -> "colours as sent: " + Arrays.toString(rgb));
            assertEquals(0x60, rgb[1], 12, () -> "colours as sent: " + Arrays.toString(rgb));
            assertEquals(0xc0, rgb[2], 12, () -> "colours as sent: " + Arrays.toString(rgb));
            assertTrue(enc.encodeAgain(true).get(0).keyFrame(), "a key frame when asked");
            // a second encoder at once: a connection of its own, the same process
            try (PictureEncoder other = process.open(W, H, 8, 800_000, 8 * 3600)) {
                assertFalse(other.encode(plain(0xffffff), true).isEmpty());
            }
            assertEquals(1, process.starts());
            dec.close();
        } finally {
            end(process);
        }
        assertTrue(log.isEmpty(), "nothing said while it simply runs: " + log);
    }

    @Test
    void aCrashEndsTheProcessNotTheAppAndTheNextEncoderStartsItAgain() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        EncoderProcess process = new EncoderProcess(AppPaths::appCommand, runIn(), log::add);
        try {
            PictureEncoder enc = process.open(W, H, 5, 200_000, 5 * 3600);
            assertFalse(enc.encode(plain(0x000000), true).isEmpty());
            Process first = process.process();
            first.destroyForcibly(); // as a crash in the native encoder: the process is gone at once
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            IOException e = assertThrows(IOException.class, () -> enc.encodeAgain(false));
            assertInstanceOf(EncoderProcess.Down.class, e, "the stop is said once by the process, not by each stream");
            enc.close();
            // the next encoder: the process again (a quick death waits a moment first)
            PictureEncoder again = null;
            long until = System.currentTimeMillis() + 15_000;
            while (again == null) {
                try {
                    again = process.open(W, H, 5, 200_000, 5 * 3600);
                } catch (EncoderProcess.Down d) {
                    if (System.currentTimeMillis() > until) throw d;
                    Thread.sleep(200);
                }
            }
            assertFalse(again.encode(plain(0xffffff), true).isEmpty(), "pictures again");
            again.close();
            assertEquals(2, process.starts());
            assertNotSame(first, process.process());
        } finally {
            end(process);
        }
        assertEquals(1, log.stream().filter(l -> l.contains("encoder process stopped")).count(), "said once: " + log);
    }

    @Test
    void theWorkerEndsWithTheAppAndServesNobodyWithoutTheToken() throws Exception {
        byte[] token = new byte[16];
        new java.security.SecureRandom().nextBytes(token);
        try (ServerSocket app = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            app.setSoTimeout(20_000);
            List<String> cmd = new ArrayList<>(AppPaths.appCommand());
            cmd.addAll(List.of("stanag-encoder", Integer.toString(app.getLocalPort()), HexFormat.of().formatHex(token)));
            Process worker = new ProcessBuilder(cmd).directory(runIn().toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                Socket hello = app.accept();
                DataInputStream in = new DataInputStream(hello.getInputStream());
                assertEquals(EncoderProcess.HELLO, in.readInt());
                byte[] got = new byte[16];
                in.readFully(got);
                assertArrayEquals(token, got);
                int port = in.readInt();
                // a stranger: a wrong token, then nothing comes back and the connection ends
                try (Socket stranger = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    stranger.setSoTimeout(5_000);
                    OutputStream out = stranger.getOutputStream();
                    out.write(new byte[16]);
                    out.write(new byte[20]);
                    out.flush();
                    InputStream back = stranger.getInputStream();
                    assertEquals(-1, back.read(), "nothing for a connection without the token");
                }
                assertTrue(worker.isAlive());
                hello.close(); // the app is gone
                assertTrue(worker.waitFor(10, TimeUnit.SECONDS), "the worker ends with the app");
            } finally {
                worker.destroyForcibly();
            }
        }
    }
}
