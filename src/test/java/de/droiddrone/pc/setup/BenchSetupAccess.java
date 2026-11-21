package de.droiddrone.pc.setup;

/** What the test bench needs from this package and can't reach from its own: how the app reads an answer, never behaviour. */
public final class BenchSetupAccess {
    private BenchSetupAccess() {
    }

    /** What the app makes of Tailscale's answer to "status --json". */
    public static Tailscale.Status tailscaleStatus(String json) {
        return Tailscale.parseStatus(json);
    }
}
