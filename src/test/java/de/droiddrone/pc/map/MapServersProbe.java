package de.droiddrone.pc.map;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * By hand, on the real internet: {@code gradlew mapProbe}. Asks each of the map's servers for one tile the way the
 * app does - its own tile cache, its own name towards the server, an empty folder so nothing comes from disk - and
 * says what came and how fast. For the day the map loads nothing though the PC has internet: a server that has
 * moved, refuses the app's name or is down shows here in a line, and so does a PC whose own network is at fault
 * (then every line fails). Five small requests; nothing is kept.
 */
public final class MapServersProbe {
    private MapServersProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("draupne-map-probe");
        TileCache cache = new TileCache(dir);
        cache.say = line -> System.out.println("      " + line);
        // Lillehammer at zoom 10: inside every server's map
        int z = 10, x = 541, y = 297;
        int ok = 0;
        System.out.println("One tile from each of the map's servers (zoom " + z + ", " + x + " / " + y + "):");
        for (TileSource source : TileSource.values()) {
            long t0 = System.currentTimeMillis();
            BufferedImage tile = cache.fetchNow(source, z, x, y);
            long ms = System.currentTimeMillis() - t0;
            if (tile != null) ok++;
            System.out.printf("  %-28s %s%n", source.title, tile != null ? "a tile of " + tile.getWidth() + " x " + tile.getHeight() + " in " + ms + " ms"
                    : "NO TILE after " + ms + " ms" + (cache.isOffline() ? " - the server was not reached" : " - the server answered, but not with a tile"));
        }
        cache.end();
        try (java.util.stream.Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
        System.out.println(ok == TileSource.values().length ? "All " + ok + " servers answer."
                : ok == 0 ? "None answers: look at this PC's own internet first." : ok + " of " + TileSource.values().length + " answer.");
    }
}
