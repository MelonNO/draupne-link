package de.droiddrone.pc.video;

import de.droiddrone.pc.WindowsTuning;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The encoder process (1.0.68, "DRAUPNE Link.exe stanag-encoder &lt;port&gt; &lt;token&gt;"): the STANAG streams' H.264
 * encoders run here, not in the app. On 2026-11-14 the native encoder crashed (an access violation in
 * avcodec_send_frame) and took the whole app with it, the drone link too; here a crash ends this process alone and
 * {@link EncoderProcess} starts it again.
 * <p>
 * It connects to the app's port with the token and says its own port ({@link EncoderProcess#HELLO}); that connection
 * stays open, and when it ends - the app closed or died - this process ends. Each encoder is a connection to its port
 * (token first), served by a thread of its own at the lowest priority, in a process below normal priority.
 */
public final class EncoderWorker {
    static final int OK = 0, FAILED = 1;
    static final int OP_ENCODE = 1, OP_AGAIN = 2;

    private EncoderWorker() {
    }

    public static void run(String[] args) throws IOException {
        int appPort = Integer.parseInt(args[1]);
        byte[] token = EncoderProcess.token(args[2]);
        WindowsTuning.belowNormal();
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Socket hello = new Socket();
        hello.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), appPort), 10_000);
        DataOutputStream out = new DataOutputStream(hello.getOutputStream());
        out.writeInt(EncoderProcess.HELLO);
        out.write(token);
        out.writeInt(server.getLocalPort());
        out.flush();
        // the app's end: this one goes too
        Thread watch = new Thread(() -> {
            try (InputStream in = hello.getInputStream()) {
                while (in.read() >= 0) {
                    // nothing is sent this way; a byte would be ignored
                }
            } catch (IOException ignored) {
                // the app is gone
            }
            System.exit(0);
        }, "encoder-app-watch");
        watch.setDaemon(true);
        watch.start();
        while (true) {
            Socket s = server.accept();
            Thread t = new Thread(() -> serve(s, token), "encoder-" + s.getPort());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    /** One encoder, for as long as its connection lasts. */
    static void serve(Socket s, byte[] token) {
        PictureEncoder enc = null;
        try (s) {
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            byte[] got = new byte[token.length];
            in.readFully(got);
            if (!Arrays.equals(got, token)) return; // not the app: nothing said
            int width = in.readInt(), height = in.readInt(), fps = in.readInt(), bitRate = in.readInt(), gop = in.readInt();
            try {
                enc = new H264Encoder(width, height, fps, bitRate, gop);
            } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
                failed(out, e);
                return;
            }
            out.writeInt(OK);
            byte[] config = enc.config();
            out.writeInt(config.length);
            out.write(config);
            out.flush();
            int pixelCount = width * height;
            byte[] raw = new byte[pixelCount * 4];
            int[] pixels = new int[pixelCount];
            ByteBuffer rawBuffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                int op;
                try {
                    op = in.readUnsignedByte();
                } catch (EOFException e) {
                    return; // the app closed this encoder
                }
                boolean key = in.readBoolean();
                List<H264Encoder.Packet> packets;
                try {
                    if (op == OP_ENCODE) {
                        in.readFully(raw);
                        rawBuffer.clear();
                        rawBuffer.asIntBuffer().get(pixels);
                        packets = enc.encode(pixels, key);
                    } else if (op == OP_AGAIN) {
                        packets = enc.encodeAgain(key);
                    } else {
                        return;
                    }
                } catch (IOException | RuntimeException e) {
                    failed(out, e);
                    continue;
                }
                out.writeInt(OK);
                out.writeInt(packets.size());
                for (H264Encoder.Packet p : packets) {
                    out.writeBoolean(p.keyFrame());
                    out.writeInt(p.data().length);
                    out.write(p.data());
                }
                out.flush();
            }
        } catch (IOException ignored) {
            // the app went, or closed this encoder
        } finally {
            if (enc != null) enc.close();
        }
    }

    private static void failed(DataOutputStream out, Throwable e) throws IOException {
        byte[] message = String.valueOf(e.getMessage() == null ? e.toString() : e.getMessage()).getBytes(StandardCharsets.UTF_8);
        out.writeInt(FAILED);
        out.writeInt(message.length);
        out.write(message);
        out.flush();
    }
}
