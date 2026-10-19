package com.ced.sharplan;

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One short-lived observer subscription, with a source/IP/token/SID checked callback. */
public final class GenaObserver {
    public interface Sink {
        void log(String text);
        void event(GenaProtocol.Message message);
    }
    private final SocketFactory sockets;
    private final String target, local;
    private final URL eventUrl;
    private final Sink sink;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final BlockingQueue<GenaProtocol.Message> pending = new ArrayBlockingQueue<>(48);
    private volatile ServerSocket server;
    private volatile Socket currentClient;

    public GenaObserver(SocketFactory sockets, String target, String local, URL eventUrl, Sink sink) {
        this.sockets = sockets; this.target = target; this.local = local;
        this.eventUrl = eventUrl; this.sink = sink;
    }

    public void cancel() {
        stopped.set(true);
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (currentClient != null) currentClient.close(); } catch (IOException ignored) { }
    }

    private GenaProtocol.Message exchange(String wire, int timeout) throws Exception {
        sink.log("GENA SEND\n" + wire);
        try (Socket socket = sockets.createSocket()) {
            socket.connect(new InetSocketAddress(LanPolicy.address(target), AwoxResearch.port(eventUrl)), timeout);
            socket.setSoTimeout(timeout);
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            GenaProtocol.Message response = GenaProtocol.read(socket.getInputStream(), 65536);
            sink.log("GENA RECV\n" + response.rawHeaders
                    + new String(response.body, StandardCharsets.UTF_8));
            return response;
        }
    }

    public void observe() throws Exception {
        String subscription = null;
        Thread listener = null;
        String path = "/notify/" + UUID.randomUUID().toString();
        try {
            if (!LanPolicy.privateAddress(local) || !LanPolicy.privateAddress(target))
                throw new IOException("Local private IPv4 addresses required.");
            server = new ServerSocket();
            server.bind(new InetSocketAddress(LanPolicy.address(local), 0), 8);
            server.setSoTimeout(400);
            URL callback = new URL("http", local, server.getLocalPort(), path);
            listener = new Thread(() -> accept(path), "AwoX-GENA-observer");
            listener.setDaemon(true);
            listener.start();
            GenaProtocol.Message response = exchange(GenaProtocol.subscribe(eventUrl, callback), 3500);
            if (response.status() != 200) throw new IOException("GENA SUBSCRIBE HTTP " + response.status());
            subscription = GenaProtocol.sid(response.headers.get("sid"));
            int seconds = 30;
            String lease = response.headers.get("timeout");
            if (lease != null && lease.matches("Second-[0-9]{1,6}")) {
                int granted = Integer.parseInt(lease.substring(7));
                seconds = Math.max(1, Math.min(seconds, granted - 1));
            }
            sink.log("GENA subscription accepted for observation only. "
                    + "No TV key/control is verified. Press buttons on the PHYSICAL TV remote now.");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            int accepted = 0;
            while (!stopped.get() && !Thread.currentThread().isInterrupted()
                    && System.nanoTime() < end && accepted < 32) {
                GenaProtocol.Message notification = pending.poll(300, TimeUnit.MILLISECONDS);
                if (notification == null) continue;
                try {
                    GenaProtocol.validateNotify(notification, path, subscription);
                    sink.event(notification);
                    accepted++;
                } catch (IOException e) { sink.log("Rejected event: " + e.getMessage()); }
            }
            sink.log("GENA observation finished: " + accepted
                    + " validated events. No events does not prove no control interface.");
        } finally {
            // Stop accepting before removing the registration. No renewal or background service.
            cancel();
            if (subscription != null) {
                try { exchange(GenaProtocol.unsubscribe(eventUrl, subscription), 1500); }
                catch (Exception e) { sink.log("GENA cleanup: " + e.getMessage()
                        + ". Requested lease was 45 seconds; no renewal is sent."); }
            }
            if (listener != null) {
                listener.interrupt();
                try { listener.join(1200); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void accept(String path) {
        int attempts = 0;
        while (!stopped.get() && attempts < 64) {
            try (Socket client = server.accept()) {
                currentClient = client;
                attempts++;
                client.setSoTimeout(1800);
                if (!target.equals(client.getInetAddress().getHostAddress())) continue;
                GenaProtocol.Message message = GenaProtocol.read(client.getInputStream(), 262144);
                GenaProtocol.validateNotify(message, path, null);
                boolean queued = pending.offer(message);
                String response = queued ? "HTTP/1.1 200 OK" : "HTTP/1.1 503 Service Unavailable";
                client.getOutputStream().write((response
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (!stopped.get()) sink.log("GENA callback rejected: " + e.getMessage());
            } finally { currentClient = null; }
        }
    }
}
