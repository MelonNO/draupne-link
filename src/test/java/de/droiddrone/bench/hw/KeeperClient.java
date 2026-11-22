package de.droiddrone.bench.hw;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rig's end of the flight controller keeper's control channel ({@link FcKeeper}): lines of text on this PC's
 * loopback, one command a line, one line back - "OK ..." or "NO ...".
 * <pre>
 * BEAT              the heartbeat: without one for 2 s an open arm window is closed. Answers with the state.
 * STATE             the state alone: board=up lease=phone armed=0 locked=1 window=0 tripped=- volts=23.90 cell=3.983 ...
 * OPEN n            an arm window of n seconds (60 at most): the arming lock comes off. NO and why when it may not.
 * CLOSE             the window closed, the lock set, motors stopped and read back. OK only when locked and disarmed.
 * SAFE              the same, at any time; OK only when locked, disarmed and every motor at rest.
 * FAULT cut|starve|stall [ms]     FAULT delay ms [for ms]     FAULT garbage phone|board bytes     FAULT clear
 * TRIP why          the rig's own stop: window closed, locked, no window until CLEAR
 * CLEAR             after a trip: windows may open again
 * SCENARIO name     the tap's full frame log from here on in frames-name.log ("-": none)
 * MARK / FIGURES    the stick frames at the board since the mark: frames= seconds= perSecond= maxGapMs= p99GapMs= ...
 * EVENTS [from]     what the keeper said since event number "from": "OK total count", then a line each
 * END               make safe and end: lock, STOP, motors read back, the port let go; then the keeper's process ends
 * </pre>
 * One command at a time; a keeper that does not answer within the time given is an IOException.
 */
public final class KeeperClient implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;

    /** @param timeoutMs how long an answer may take (END waits for a board that restarts: give it a minute) */
    public KeeperClient(int controlPort, int timeoutMs) throws IOException {
        socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), controlPort), Math.min(timeoutMs, 2000));
            socket.setSoTimeout(timeoutMs);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** One command, and the keeper's line for it. */
    public synchronized String ask(String command) throws IOException {
        out.write((command.replace('\n', ' ').replace('\r', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        String answer = in.readLine();
        if (answer == null) throw new IOException("the keeper closed the control channel");
        return answer;
    }

    /** The command must be taken: a "NO ..." is an IOException with the keeper's reason. */
    public String order(String command) throws IOException {
        String answer = ask(command);
        if (!answer.startsWith("OK")) throw new IOException("the keeper refused " + command + ": " + answer);
        return answer;
    }

    /** A heartbeat, and the state it is answered with. */
    public State beat() throws IOException {
        return State.of(order("BEAT"));
    }

    public State state() throws IOException {
        return State.of(order("STATE"));
    }

    /** What the keeper said from event number {@code from} on, each as "KIND epoch-ms text". */
    public synchronized List<String> events(int from) throws IOException {
        String head = ask("EVENTS " + from);
        String[] w = head.split("\\s+");
        if (!head.startsWith("OK") || w.length < 3) throw new IOException("the keeper's events: " + head);
        List<String> lines = new ArrayList<>();
        for (int i = Integer.parseInt(w[2]); i > 0; i--) {
            String line = in.readLine();
            if (line == null) throw new IOException("the keeper closed the control channel");
            lines.add(line);
        }
        return lines;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** The keeper's state line, read: name=value pairs. */
    public record State(Map<String, String> values) {
        static State of(String answer) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String pair : answer.split("\\s+")) {
                int eq = pair.indexOf('=');
                if (eq > 0) m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            return new State(m);
        }

        public String get(String name) {
            return values.getOrDefault(name, "");
        }

        public int number(String name) {
            try {
                return Integer.parseInt(get(name));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        public double real(String name) {
            try {
                return Double.parseDouble(get(name));
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        /** The board is taken: locked, its channel map read. */
        public boolean boardUp() {
            return get("board").equals("up");
        }

        /** Armed as the board last said; null: it has not said. */
        public Boolean armed() {
            return get("armed").equals("1") ? Boolean.TRUE : get("armed").equals("0") ? Boolean.FALSE : null;
        }

        /** The board's word on armed is no older than a second and a half. */
        public boolean fresh() {
            return get("fresh").equals("1");
        }

        public Boolean locked() {
            return get("locked").equals("1") ? Boolean.TRUE : get("locked").equals("0") ? Boolean.FALSE : null;
        }

        /** Milliseconds left of the arm window; 0: none is open. */
        public int windowMs() {
            return Math.max(0, number("window"));
        }

        /** Why the keeper opens no window; null: it has not tripped. */
        public String tripped() {
            String t = get("tripped");
            return t.isEmpty() || t.equals("-") ? null : t.replace('_', ' ');
        }

        /** "phone", "setup" or "none". */
        public String lease() {
            return get("lease");
        }

        /** Why the keeper itself ended the last arm window ("RECEIVER LOST: ...", "STICKS STOPPED: ...", a trip's words); null: it did not. */
        public String lastEnd() {
            String t = get("lastEnd");
            return t.isEmpty() || t.equals("-") ? null : t.replace('_', ' ');
        }

        /**
         * The arming blocks that hold the board beside the keeper's own lock, by Betaflight's names ("RXLOSS+RPMFILTER");
         * null: none, or not known. The board's own word - with its battery out and bidirectional DShot on, RPMFILTER - and
         * no failure of the keeper's.
         */
        public String cannotArm() {
            String t = get("cannotArm");
            return t.isEmpty() || t.equals("-") ? null : t;
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            values.forEach((k, v) -> s.append(s.length() > 0 ? " " : "").append(k).append('=').append(v));
            return s.toString();
        }
    }
}
