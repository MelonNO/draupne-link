package de.droiddrone.pc.observe;

import de.droiddrone.common.Observe;
import de.droiddrone.common.UdpCommon;
import de.droiddrone.pc.ControlLink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An observer link against a made-up phone on this PC: what it asks, what it does with the answers (1.0.48). */
class ObserverLinkTest {
    /** The pipeline's side: what reached it. */
    private static final class Video implements ControlLink.VideoListener {
        final List<String> got = new ArrayList<>();

        @Override
        public synchronized void onVideoConfig(int width, int height, boolean hevc, boolean frontCamera, byte[] codecConfig) {
            got.add("config " + width + "x" + height + (hevc ? " hevc" : " avc") + " " + codecConfig.length);
        }

        @Override
        public synchronized void onVideoFragment(boolean keyFrame, short frameNum, int offset, int frameSize, byte[] buf, int dataOffset, int length) {
            got.add((keyFrame ? "key " : "frame ") + frameNum + " " + length);
        }

        synchronized List<String> got() {
            return new ArrayList<>(got);
        }
    }

    private static Observe.Request receiveRequest(DatagramSocket phone, byte[] buf, DatagramPacket[] from) throws Exception {
        while (true) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            phone.receive(p);
            from[0] = p;
            Observe.Request r = Observe.decodeRequest(p.getData(), p.getLength());
            if (r != null) return r;
            if (p.getData()[0] == Observe.STOP) return null;
        }
    }

    @Test
    void asksForItsPictureAndNeverSticks() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (DatagramSocket phone = new DatagramSocket(0, local)) {
            phone.setSoTimeout(3000);
            Video video = new Video();
            List<String> events = new ArrayList<>();
            ObserverLink link = new ObserverLink("MobileAlpha", local, phone.getLocalPort(), "secret", video, null, -1, events::add);
            link.start();
            byte[] buf = new byte[2048];
            DatagramPacket[] from = new DatagramPacket[1];
            List<Byte> types = new ArrayList<>();
            try {
                Observe.Request r = receiveRequest(phone, buf, from);
                types.add(from[0].getData()[0]);
                assertEquals("secret", r.key);
                assertEquals(Observe.GRID_WIDTH, r.width, "the grid's small picture first");
                assertEquals(Observe.GRID_FPS, r.fps);
                assertTrue(r.audio());
                assertTrue(r.videoConfig(), "no config yet: asked for");
                link.setQuality(new ObserverLink.Quality(1280, 720, 30, 4000));
                r = receiveRequest(phone, buf, from);
                assertEquals(1280, r.width, "selected: the sharp picture asked for at once");
                InetAddress observer = from[0].getAddress();
                int observerPort = from[0].getPort();

                // the phone answers: streaming, then the stream's config and a key frame fragment
                byte[] state = Observe.encodeState(Observe.STREAMING, 1280, 720, 30, 1);
                phone.send(new DatagramPacket(state, state.length, observer, observerPort));
                byte[] config = {UdpCommon.VideoInitialFrame, 0, 0, 0x05, 0x00, 0x02, (byte) 0xD0, 0, 0, 0, 0, 0, 1, 0x67};
                phone.send(new DatagramPacket(config, config.length, observer, observerPort));
                byte[] key = {UdpCommon.KeyFrame, 0, 1, 0, 0, 0, 0, 0, 0, 0, 3, 7, 8, 9};
                phone.send(new DatagramPacket(key, key.length, observer, observerPort));
                for (int i = 0; i < 200 && video.got().size() < 2; i++) Thread.sleep(10);
                assertEquals(List.of("config 1280x720 avc 5", "key 1 3"), video.got());
                assertEquals(Observe.STREAMING, link.state().state);

                // the observer record, in a telemetry packet of its own
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeByte(UdpCommon.TelemetryData);
                Observe.writeRecord(out, new Observe.Position(Observe.POS_VALID, 59.9, 10.7, 50, 1, Double.NaN, 45, 3, 5, 70));
                byte[] t = bytes.toByteArray();
                phone.send(new DatagramPacket(t, t.length, observer, observerPort));
                for (int i = 0; i < 200 && link.telemetry().getObserved() == null; i++) Thread.sleep(10);
                assertEquals(45, link.telemetry().getObserved().position().phoneAzimuthDeg, 0.05);

                // a pilot connected: said once
                byte[] ended = Observe.encodeState(Observe.ENDED_PILOT, 0, 0, 0, 0);
                phone.send(new DatagramPacket(ended, ended.length, observer, observerPort));
                for (int i = 0; i < 200 && events.isEmpty(); i++) Thread.sleep(10);
                assertEquals(1, events.size());
                assertTrue(events.get(0).contains("pilot connected"), events.get(0));
            } finally {
                link.close();
            }
            // the last thing it sends is OBSERVE_STOP; nothing it ever sent was a stick frame or a Connect
            boolean stopped = false;
            try {
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    phone.receive(p);
                    byte b = p.getData()[0];
                    types.add(b);
                    stopped = b == Observe.STOP;
                }
            } catch (java.net.SocketTimeoutException ignored) {
            }
            assertTrue(stopped, "told the phone it left");
            assertFalse(types.contains(UdpCommon.RcFrame));
            assertFalse(types.contains(UdpCommon.Connect));
        }
    }
}
