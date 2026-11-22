package de.droiddrone.pc.gui;

import de.droiddrone.pc.Mirror;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.map.Kml;
import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.Pins;
import de.droiddrone.pc.map.TileCache;
import de.droiddrone.pc.observe.TileFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Container;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A spectator's map when the pilot PC's map text can't be read (cut short, not KML at all, nested without end): the
 * state it came in is taken all the same - the drones move on - the last map that could be read stays, the log says
 * so once, and the next text that can be read is shown.
 */
class MirrorMapTextTest {
    /** A window's side, without the window: a map, and the log. */
    private static final class Host implements MirrorController.Host {
        final List<String> log = new ArrayList<>();
        final JPanel home = new JPanel(new BorderLayout());
        final JPanel video = new JPanel();
        final MapView map;

        Host(MapView map) {
            this.map = map;
            home.add(video, BorderLayout.CENTER);
        }

        @Override public void logLine(String text) { log.add(text); }
        @Override public boolean spectatorsAllowed() { return true; }
        @Override public String key() { return "DD"; }
        @Override public int mode() { return Mirror.IDLE; }
        @Override public String phone() { return ""; }
        @Override public List<TileFeed> tileFeeds() { return List.of(); }
        @Override public int selectedTile() { return -1; }
        @Override public int enlargedTile() { return -1; }
        @Override public List<FleetCards.Card> fleetCards() { return null; }
        @Override public Pins pins() { return new Pins(null); }
        @Override public MapGraphics graphics() { return new MapGraphics(null); }
        @Override public Container videoHome() { return home; }
        @Override public JComponent videoView() { return video; }
        @Override public ObservationGrid newGrid(ObservationGrid.Listener listener) { return new ObservationGrid(listener); }
        @Override public MapView miniMap() { return map; }
        @Override public MapWindow mapWindowIfOpened() { return null; }
        @Override public boolean invertPitch() { return false; }
    }

    private static Map<String, byte[]> state(String mapText, MapView.NetDrone... drones) {
        Map<String, byte[]> s = new LinkedHashMap<>();
        s.put(Mirror.MODE, new Mirror.Mode(Mirror.PILOT, "MobileAlpha", List.of(), -1, -1).encode());
        s.put(MirrorCodec.DRONES, MirrorCodec.drones(List.of(drones)));
        s.put(MirrorCodec.MAP, Mirror.text(mapText));
        return s;
    }

    private static MapView.NetDrone drone(String name, double lat) {
        return new MapView.NetDrone(name, lat, 10, "IDLE", MapView.Kind.ACTIVE, false, null);
    }

    private static String map(String... pins) {
        List<Pins.Pin> list = new ArrayList<>();
        for (String p : pins) list.add(new Pins.Pin(p, 59.9, 10.7));
        List<MapGraphics.Item> items = pins.length < 2 ? List.of()
                : List.of(MapGraphics.Item.line("Route", List.of(new MapGraphics.LatLon(59.9, 10.7), new MapGraphics.LatLon(59.91, 10.71)), 0xff0000, 3));
        return Kml.write(items, list, "DRAUPNE Link");
    }

    private static List<String> pinNames(MapView map) {
        return map.allPins().stream().map(Pins.Pin::name).toList();
    }

    @Test
    void aMapTextThatCantBeReadIsLeftOutAndTheNextGoodOneTaken(@TempDir Path dir) throws Exception {
        String good = map("Alpha");
        String[] broken = {
                good.substring(0, good.length() / 2),                                  // cut short
                "not a map at all \u0001\u0002",
                "",
                "<?xml version=\"1.0\"?><!DOCTYPE kml [<!ENTITY a \"aaaa\">]><kml><Document><name>&a;</name></Document></kml>",
                "<kml><Document><Placemark><name>" + "<b>".repeat(50_000) + "x" + "</b>".repeat(50_000)
                        + "</name><Point><coordinates>10.7,59.9,0</coordinates></Point></Placemark></Document></kml>"};
        try (DatagramSocket nobody = new DatagramSocket(0, InetAddress.getLoopbackAddress())) { // a pilot PC that never answers
            Throwable[] failed = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                Host host = new Host(new MapView(new TileCache(dir, "test", false)));
                MirrorController watcher = new MirrorController(host);
                try {
                    watcher.watch(InetAddress.getLoopbackAddress(), nobody.getLocalPort());
                    watcher.apply(state(good, drone("MobileAlpha", 60.0)));
                    assertEquals(List.of("Alpha"), pinNames(host.map));
                    assertEquals(60.0, host.map.positionOf("MobileAlpha")[0]);

                    double lat = 60.0;
                    for (String text : broken) {
                        lat += 0.1;
                        int said = host.log.size();
                        watcher.apply(state(text, drone("MobileAlpha", lat), drone("MobileBravo", 61)));
                        assertEquals(List.of("Alpha"), pinNames(host.map), "the last map that could be read stays");
                        assertEquals(lat, host.map.positionOf("MobileAlpha")[0], "the rest of the state is taken: the drone moved on");
                        assertNotNull(host.map.positionOf("MobileBravo"));
                        assertEquals(said + 1, host.log.size(), "said once: " + host.log);
                        assertTrue(host.log.get(said).length() <= 80, host.log.get(said));
                        // the same text again (the state comes every second): nothing more said
                        watcher.apply(state(text, drone("MobileAlpha", lat), drone("MobileBravo", 61)));
                        assertEquals(said + 1, host.log.size(), "not again for the same text");
                    }

                    watcher.apply(state(map("Alpha", "Bravo"), drone("MobileAlpha", 62)));
                    assertEquals(List.of("Alpha", "Bravo"), pinNames(host.map), "the next map that can be read is shown");
                    assertEquals(1, host.map.graphics().list().size());
                    assertEquals(62.0, host.map.positionOf("MobileAlpha")[0]);
                } catch (Throwable t) {
                    failed[0] = t;
                } finally {
                    watcher.close();
                }
            });
            if (failed[0] instanceof Error e) throw e;
            if (failed[0] instanceof Exception e) throw e;
        }
    }
}
