package de.droiddrone.pc.route;

import de.droiddrone.common.MissionBlock;
import de.droiddrone.common.MissionItem;
import de.droiddrone.pc.FcTelemetry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Fleet PRD RT-8: a planned route to the drone - one block to the phone (MissionBlock), sent again every second until
 * the phone names it in its status, then the phone's own upload and read-back over USB followed to the end. The
 * result says done (read back item by item) or why not. A thread of its own; never Swing's or the link's.
 */
public final class RouteSender {
    /** The whole upload, read-back included, must be over by then. */
    static final long FINISH_MS = 30_000;
    private static final AtomicInteger NEXT = new AtomicInteger((int) (System.nanoTime() & 0x7fff));

    public record Result(boolean done, String text) {
    }

    private RouteSender() {
    }

    /**
     * @param send     sends the packet (ControlLink.sendMissionBlock); false: not sent now
     * @param status   the phone's newest status (FcTelemetry.getRouteUpload)
     * @param progress percent as it goes (may be null)
     */
    public static Thread send(List<MissionItem> items, Predicate<byte[]> send, Supplier<FcTelemetry.RouteUpload> status,
                              Consumer<Integer> progress, Consumer<Result> done) {
        return send(de.droiddrone.common.MissionUpload.TYPE_MISSION, items, send, status, progress, done);
    }

    /** Fleet AP-9 / AC-10: a fence (type 1) or rally points (type 2) the same way. */
    public static Thread send(int missionType, List<MissionItem> items, Predicate<byte[]> send, Supplier<FcTelemetry.RouteUpload> status,
                              Consumer<Integer> progress, Consumer<Result> done) {
        int block = NEXT.incrementAndGet() & 0xffff;
        byte[] packet = MissionBlock.packet(block, missionType, items);
        Thread t = new Thread(() -> done.accept(run(block, packet, send, status, progress)), "routeSender");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static Result run(int block, byte[] packet, Predicate<byte[]> send, Supplier<FcTelemetry.RouteUpload> status, Consumer<Integer> progress) {
        long start = de.droiddrone.pc.MonoClock.ms();
        int sends = 0;
        boolean heard = false;
        int lastPercent = -1;
        try {
            while (de.droiddrone.pc.MonoClock.ms() - start < FINISH_MS) {
                FcTelemetry.RouteUpload s = status.get();
                boolean ours = s != null && s.block() == block;
                if (ours) {
                    heard = true;
                    if (progress != null && s.percent() != lastPercent) progress.accept(lastPercent = s.percent());
                    if (s.done()) return new Result(true, (packet[3] == 1 ? "Fence" : packet[3] == 2 ? "Rally points" : "Route") + " on the drone, read back: " + itemsOf(packet) + " points.");
                    if (s.failed()) return new Result(false, "Route upload failed: " + (s.reason().isEmpty() ? "no reason given" : s.reason()));
                }
                if (!heard) {
                    if (sends >= MissionBlock.MAX_SENDS) return new Result(false, "The phone didn't answer (phone app pc.58 or newer, an ArduPilot drone).");
                    if (send.test(packet)) sends++;
                }
                Thread.sleep(heard ? 200 : MissionBlock.RESEND_MS);
            }
            return new Result(false, heard ? "The upload didn't finish in 30 s." : "The phone didn't answer.");
        } catch (InterruptedException e) {
            return new Result(false, "Stopped.");
        }
    }

    private static int itemsOf(byte[] packet) {
        return packet.length >= 6 ? ((packet[4] & 0xff) << 8 | (packet[5] & 0xff)) : 0; // after [0x33][block u16][type u8]
    }
}
