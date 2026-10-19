package com.ced.sharplan;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.*;

/** Bounded metadata probes on an already-confirmed, already-open TV port. Never follows redirects. */
public final class ServiceFingerprint {
    public interface Access {
        Socket open(int port) throws IOException;
        boolean cancelled();
    }
    public interface Capture { void save(String kind, String source, byte[] bytes, boolean truncated); }
    static final int LIMIT = 65536;
    final Access access;
    final Capture capture;
    private String tlsEvidence = "";
    public ServiceFingerprint(Access access, Capture capture) { this.access = access; this.capture = capture; }
    public static String category(String evidence) {
        String s = evidence.toLowerCase(Locale.ROOT);
        if (s.contains("awox") || s.contains("mediarenderer") || s.contains("media renderer")
                || s.contains("renderingcontrol")) return "DLNA / media only";
        // An explicit remote-service description is a research candidate, never control verification.
        if (Pattern.compile("(tv.{0,20}remote|remote.{0,20}control|remote.{0,20}key)", Pattern.DOTALL)
                .matcher(s).find()) return "Possible control service — UNVERIFIED";
        if (s.contains("http/") || s.contains("websocket accepted")) return "Web service";
        if (s.contains("tls certificate") || s.contains("tls handshake")) return "TLS service";
        return "Unknown service";
    }
    public static String wsPath(String body, String host, int port, boolean tls) {
        Matcher m = Pattern.compile("(?i)wss?://[^\\s\"'<>]+").matcher(body);
        while (m.find()) try {
            URI u = new URI(m.group());
            boolean secure = "wss".equalsIgnoreCase(u.getScheme());
            int p = u.getPort() < 0 ? (secure ? 443 : 80) : u.getPort();
            String path = u.getRawPath();
            if (host.equals(u.getHost()) && p == port && secure == tls && u.getRawUserInfo() == null
                    && u.getRawQuery() == null && u.getRawFragment() == null && path != null
                    && path.matches("/[A-Za-z0-9_./~-]*") && !path.contains("..")
                    && !path.toLowerCase(Locale.ROOT).matches(".*(reboot|restart|reset|shutdown|delete|set|action|power).*"))
                return path;
        } catch (Exception ignored) { }
        return null;
    }
    public String inspect(int port) {
        if (port == 54871) return "TCP 54871 · DLNA / media only\n"
                + "Known AwoX renderer. v0.3 physical test disproved TV control; not re-probed.";
        tlsEvidence = "";
        StringBuilder out = new StringBuilder("TCP " + port + "\n");
        if (access.cancelled()) return out + "Cancelled before fingerprint.";
        byte[] banner = new byte[0];
        try (Socket s = access.open(port)) {
            s.setSoTimeout(350);
            banner = read(s.getInputStream(), 4096);
            if (banner.length > 0) {
                capture.save("banner", source(port, "tcp", "/"), banner, banner.length == 4096);
                out.append("Passive banner (raw bytes captured): ").append(printable(banner)).append('\n');
            }
        } catch (Exception e) { out.append("Passive read: ").append(error(e)).append('\n'); }
        if (access.cancelled()) return out + "Cancelled.";
        // A non-HTTP greeting is evidence of another protocol: collect it without sending a command.
        if (banner.length > 0 && !new String(banner, StandardCharsets.ISO_8859_1).startsWith("HTTP/")) {
            return out.append("Classification: ").append(category(out.toString()))
                    .append("\nBanner protocol retained; no active application request sent.").toString();
        }
        // Avoid HTTP payloads on well-known non-web ports, including Sharp's unverified legacy TCP port.
        if (nonWebPort(port)) return out + "Unknown/non-web service; connect and passive read only. No key/query commands.";
        boolean tls = port == 443 || port == 8443 || port == 9443;
        if (!tls) {
            try {
                String response = http(port, false, "/", null);
                if (response.startsWith("HTTP/")) {
                    out.append(response).append('\n');
                    inspectWs(port, false, response, out);
                    return out.append("\nClassification: ").append(category(response)).toString();
                }
                out.append("HTTP probe did not produce an HTTP response.\n");
            } catch (Exception e) { out.append("HTTP root probe: ").append(error(e)).append('\n'); }
        }
        if (!access.cancelled()) try {
            String response = http(port, true, "/", null);
            out.append(response).append('\n');
            if (response.startsWith("HTTP/")) inspectWs(port, true, response, out);
        } catch (Exception e) { out.append("TLS/HTTPS probe: ").append(error(e)).append('\n'); }
        return out.append("\n").append(tlsEvidence).append("\nClassification: ").append(category(out.toString() + tlsEvidence)).toString();
    }
    static boolean nonWebPort(int port) {
        return Arrays.asList(21,22,23,25,53,110,111,135,139,143,445,465,515,548,587,
                631,853,990,993,995,1433,1521,1883,2049,3306,3389,5432,5672,5900,
                6379,8009,8883,9100,10002,11211,27017).contains(port);
    }
    private void inspectWs(int port, boolean tls, String response, StringBuilder out) {
        String path = wsPath(response, DeepTcpScanner.TARGET, port, tls);
        if (path == null) return;
        out.append("\nExplicit same-origin WebSocket URL found: ").append(path).append('\n');
        try {
            byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
            String key = Base64.getEncoder().encodeToString(random);
            String reply = http(port, tls, path, key);
            String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            boolean valid = websocketAccepted(reply, expected);
            out.append(valid ? "WebSocket accepted; handshake only, no frames sent."
                    : "WebSocket upgrade not verified.").append('\n');
        } catch (Exception e) { out.append("WebSocket: ").append(error(e)).append('\n'); }
    }
    static boolean websocketAccepted(String response, String expected) {
        String[] lines = response.split("\\r?\\n");
        boolean accept = false, upgrade = false, connection = false;
        for (String line : lines) {
            int colon = line.indexOf(':'); if (colon < 0) continue;
            String name = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Sec-WebSocket-Accept")) accept = value.equals(expected);
            if (name.equalsIgnoreCase("Upgrade")) upgrade = value.equalsIgnoreCase("websocket");
            if (name.equalsIgnoreCase("Connection")) connection =
                    Arrays.asList(value.toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("upgrade");
        }
        return lines[0].matches("HTTP/1\\.[01] 101(?: .*)?") && accept && upgrade && connection;
    }
    private String http(int port, boolean tls, String path, String wsKey) throws Exception {
        if (access.cancelled()) throw new InterruptedIOException("Cancelled");
        String origin = source(port, tls ? "https" : "http", path);
        Socket raw = access.open(port);
        try {
            Socket socket = raw;
            if (tls) socket = tls(raw, port);
            try {
                socket.setSoTimeout(1200);
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + DeepTcpScanner.TARGET + ":" + port
                        + "\r\nUser-Agent: SharpLanDiagnostic/0.4\r\nAccept: */*\r\nAccept-Encoding: identity\r\n"
                        + (wsKey == null ? "Connection: close\r\n" : "Connection: Upgrade\r\nUpgrade: websocket\r\n"
                        + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + wsKey + "\r\n")
                        + "\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] bytes = wsKey == null ? read(socket.getInputStream(), LIMIT)
                        : readHeaders(socket.getInputStream(), 16384);
                capture.save(wsKey == null ? "http-root" : "websocket-handshake", origin, bytes, bytes.length >= LIMIT);
                return new String(bytes, StandardCharsets.UTF_8);
            } finally { if (socket != raw) socket.close(); }
        } finally { raw.close(); }
    }
    private Socket tls(Socket raw, int port) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509TrustManager original = null;
        for (TrustManager manager : tmf.getTrustManagers())
            if (manager instanceof X509TrustManager) original = (X509TrustManager) manager;
        if (original == null) throw new CertificateException("No platform certificate validator");
        final X509TrustManager delegate = original;
        X509TrustManager recording = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                delegate.checkClientTrusted(chain, auth);
            }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                tlsEvidence = "TLS certificate observed; successful HTTPS trust validation is not assumed.";
                StringBuilder info = new StringBuilder("TLS certificate presented. Trust is validated by Android.\n");
                for (X509Certificate cert : chain) try {
                    info.append("Subject: ").append(cert.getSubjectX500Principal())
                            .append("\nIssuer: ").append(cert.getIssuerX500Principal())
                            .append("\nValid: ").append(cert.getNotBefore()).append(" to ").append(cert.getNotAfter())
                            .append("\nSHA-256: ").append(DiagnosticStore.hex(MessageDigest.getInstance("SHA-256")
                                    .digest(cert.getEncoded())))
                            .append("\nSAN: ").append(cert.getSubjectAlternativeNames()).append('\n');
                } catch (Exception e) { info.append(error(e)).append('\n'); }
                capture.save("tls-certificate", source(port, "tls", "/"),
                        info.toString().getBytes(StandardCharsets.UTF_8), false);
                delegate.checkServerTrusted(chain, auth); // Never silently trust self-signed TV certificates.
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{recording}, null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, DeepTcpScanner.TARGET, port, true);
        socket.setSoTimeout(1500);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        try {
            socket.startHandshake();
            capture.save("tls-handshake", source(port, "tls", "/"),
                    ("TLS handshake verified: " + socket.getSession().getProtocol() + " "
                            + socket.getSession().getCipherSuite()).getBytes(StandardCharsets.UTF_8), false);
            return socket;
        } catch (Exception e) { socket.close(); throw e; }
    }
    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        long deadline = System.nanoTime() + 2_500_000_000L;
        while (out.size() < limit && System.nanoTime() < deadline) {
            int n;
            try { n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size())); }
            catch (SocketTimeoutException e) { break; }
            if (n < 0) break;
            if (n > 0) out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static byte[] readHeaders(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int last = 0;
        long deadline = System.nanoTime() + 2_500_000_000L;
        while (out.size() < limit && System.nanoTime() < deadline) {
            int b; try { b = in.read(); } catch (SocketTimeoutException e) { break; }
            if (b < 0) break;
            out.write(b); last = (last << 8) | b;
            if (last == 0x0d0a0d0a) break;
        }
        return out.toByteArray();
    }
    static String printable(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", ".").substring(0, Math.min(value.length(), 512));
    }
    static String error(Exception e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
    static String source(int port, String scheme, String path) {
        return scheme + "://" + DeepTcpScanner.TARGET + ":" + port + path;
    }
}
