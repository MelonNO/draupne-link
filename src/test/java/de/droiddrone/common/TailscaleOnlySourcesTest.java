package de.droiddrone.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q-006 (Tailscale only), read in the phone app's sources: every socket the app hears on goes through the rule
 * ({@link TailscaleOnly}). The link's and overwatch's are LinkSockets opened where the rule wants them, with the rule as
 * their gate; nothing else in the app opens a socket that listens (the test rig's flight controller over TCP is the
 * rig's build alone); upstream's one other listener - the MAVLink UDP bridge - is held shut by the service. When the
 * upstream pin moves, this says which new file opens a socket.
 */
class TailscaleOnlySourcesTest {
    private static final Pattern OPENS = Pattern.compile("new\\s+(DatagramSocket|ServerSocket|MulticastSocket|Socket)\\s*\\(|DatagramChannel\\.open|ServerSocketChannel\\.open"
            + "|LocalServerSocket|NsdManager");

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        return root;
    }

    private static String text(Path f) throws Exception {
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    /** Code lines only: comments say in words what the code does. */
    private static List<String> code(Path f) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.startsWith("//") || l.startsWith("*") || l.startsWith("/*")) continue;
            int comment = l.indexOf("//");
            out.add(comment >= 0 ? l.substring(0, comment) : l);
        }
        return out;
    }

    private static List<String> opening(Path dir) throws Exception {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                for (String l : code(f)) if (OPENS.matcher(l).find()) found.add(f.getFileName().toString());
            }
        }
        return found;
    }

    @Test
    void theAppOpensNoSocketButThroughTheRule() throws Exception {
        Path overlay = root().resolve("flight-app/overlay");
        // the app's own sources: the rule's Place opens each socket, nothing else does
        List<String> found = opening(overlay.resolve("Flight/src/main/java"));
        assertEquals(List.of(), found, "a socket opened outside TailscaleOnly.Place.open");
        List<String> common = opening(overlay.resolve("Common/src/main/java"));
        assertEquals(List.of("TailscaleOnly.java", "TailscaleOnly.java", "TailscaleOnly.java"), common,
                "in Common only the rule opens sockets (LinkSocket holds what it is given)");
    }

    @Test
    void theLinkAndOverwatchAreLinkSocketsWithTheRuleAsTheirGate() throws Exception {
        Path flight = root().resolve("flight-app/overlay/Flight/src/main/java/de/droiddrone/flight");
        String udp = text(flight.resolve("Udp.java"));
        assertTrue(udp.contains("socket = new LinkSocket(this::openLinkSocket, tailscale);"), "the link: opened where the rule says, the rule its gate");
        assertTrue(udp.contains("TailscaleOnly.Place at = PhoneTailscale.place();") && udp.contains("tailscale.opened(at);"), "the opener asks the rule and says where it bound");
        assertTrue(udp.contains("if (tailscale.rebindDue(wanted)) {") && udp.contains("link.reopen();"), "the send watch follows the address");
        String overwatch = text(flight.resolve("Overwatch.java"));
        assertTrue(overwatch.contains("new de.droiddrone.common.LinkSocket(this::openWhereTailscaleIs, tailscale)"), "overwatch's socket the same");
        assertTrue(overwatch.contains("followTailscale(); // Q-006"), "and its tick follows the address");
        // the screen asks the same rule
        assertTrue(text(flight.resolve("MainActivity.java")).contains("return PhoneTailscale.up();"), "the screen's Tailscale line is the link's rule");
    }

    @Test
    void upstreamsOtherListenersAreOverlaidOrHeldShut() throws Exception {
        Path root = root();
        Path upstream = root.resolve("upstream/Flight/src/main/java");
        Path overlay = root.resolve("flight-app/overlay/Flight/src/main/java");
        List<String> open = new ArrayList<>();
        for (String name : Set.copyOf(opening(upstream))) {
            boolean overlaid;
            try (Stream<Path> files = Files.walk(overlay)) {
                overlaid = files.anyMatch(p -> p.getFileName().toString().equals(name));
            }
            if (!overlaid) open.add(name);
        }
        assertEquals(List.of("MavlinkUdpBridge.java"), open, "upstream's sockets the overlay does not replace");
        String service = text(overlay.resolve("de/droiddrone/flight/DDService.java"));
        assertTrue(Pattern.compile("new MavlinkUdpBridge\\(MainActivity\\.config, mavlink\\) \\{[\\s\\S]{0,900}?public void initialize\\(\\) \\{\\s*if \\(said\\) return;")
                .matcher(service).find(), "the service's MAVLink bridge never opens its socket");
        assertEquals(1, service.split("new MavlinkUdpBridge\\(", -1).length - 1, "and it is the only one made");
    }
}
