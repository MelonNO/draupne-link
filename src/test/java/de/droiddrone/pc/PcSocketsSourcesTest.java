package de.droiddrone.pc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-LINK-2, read in the Windows app's sources: every socket it opens for phones and other PCs is bound by the rule
 * ({@link LinkPlace#open}) - never on every address. The only others are the virtual fleet's made-up drones and pilot
 * PC (each on a loopback address of its own), the video encoders' (loopback) and ApkShare's web page (TCP, the public
 * APK only). A new socket anywhere else fails this test until it is looked at.
 */
class PcSocketsSourcesTest {
    private static final Pattern OPENS = Pattern.compile("new\\s+(DatagramSocket|ServerSocket|MulticastSocket|Socket)\\s*\\(|DatagramChannel\\.open"
            + "|ServerSocketChannel\\.open|HttpServer\\.create|new\\s+ImpairedSocket\\s*\\(");

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    /** "File.java: the line" for each code line that opens a socket (comments left out). */
    private static List<String> opening(Path dir) throws Exception {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String l = line.strip();
                    if (l.startsWith("//") || l.startsWith("*") || l.startsWith("/*")) continue;
                    int comment = l.indexOf("//");
                    if (comment >= 0) l = l.substring(0, comment);
                    if (OPENS.matcher(l).find()) found.add(dir.relativize(f).toString().replace('\\', '/') + ": " + l.strip());
                }
            }
        }
        return found;
    }

    @Test
    void everySocketForPhonesAndPcsIsBoundByTheRule() throws Exception {
        List<String> found = opening(root().resolve("src/main/java/de/droiddrone/pc"));
        List<String> rule = new ArrayList<>(), fleet = new ArrayList<>(), loopback = new ArrayList<>(), other = new ArrayList<>();
        for (String f : found) {
            if (f.startsWith("LinkPlace.java: ")) rule.add(f);
            else if (f.startsWith("fleet/")) fleet.add(f);
            else if (f.startsWith("video/Encoder")) loopback.add(f);
            else other.add(f);
        }
        assertEquals(2, rule.size(), "the rule's two binds: " + rule);
        assertTrue(rule.stream().noneMatch(l -> l.matches(".*new DatagramSocket\\(\\s*(port|\\d+)?\\s*\\).*")), "neither on every address: " + rule);
        assertEquals(List.of("setup/ApkShare.java: server = HttpServer.create(new InetSocketAddress(port), 0);"), other,
                "a socket outside the rule (only ApkShare's page, which serves the public APK, is left as it was)");
        // the virtual fleet: each on a loopback address of its own, given with the port
        assertFalse(fleet.isEmpty());
        for (String f : fleet) assertTrue(f.matches(".*(address|bind).*"), "a made-up drone's or pilot PC's socket on its own address: " + f);
        for (String f : loopback) assertTrue(f.contains("getLoopbackAddress()") || f.contains("new Socket()"), "the encoders on loopback: " + f);
    }

    @Test
    void theLinkAndItsNeighboursUseTheRule() throws Exception {
        Path pc = root().resolve("src/main/java/de/droiddrone/pc");
        String link = Files.readString(pc.resolve("ControlLink.java"), StandardCharsets.UTF_8);
        assertTrue(link.contains("PeerSocket.forPeer(remoteIp, localPort, ownTailscale, ControlLink::setUp)"), "the link: bound by its peer");
        String names = Files.readString(pc.resolve("NameService.java"), StandardCharsets.UTF_8);
        assertTrue(names.contains("PeerSocket.onSide(side, port, own,"), "the link port's listeners: one per side");
        assertTrue(names.contains("new SideSockets(own, null)"), "the scan: from each device's side");
        assertTrue(Files.readString(pc.resolve("SpectatorHost.java")).contains("PeerSocket.beside(linkSocket, 0,"), "spectators: beside the link");
        assertTrue(Files.readString(pc.resolve("LinkPlace.java")).contains("strong host model"), "the assumption written where the bind is chosen");
    }
}
