package de.droiddrone.pc;

import de.droiddrone.testing.TroubledServer;
import de.droiddrone.testing.TroubledServer.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.71: no request to the internet waits for ever. An answer that stalls after its head - half of it, then nothing
 * - is given up when its time for the whole is over ({@link Net#send}); a long download read piece by piece fails
 * when it stands still ({@link Net#noStall}). Before, each held its thread for as long as the app ran.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NetTest {
    private TroubledServer server;
    private final byte[] body = new byte[200_000];
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void up() throws Exception {
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        server = new TroubledServer(body, "application/octet-stream");
    }

    @AfterEach
    void down() throws Exception {
        server.close();
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create(server.address() + "/file")).timeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void aWholeAnswerComesAsBefore() throws Exception {
        HttpResponse<byte[]> r = Net.send(client, request(), HttpResponse.BodyHandlers.ofByteArray(), 5000);
        assertEquals(200, r.statusCode());
        assertArrayEquals(body, r.body());
    }

    @Test
    void anAnswerThatStopsHalfWayIsGivenUpInTime() {
        server.always(Mode.HALF);
        long t0 = System.currentTimeMillis();
        HttpTimeoutException e = assertThrows(HttpTimeoutException.class, () -> Net.send(client, request(), HttpResponse.BodyHandlers.ofByteArray(), 1500));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 1400 && took < 4000, "given up after its time for the whole, not before and not much later: " + took + " ms");
        assertTrue(e.getMessage().contains("no whole answer"), e.getMessage());
    }

    @Test
    void aServerThatHangsUpIsAnErrorNotAWait() {
        server.always(Mode.HANG_UP);
        assertThrows(IOException.class, () -> Net.send(client, request(), HttpResponse.BodyHandlers.ofByteArray(), 5000));
    }

    @Test
    void aDownloadThatStandsStillFailsAndSaysSo() throws Exception {
        server.always(Mode.HALF);
        HttpResponse<InputStream> r = Net.send(client, request(), HttpResponse.BodyHandlers.ofInputStream(), 5000);
        long t0 = System.currentTimeMillis();
        long got = 0;
        IOException stalled = null;
        try (InputStream in = Net.noStall(r.body(), 1500)) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) got += n;
        } catch (IOException e) {
            stalled = e;
        }
        long took = System.currentTimeMillis() - t0;
        assertNotNull(stalled, "the read ends with an error, not with a file that looks whole (" + got + " bytes)");
        assertTrue(stalled.getMessage().contains("stalled"), stalled.getMessage());
        assertEquals(body.length / 2, got, "what came before it stood still was read");
        assertTrue(took < 6000, "after " + took + " ms");
    }

    /** A slow link that works: the file comes in pieces and takes far longer than a download may stand still. */
    @Test
    void aSlowDownloadThatKeepsComingGetsThroughHoweverLongItTakes() throws Exception {
        server.pieces = 20;
        server.pieceEveryMs = 150; // 3 s for the whole of it, twice the patience for a standstill, never 1.5 s without a piece (500 ms was red once on the CI runner, 2026-11-21)
        server.always(Mode.SLOW);
        HttpResponse<byte[]> r = Net.sendMoving(client, request(), HttpResponse.BodyHandlers.ofByteArray(), 1500, 30_000);
        assertEquals(200, r.statusCode());
        assertArrayEquals(body, r.body());
    }

    @Test
    void aDownloadThatStandsStillIsGivenUpThenNotAtTheEndOfPatience() {
        server.always(Mode.HALF);
        long t0 = System.currentTimeMillis();
        HttpTimeoutException e = assertThrows(HttpTimeoutException.class, () -> Net.sendMoving(client, request(), HttpResponse.BodyHandlers.ofByteArray(), 700, 30_000));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 600 && took < 4000, "given up when it had stood still for its time: " + took + " ms");
        assertTrue(e.getMessage().contains("stood still"), e.getMessage());
    }

    @Test
    void aDownloadThatTricklesWithoutEndIsGivenUpAtLast() {
        server.pieces = 200;
        server.pieceEveryMs = 100; // 20 s for all of it
        server.always(Mode.SLOW);
        long t0 = System.currentTimeMillis();
        HttpTimeoutException e = assertThrows(HttpTimeoutException.class, () -> Net.sendMoving(client, request(), HttpResponse.BodyHandlers.ofByteArray(), 700, 1500));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 1400 && took < 4000, "given up at the end of patience: " + took + " ms");
        assertTrue(e.getMessage().contains("no whole answer"), e.getMessage());
    }

    @Test
    void aDownloadThatKeepsComingIsNotCutShort() throws Exception {
        HttpResponse<InputStream> r = Net.send(client, request(), HttpResponse.BodyHandlers.ofInputStream(), 5000);
        long got = 0;
        try (InputStream in = Net.noStall(r.body(), 1500)) {
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) got += n;
        }
        assertEquals(body.length, got);
    }
}
