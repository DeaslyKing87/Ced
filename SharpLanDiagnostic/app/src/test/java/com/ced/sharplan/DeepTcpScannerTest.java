package com.ced.sharplan;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class DeepTcpScannerTest {
    @Test public void completeScanCoversEveryPortExactlyOnceWithBoundedConcurrency() throws Exception {
        AtomicIntegerArray visits = new AtomicIntegerArray(65536);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        DeepTcpScanner scan = new DeepTcpScanner("192.168.1.45", true, (port, timeout) -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
            try {
                assertEquals(300, timeout); visits.incrementAndGet(port);
                if (port != 443 && port != 54871) throw new ConnectException("refused");
            } finally { active.decrementAndGet(); }
        }, (done, open, found) -> { }, () -> { });
        scan.run();
        assertEquals(65535, scan.completed()); assertEquals(0, visits.get(0));
        for (int p = 1; p <= 65535; p++) assertEquals("port " + p, 1, visits.get(p));
        assertEquals(Arrays.asList(443,54871), scan.openPorts());
        assertTrue(maximum.get() <= 16); assertTrue(scan.csv().contains("192.168.1.45,65535,connect_rejected"));
    }
    @Test public void rejectsAllUnconfirmedOrDifferentTargets() {
        for (String host : new String[]{"192.168.1.44","192.168.1.46","127.0.0.1","example.com","192.168.1.45:80",""})
            try { new DeepTcpScanner(host, true, (p,t) -> { }, (d,o,n) -> { }); fail(host); }
            catch (IllegalArgumentException expected) { }
        try { new DeepTcpScanner("192.168.1.45", false, (p,t) -> { }, (d,o,n) -> { }); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void cancellationBeforeStartDoesNotConnect() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeepTcpScanner scan = new DeepTcpScanner(DeepTcpScanner.TARGET, true, (p,t) -> calls.incrementAndGet(),
                (d,o,n) -> { }, () -> { });
        scan.cancel(); scan.run(); assertEquals(0, calls.get());
        assertTrue(scan.summary().contains("Cancelled=true")); assertTrue(scan.csv().contains("65535,not_tested"));
    }
    @Test public void midScanCancellationRetainsPartialResultsAndStopsWorkers() throws Exception {
        AtomicReference<DeepTcpScanner> ref = new AtomicReference<>();
        DeepTcpScanner scan = new DeepTcpScanner(DeepTcpScanner.TARGET, true,
                (p,t) -> { if (p >= 50) ref.get().cancel(); }, (d,o,n) -> { }, () -> { });
        ref.set(scan); scan.run();
        assertTrue(scan.cancelled()); assertTrue(scan.completed() < 65535);
        assertFalse(scan.openPorts().isEmpty()); assertTrue(scan.csv().contains("not_tested"));
    }
    @Test public void doesNotLabelTimeoutOrIoErrorAsClosed() throws Exception {
        DeepTcpScanner scan = new DeepTcpScanner(DeepTcpScanner.TARGET, true, (p,t) -> {
            if (p == 3) throw new SocketTimeoutException("timeout");
            if (p == 4) throw new IOException("network lost");
            throw new ConnectException("refused");
        }, (d,o,n) -> { }, () -> { });
        scan.run(); String csv = scan.csv();
        assertTrue(csv.contains(",2,connect_rejected"));
        assertTrue(csv.contains(",3,timeout_or_filtered")); assertTrue(csv.contains(",4,error_or_cancelled"));
    }
    @Test public void productionRateLimiterDoesNotBurst() throws Exception {
        DeepTcpScanner.RateGate gate = new DeepTcpScanner.RateGate(80);
        long start = System.nanoTime(); for (int i = 0; i < 6; i++) gate.acquire();
        assertTrue("six admissions must be spaced", System.nanoTime() - start >= 50_000_000);
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsExcessiveScanRate() { new DeepTcpScanner.RateGate(81); }
}
