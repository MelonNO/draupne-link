package de.droiddrone.pc;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A request to the internet with a time limit for the whole of it (1.0.71, the pilot: "the map is stuck loading even
 * though the computer has internet"). {@code HttpRequest}'s own timeout ends when the answer's head has come; an
 * answer that stalls after it - half of it sent, then nothing, as a weak link, a PC moving from Wi-Fi to 4G or a VPN
 * coming up does - held the asking thread for as long as the app ran: the map's two tile loaders, the one thread of
 * the terrain heights. Here the head has the request's timeout and all of it {@code wholeMs}; then it is given up.
 */
public final class Net {
    private Net() {
    }

    /**
     * As {@code client.send}, back within wholeMs.
     * @throws HttpTimeoutException when the whole answer has not come in that time (the request is let go of)
     */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, long wholeMs)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> asked = client.sendAsync(request, handler);
        try {
            return asked.get(wholeMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            asked.cancel(true);
            throw new HttpTimeoutException("no whole answer in " + wholeMs / 1000 + " s");
        } catch (ExecutionException e) {
            Throwable why = e.getCause();
            if (why instanceof IOException io) throw io;
            if (why instanceof RuntimeException r) throw r;
            throw new IOException(why);
        } catch (InterruptedException e) {
            asked.cancel(true);
            throw e;
        }
    }

    /**
     * As {@code client.send}, for a download that may take as long as it takes while it keeps coming: the head has the
     * request's own timeout; once begun, the answer is given up when nothing more of it came for stallMs, and at
     * wholeMs at the latest. With one limit for the whole of it ({@link #send}) a slow link that works never got a
     * big file through - the phone app's 23 MB in 15 minutes need 25 kB a second - and every new try began at nothing
     * (a review of 2026-11-20).
     * @throws HttpTimeoutException when it stood still or took longer than wholeMs (the request is let go of)
     */
    public static <T> HttpResponse<T> sendMoving(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler, long stallMs, long wholeMs)
            throws IOException, InterruptedException {
        java.util.concurrent.atomic.AtomicLong movedAt = new java.util.concurrent.atomic.AtomicLong(NOT_BEGUN);
        CompletableFuture<HttpResponse<T>> asked = client.sendAsync(request, info -> moving(handler.apply(info), movedAt));
        long begun = System.nanoTime();
        try {
            while (true) {
                long now = System.nanoTime(), moved = movedAt.get();
                long wholeLeft = wholeMs - (now - begun) / 1_000_000;
                long stallLeft = moved == NOT_BEGUN ? Long.MAX_VALUE : stallMs - (now - moved) / 1_000_000;
                if (wholeLeft <= 0 || stallLeft <= 0) {
                    asked.cancel(true);
                    throw new HttpTimeoutException(stallLeft <= 0 ? "the download stood still for " + stallMs / 1000 + " s" : "no whole answer in " + wholeMs / 1000 + " s");
                }
                try {
                    return asked.get(Math.min(200, Math.min(wholeLeft, stallLeft)), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // not yet: looked at again
                }
            }
        } catch (ExecutionException e) {
            Throwable why = e.getCause();
            if (why instanceof IOException io) throw io;
            if (why instanceof RuntimeException r) throw r;
            throw new IOException(why);
        } catch (InterruptedException e) {
            asked.cancel(true);
            throw e;
        }
    }

    private static final long NOT_BEGUN = Long.MIN_VALUE;

    /** A body's subscriber that writes down, by System.nanoTime, when the head came and when each piece of the body did. */
    private static <T> HttpResponse.BodySubscriber<T> moving(HttpResponse.BodySubscriber<T> to, java.util.concurrent.atomic.AtomicLong movedAt) {
        movedAt.set(System.nanoTime()); // the head is in
        return new HttpResponse.BodySubscriber<>() {
            @Override
            public java.util.concurrent.CompletionStage<T> getBody() {
                return to.getBody();
            }

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                to.onSubscribe(subscription);
            }

            @Override
            public void onNext(java.util.List<java.nio.ByteBuffer> piece) {
                movedAt.set(System.nanoTime());
                to.onNext(piece);
            }

            @Override
            public void onError(Throwable why) {
                to.onError(why);
            }

            @Override
            public void onComplete() {
                to.onComplete();
            }
        };
    }

    private static final java.util.concurrent.ScheduledExecutorService WATCH = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "netStallWatch");
        t.setDaemon(true);
        return t;
    });

    /**
     * A download read piece by piece that may take as long as it takes, but not stand still: when a read gets nothing
     * for stallMs the stream is closed and the read fails with "the download stalled" - it waited for ever, the
     * window saying "Downloading" with it.
     */
    public static java.io.InputStream noStall(java.io.InputStream in, long stallMs) {
        return new java.io.FilterInputStream(in) {
            private volatile boolean stalled;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                java.util.concurrent.ScheduledFuture<?> watch = WATCH.schedule(() -> {
                    stalled = true;
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }, stallMs, TimeUnit.MILLISECONDS);
                try {
                    int n = super.read(b, off, len);
                    if (stalled) throw new IOException("the download stalled: nothing came for " + stallMs / 1000 + " s");
                    return n;
                } catch (IOException e) {
                    if (stalled) throw new IOException("the download stalled: nothing came for " + stallMs / 1000 + " s");
                    throw e;
                } finally {
                    watch.cancel(false);
                }
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n <= 0 ? -1 : one[0] & 0xff;
            }
        };
    }
}
