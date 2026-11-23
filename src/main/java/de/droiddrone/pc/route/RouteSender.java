package de.droiddrone.pc.route;

import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionItem;
import de.droiddrone.pc.FcTelemetry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.IntFunction;

/**
 * Fleet PRD RT-8: a planned route to the drone - one block to the phone (MissionBlock), sent again every second until
 * the phone names it in its status, then the phone's own upload and read-back over USB followed to the end. The
 * result says done (read back item by item) or why not. A thread of its own; never Swing's or the link's.
 */
public final class RouteSender {
    /** The whole upload, read-back included, must be over by then. */
    static final long FINISH_MS = 30_000;
    /**
     * Heard, then no new status for this long: the block is sent again (the phone answers a block it has with its
     * status). The phone says an upload's end once - that one record lost, the sender waited the whole 30 s.
     */
    static final long QUIET_MS = 2000;
    private static final AtomicInteger NEXT = new AtomicInteger(randomId());

    /**
     * A block or call id to count on from, new at every start of this PC (1..65535, never 0): the phone answers a block
     * id it already has with its old outcome and a call it carried out in the last minute with its status. Ids that
     * started at 1 (calls) or near the uptime (System.nanoTime) after a restart could meet one the phone still knows.
     */
    public static int randomId() {
        return new java.security.SecureRandom().nextInt(65535) + 1;
    }

    public record Result(boolean done, String text) {
    }

    private RouteSender() {
    }

    /**
     * @param send     sends the packet (ControlLink.sendMissionBlock); false: not sent now
     * @param status   the phone's newest status for a block (FcTelemetry.getRouteUpload(int))
     * @param progress percent as it goes (may be null)
     */
    public static Thread send(List<MissionItem> items, Predicate<byte[]> send, IntFunction<FcTelemetry.RouteUpload> status,
                              Consumer<Integer> progress, Consumer<Result> done) {
        return send(de.droiddrone.common.MissionUpload.TYPE_MISSION, items, send, status, progress, done);
    }

    /** Fleet AP-9 / AC-10: a fence (type 1) or rally points (type 2) the same way. */
    public static Thread send(int missionType, List<MissionItem> items, Predicate<byte[]> send, IntFunction<FcTelemetry.RouteUpload> status,
                              Consumer<Integer> progress, Consumer<Result> done) {
        int block = NEXT.incrementAndGet() & 0xffff;
        List<byte[]> parts = MissionBlock.packets(block, missionType, items);
        Thread t = new Thread(() -> done.accept(run(block, parts, send, status, progress)), "routeSender");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Sends every part of the block each second until the phone's status names the block past RECEIVING (it has them
     * all), then follows the phone's upload; again after QUIET_MS without news. Each part is a datagram of its own under
     * the phone's 1100 bytes (MissionBlock.ITEMS_PER_PART): one datagram for the whole block was cut over ~33 items.
     */
    static Result run(int block, List<byte[]> parts, Predicate<byte[]> send, IntFunction<FcTelemetry.RouteUpload> status, Consumer<Integer> progress) {
        byte[] head = parts.get(0);
        int type = head[3];
        int items = (head[10] & 0xff) << 8 | (head[11] & 0xff); // [0x33][block u16][type][part][parts][crc u32][items u16]
        long start = de.droiddrone.pc.MonoClock.ms();
        int rounds = 0;
        boolean heard = false, receiving = false;
        int lastPercent = -1;
        long lastNews = start, lastSeen = -1, sentAt = Long.MIN_VALUE / 2;
        try {
            while (de.droiddrone.pc.MonoClock.ms() - start < FINISH_MS) {
                long now = de.droiddrone.pc.MonoClock.ms();
                FcTelemetry.RouteUpload s = status.apply(block); // its own block's: another upload's status never hides it
                if (s != null && s.receivedAt() != lastSeen) {
                    lastSeen = s.receivedAt();
                    lastNews = now;
                    heard = true;
                    receiving = s.state() == MissionBlock.RECEIVING;
                    if (!receiving && progress != null && s.percent() != lastPercent) progress.accept(lastPercent = s.percent());
                    if (s.done()) return new Result(true, (type == 1 ? "Fence" : type == 2 ? "Rally points" : "Route") + " on the drone, read back: " + items + " points.");
                    if (s.failed()) return new Result(false, "Route upload failed: " + (s.reason().isEmpty() ? "no reason given" : s.reason()));
                }
                if (!heard || receiving) {
                    if (now - sentAt >= MissionBlock.RESEND_MS) { // every part again: the phone takes each part once
                        if (!heard && rounds >= MissionBlock.MAX_SENDS) return new Result(false, "The phone didn't answer (phone app pc.58 or newer, an ArduPilot drone).");
                        boolean any = false;
                        for (byte[] p : parts) any |= send.test(p);
                        if (any) rounds++;
                        sentAt = now;
                    }
                } else if (now - lastNews >= QUIET_MS) {
                    lastNews = now;
                    send.test(head); // a lost status: the phone answers a block it has with its status
                }
                Thread.sleep(100);
            }
            return new Result(false, heard ? "The upload didn't finish in 30 s." : "The phone didn't answer.");
        } catch (InterruptedException e) {
            return new Result(false, "Stopped.");
        }
    }
}
