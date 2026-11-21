package de.droiddrone.pc.map;

import java.nio.file.Path;
import java.util.function.Consumer;

/** For the bench's scenarios outside this package: the tile cache as the app has it, and what only tests may reach. */
public final class MapBenchAccess {
    private MapBenchAccess() {
    }

    /** A tile cache as the app's own: its loader threads, the network on, its real time limits; never held back. */
    public static TileCache cache(Path dir, Consumer<String> said) {
        TileCache c = new TileCache(dir, "bench", true, true, () -> false);
        c.say = said;
        return c;
    }

    public static void end(TileCache cache) {
        cache.end();
    }

    /** How many times the cache took a new connection pool because its server did not answer. */
    public static int renewals(TileCache cache) {
        return cache.renewals;
    }
}
