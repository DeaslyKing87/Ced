package com.ced.sharplan;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Connect-only scan. The host, port range, rate, timeout and concurrency cannot be edited in the UI. */
public final class DeepTcpScanner {
    public static final String TARGET = "192.168.1.45";
    public static final int LAST = 65535, WORKERS = 16, TIMEOUT_MS = 300, RATE = 80;
    public interface Connector { void connect(int port, int timeout) throws IOException; }
    public interface Listener { void update(int completed, List<Integer> open, boolean newlyOpen); }
    public interface Gate { void acquire() throws InterruptedException; }
    public static final class RateGate implements Gate {
        private final long interval;
        private long next;
        public RateGate(int perSecond) {
            if (perSecond < 1 || perSecond > RATE) throw new IllegalArgumentException("Rate");
            interval = 1_000_000_000L / perSecond;
        }
        @Override public synchronized void acquire() throws InterruptedException {
            long now = System.nanoTime(), delay = next - now;
            if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
            next = System.nanoTime() + interval; // No catch-up burst after scheduling stalls.
        }
    }
    private final Connector connector;
    private final Listener listener;
    private final Gate rate;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger next = new AtomicInteger(1), completed = new AtomicInteger();
    private final ConcurrentSkipListSet<Integer> open = new ConcurrentSkipListSet<>();
    private final byte[] states = new byte[LAST + 1];
    private volatile ExecutorService workers;
    private volatile boolean finished;
    public DeepTcpScanner(String host, boolean confirmed, Connector connector, Listener listener) {
        this(host, confirmed, connector, listener, new RateGate(RATE));
    }
    DeepTcpScanner(String host, boolean confirmed, Connector connector, Listener listener, Gate rate) {
        if (!TARGET.equals(host) || !confirmed) throw new IllegalArgumentException("Confirm the known TV IP first.");
        this.connector = connector; this.listener = listener; this.rate = rate;
    }
    public void cancel() {
        cancelled.set(true);
        ExecutorService pool = workers;
        if (pool != null) pool.shutdownNow();
    }
    public boolean cancelled() { return cancelled.get(); }
    public int completed() { return completed.get(); }
    public List<Integer> openPorts() { return new ArrayList<>(open); }
    public void run() throws InterruptedException {
        if (finished || workers != null) throw new IllegalStateException("Scan already started.");
        if (cancelled.get()) return;
        workers = Executors.newFixedThreadPool(WORKERS);
        try {
            for (int n = 0; n < WORKERS && !cancelled.get(); n++) try { workers.submit(() -> {
                while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
                    int port = next.getAndIncrement();
                    if (port > LAST) return;
                    try { rate.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    if (cancelled.get()) return;
                    byte state;
                    try { connector.connect(port, TIMEOUT_MS); state = 1; open.add(port); }
                    catch (SocketTimeoutException e) { state = 3; }
                    catch (ConnectException e) { state = 2; }
                    catch (IOException | RuntimeException e) { state = 4; }
                    states[port] = state;
                    int count = completed.incrementAndGet();
                    if (state == 1 || count % 256 == 0 || count == LAST)
                        listener.update(count, openPorts(), state == 1);
                }
            }); } catch (RejectedExecutionException e) {
                if (!cancelled.get()) throw e;
                break;
            }
            workers.shutdown();
            while (!workers.awaitTermination(200, TimeUnit.MILLISECONDS))
                if (cancelled.get()) workers.shutdownNow();
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            finished = true;
            listener.update(completed(), openPorts(), false);
        }
        if (!cancelled.get() && completed() != LAST) throw new IllegalStateException("Incomplete scan; export partial results.");
    }
    public String csv() {
        if (!finished && workers != null && !workers.isTerminated())
            throw new IllegalStateException("Wait for workers before exporting scan.");
        StringBuilder out = new StringBuilder("ip,port,result\n");
        for (int p = 1; p <= LAST; p++) out.append(TARGET).append(',').append(p).append(',')
                .append(switch (states[p]) {
                    case 1 -> "open"; case 2 -> "connect_rejected";
                    case 3 -> "timeout_or_filtered"; case 4 -> "error_or_cancelled";
                    default -> "not_tested";
                }).append('\n');
        return out.toString();
    }
    public String summary() {
        int[] counts = new int[5];
        for (int p = 1; p <= LAST; p++) counts[states[p]]++;
        return "Target=" + TARGET + "\nCompleted=" + completed() + "/" + LAST
                + "\nCancelled=" + cancelled() + "\nOpen=" + openPorts()
                + "\nConnect rejected=" + counts[2] + "\nTimeout/filtered=" + counts[3]
                + "\nErrors/cancelled=" + counts[4] + "\nNot tested=" + counts[0]
                + "\nTimeouts are inconclusive. A connect rejection is not a protocol result."
                + "\nNo TV control commands were sent. No physical controls verified.";
    }
}
