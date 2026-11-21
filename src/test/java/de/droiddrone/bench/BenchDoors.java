package de.droiddrone.bench;

import de.droiddrone.pc.Outside;
import de.droiddrone.pc.setup.Shell;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The bench's stand-ins for the app's doors to the outside ({@link Outside}): web addresses go to the made-up web
 * server, no other program is started, no browser and no Explorer window opens - and each thing the app wanted is
 * written down, so a test can say "REPORT opened its folder" or "UPDATE started the installer".
 */
public final class BenchDoors implements Outside.Doors {
    /** One thing the app asked of the outside. kind: url, run, start, browse, open. */
    public record Asked(long atMs, String kind, String what) {
        @Override
        public String toString() {
            return kind + " " + what;
        }
    }

    private final List<Asked> asked = new CopyOnWriteArrayList<>();
    private final FakeWeb web;
    /** What a command the app waits for answers; null = "ran fine, said nothing". */
    private volatile Function<List<String>, Shell.Result> commands = c -> null;

    public BenchDoors(FakeWeb web) {
        this.web = web;
    }

    public List<Asked> asked() {
        return List.copyOf(asked);
    }

    public List<String> asked(String kind) {
        return asked.stream().filter(a -> a.kind().equals(kind)).map(Asked::what).toList();
    }

    public void forget() {
        asked.clear();
    }

    /** A test's own answers to the commands the app runs (tailscale status, netsh ...). */
    public void commands(Function<List<String>, Shell.Result> answers) {
        commands = answers;
    }

    @Override
    public String url(String url) {
        String to = web == null ? url : web.standInFor(url);
        // every tile would fill the list: only the first of each host is written down
        String host = URI.create(url).getHost();
        if (asked.stream().noneMatch(a -> a.kind().equals("url") && a.what().equals(host))) note("url", host);
        return to;
    }

    @Override
    public Shell.Result run(List<String> command) {
        note("run", String.join(" ", command));
        Shell.Result r = commands.apply(command);
        if (r != null) return r;
        String program = command.isEmpty() ? "" : new File(command.get(0)).getName().toLowerCase(Locale.ROOT);
        if (program.startsWith("tailscale")) {
            // a PC with Tailscale installed and signed in, with no other device: the made-up drones are found by
            // their announcement file, not through Tailscale
            if (command.contains("status")) {
                return new Shell.Result(0, "{\"BackendState\":\"Running\",\"Self\":{\"HostName\":\"bench-pc\",\"DNSName\":\"bench-pc.bench.ts.net.\","
                        + "\"TailscaleIPs\":[\"127.0.0.1\"],\"Online\":true},\"Peer\":{}}");
            }
            if (command.contains("ip")) return new Shell.Result(0, "127.0.0.1\n");
        }
        return new Shell.Result(0, "");
    }

    @Override
    public boolean start(List<String> command) {
        note("start", String.join(" ", command));
        return true;
    }

    @Override
    public boolean browse(URI address) {
        note("browse", address.toString());
        return true;
    }

    @Override
    public boolean open(File folderOrFile) {
        note("open", folderOrFile.getAbsolutePath());
        return true;
    }

    private void note(String kind, String what) {
        asked.add(new Asked(System.currentTimeMillis(), kind, what));
    }
}
