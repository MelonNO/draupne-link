package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SetupTest {
    @TempDir
    Path dir;

    @Test
    void parsesSignedOutStatus() {
        String json = """
                {"Version":"1.104.2","BackendState":"NeedsLogin","AuthURL":"https://login.tailscale.com/a/abc",
                 "TailscaleIPs":null,"Self":{"HostName":"DESKTOP-1","OS":"windows","TailscaleIPs":null,"Online":false},
                 "Peer":null}""";
        Tailscale.Status s = Tailscale.parseStatus(json);
        assertTrue(s.installed());
        assertFalse(s.signedIn());
        assertEquals("https://login.tailscale.com/a/abc", s.authUrl());
        assertTrue(s.peers().isEmpty());
    }

    @Test
    void parsesPeersAndDirectness() {
        String json = """
                Warning: something first
                {"BackendState":"Running","AuthURL":"",
                 "Self":{"HostName":"DESKTOP-1","OS":"windows","TailscaleIPs":["100.64.0.10","fd7a:115c::1"]},
                 "Peer":{
                   "nodekey:a":{"HostName":"pixel-7","OS":"android","Online":true,"TailscaleIPs":["100.88.0.5","fd7a::5"],"CurAddr":"","Relay":"fra"},
                   "nodekey:b":{"HostName":"laptop","OS":"windows","Online":false,"TailscaleIPs":["100.88.0.6"],"CurAddr":"192.0.2.1:41641","Relay":"fra"}}}""";
        Tailscale.Status s = Tailscale.parseStatus(json);
        assertTrue(s.signedIn());
        assertEquals("100.64.0.10", s.selfIp());
        assertEquals(1, s.androidPeers().size());
        Tailscale.Peer phone = s.androidPeers().get(0);
        assertEquals("100.88.0.5", phone.ip());
        assertTrue(phone.online());
        assertFalse(phone.isDirect());

        assertEquals(Tailscale.PhoneState.ONLINE, Tailscale.phoneState(s, "100.88.0.5"));
        assertEquals(Tailscale.PhoneState.OFFLINE, Tailscale.phoneState(s, "100.88.0.6"), "a known device that is off Tailscale");
        assertEquals(Tailscale.PhoneState.NOT_FOUND, Tailscale.phoneState(s, "100.88.0.99"), "no such device");
    }

    @Test
    void phoneStateWhenThisPcIsOffTailscale() {
        Tailscale.Status signedOut = Tailscale.parseStatus("{\"BackendState\":\"Stopped\",\"Peer\":null}");
        assertEquals(Tailscale.PhoneState.PC_OFF, Tailscale.phoneState(signedOut, "100.88.0.5"));
        assertEquals(Tailscale.PhoneState.NOT_INSTALLED, Tailscale.phoneState(Tailscale.Status.notInstalled(), "100.88.0.5"));
        assertFalse(Tailscale.PhoneState.ONLINE.isProblem());
        assertTrue(Tailscale.PhoneState.OFFLINE.isProblem());
    }

    @Test
    void pingPrefersDirectPongs() {
        Tailscale.PingResult relayed = Tailscale.parsePing("""
                pong from pixel-7 (100.88.0.5) via DERP(fra) in 88ms
                pong from pixel-7 (100.88.0.5) via DERP(fra) in 71ms
                direct connection not established""");
        assertTrue(relayed.reached());
        assertFalse(relayed.direct());
        assertEquals(71, relayed.bestMs());

        // the shape of real output: relayed first, then direct with jitter
        Tailscale.PingResult direct = Tailscale.parsePing("""
                pong from test-phone (100.64.0.20) via DERP(fra) in 30ms
                pong from test-phone (100.64.0.20) via 198.51.100.23:41641 in 213ms
                pong from test-phone (100.64.0.20) via 198.51.100.23:41641 in 68ms
                pong from test-phone (100.64.0.20) via 198.51.100.23:41641 in 58ms
                pong from test-phone (100.64.0.20) via 198.51.100.23:41641 in 110ms
                pong from test-phone (100.64.0.20) via DERP(fra) in 150ms""");
        assertTrue(direct.direct());
        assertEquals(58, direct.bestMs(), "only direct pongs count once there are any");
        assertEquals(110, direct.medianMs());
        assertEquals("198.51.100.23:41641", direct.via());

        assertFalse(Tailscale.parsePing("timeout waiting for ping reply").reached());
    }

    @Test
    void apkShareOnlyAnswersLocalNetworksAndTailscale() throws Exception {
        assertTrue(ApkShare.isAllowed(InetAddress.getByName("192.168.1.20")));
        assertTrue(ApkShare.isAllowed(InetAddress.getByName("10.0.0.5")));
        assertTrue(ApkShare.isAllowed(InetAddress.getByName("100.88.0.5")));
        assertTrue(ApkShare.isAllowed(InetAddress.getByName("127.0.0.1")));
        assertFalse(ApkShare.isAllowed(InetAddress.getByName("100.200.0.5")), "outside 100.64.0.0/10");
        assertFalse(ApkShare.isAllowed(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void apkShareServesTheFile() throws Exception {
        Path apk = dir.resolve(ApkShare.APK_NAME);
        Files.write(apk, new byte[]{1, 2, 3, 4, 5});
        try (ApkShare share = new ApkShare()) {
            share.start(apk);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + ApkShare.PORT + "/";
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains(ApkShare.APK_NAME));
            HttpResponse<byte[]> file = client.send(HttpRequest.newBuilder(URI.create(base + ApkShare.APK_NAME)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, file.body());
            assertEquals("application/vnd.android.package-archive", file.headers().firstValue("Content-Type").orElse(""));
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "../secret")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test
    void sha256MatchesKnownValue() throws Exception {
        Path f = dir.resolve("abc.txt");
        Files.writeString(f, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkShare.sha256(f));
    }

    @Test
    void powershellEncodedCommandsRoundTrip() {
        Shell.Result r = Shell.powershell(Duration.ofSeconds(30), "Write-Output ('it''s ' + (2+3))");
        assertTrue(r.ok(), r.output());
        assertEquals("it's 5", r.output().trim());
        assertEquals("'it''s'", Shell.psQuote("it's"));
    }
    @Test
    void windowsToolsRunByFullPath() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        assertTrue(Shell.withFullPath(List.of("netsh", "advfirewall")).get(0).toLowerCase().endsWith("\\system32\\netsh.exe"));
        assertTrue(Shell.withFullPath(List.of("powershell.exe")).get(0).toLowerCase().endsWith("\\windowspowershell\\v1.0\\powershell.exe"));
        assertEquals(List.of("C:\\x\\tool.exe", "a"), Shell.withFullPath(List.of("C:\\x\\tool.exe", "a")), "a full path is kept");
        assertEquals(List.of("no-such-tool"), Shell.withFullPath(List.of("no-such-tool")), "unknown names are left to the PATH");
        assertEquals("advfirewall", Shell.withFullPath(List.of("netsh", "advfirewall")).get(1));
    }

    @Test
    void tailscaleComesStraightFromTailscale() {
        String url = Tailscale.msiUrl();
        assertTrue(url.startsWith("https://pkgs.tailscale.com/stable/tailscale-setup-latest-"), url);
        assertTrue(url.endsWith("-amd64.msi") || url.endsWith("-arm64.msi"), url);
    }

    @Test
    void wizardWaitsWhileTheInstallerInstallsTailscale() throws Exception {
        Path log = dir.resolve("setup.log");
        assertFalse(PcPrep.tailscaleInstallPending(log), "no log: nothing pending");
        Files.writeString(log, "2026-10-29T12:00:00  Firewall rules: done\n2026-10-29T12:00:01  Tailscale: install scheduled\n");
        assertTrue(PcPrep.tailscaleInstallPending(log));
        Files.writeString(log, "2026-10-29T12:01:30  Tailscale install: done\n", java.nio.file.StandardOpenOption.APPEND);
        assertFalse(PcPrep.tailscaleInstallPending(log), "finished");
        Files.writeString(log, "2026-10-29T12:00:01  Tailscale: install scheduled\n");
        Files.setLastModifiedTime(log, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(Duration.ofHours(2))));
        assertFalse(PcPrep.tailscaleInstallPending(log), "an old, never-finished task is not waited for");
    }
}
