package com.ced.sharplan;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ServiceFingerprintTest {
    static class FakeSocket extends Socket {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final InputStream input;
        FakeSocket(String response) { input = new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public void setSoTimeout(int ms) { }
        @Override public void close() { }
    }
    ServiceFingerprint probe(List<FakeSocket> sockets, List<String> saved) {
        return new ServiceFingerprint(new ServiceFingerprint.Access() {
            int next;
            public Socket open(int p) throws IOException {
                if (next >= sockets.size()) throw new IOException("No further test response");
                return sockets.get(next++);
            }
            public boolean cancelled() { return false; }
        }, (kind, source, bytes, truncated) -> saved.add(kind + " " + source + "\n"
                + new String(bytes, StandardCharsets.UTF_8)));
    }
    @Test public void knownRendererIsNotProbedAgain() {
        List<String> saved = new ArrayList<>();
        String result = probe(Collections.emptyList(), saved).inspect(54871);
        assertTrue(result.contains("not re-probed")); assertTrue(saved.isEmpty());
        assertEquals("DLNA / media only", ServiceFingerprint.category(result));
    }
    @Test public void httpProbeSendsOnlyRootGetAndRetainsRawResponse() {
        FakeSocket passive = new FakeSocket("");
        FakeSocket web = new FakeSocket("HTTP/1.1 200 OK\r\nServer: TV-local\r\nContent-Length: 5\r\n\r\nHello");
        List<String> saved = new ArrayList<>();
        String result = probe(Arrays.asList(passive,web), saved).inspect(8123);
        String request = web.sent.toString(StandardCharsets.UTF_8);
        assertTrue(request.startsWith("GET / HTTP/1.1\r\nHost: 192.168.1.45:8123\r\n"));
        assertFalse(request.contains("POST")); assertFalse(request.contains("Authorization"));
        assertEquals(0, passive.sent.size()); assertTrue(result.contains("Web service"));
        assertTrue(saved.get(0).contains("Server: TV-local"));
    }
    @Test public void binaryAndSshBannersPreventHttpProbes() {
        FakeSocket ssh = new FakeSocket("SSH-2.0-TV\r\n");
        List<String> saved = new ArrayList<>();
        String result = probe(Collections.singletonList(ssh), saved).inspect(2222);
        assertTrue(result.contains("no active application request")); assertEquals(0, ssh.sent.size());
        assertTrue(saved.get(0).contains("SSH-2.0-TV"));
    }
    @Test public void legacySharpPortOnlyReceivesPassiveRead() {
        FakeSocket socket = new FakeSocket("");
        String result = probe(Collections.singletonList(socket), new ArrayList<>()).inspect(10002);
        assertEquals(0, socket.sent.size()); assertTrue(result.contains("No key/query commands"));
    }
    @Test public void websocketPathMustBeExplicitSameOriginWithoutActionsOrQuery() {
        String host = DeepTcpScanner.TARGET;
        assertEquals("/socket", ServiceFingerprint.wsPath("new WebSocket('ws://192.168.1.45:8123/socket')",host,8123,false));
        for (String url : new String[]{"ws://192.168.1.46:8123/socket","ws://evil.test:8123/socket",
                "ws://192.168.1.45:9999/socket","ws://192.168.1.45:8123/socket?key=power",
                "ws://192.168.1.45:8123/restart","ws://user@192.168.1.45:8123/socket",
                "wss://192.168.1.45:8123/socket","ws://192.168.1.45:8123/../action"})
            assertNull(url,ServiceFingerprint.wsPath(url,host,8123,false));
    }
    @Test public void websocketVerificationRequiresCorrectAcceptAndUpgradeHeaders() {
        String valid = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: expected\r\n\r\n";
        assertTrue(ServiceFingerprint.websocketAccepted(valid, "expected"));
        assertFalse(ServiceFingerprint.websocketAccepted(valid, "wrong"));
        assertFalse(ServiceFingerprint.websocketAccepted(valid.replace("101", "200"), "expected"));
        assertFalse(ServiceFingerprint.websocketAccepted(valid.replace("Upgrade: websocket\r\n", ""), "expected"));
    }
    @Test public void successAndRendererResponsesNeverBecomeVerifiedRemoteControl() {
        assertEquals("Web service", ServiceFingerprint.category("HTTP/1.1 200 OK"));
        assertEquals("DLNA / media only", ServiceFingerprint.category("HTTP/1.1 200 OK AwoX media renderer"));
        assertEquals("Possible control service — UNVERIFIED", ServiceFingerprint.category("TV remote control API"));
        for (boolean identified : new boolean[]{true,false}) assertFalse(TvControlGate.canUnlock(false,identified,false));
    }
    @Test public void responseLimitsKeepOriginalBytesAndStopAtBoundary() throws Exception {
        byte[] source = new byte[100000]; Arrays.fill(source, (byte)65);
        assertEquals(65536, ServiceFingerprint.read(new ByteArrayInputStream(source),65536).length);
        byte[] headers = ServiceFingerprint.readHeaders(new ByteArrayInputStream(
                "HTTP/1.1 101 OK\r\nX: y\r\n\r\nDO NOT READ FRAMES".getBytes(StandardCharsets.US_ASCII)),16384);
        assertEquals("HTTP/1.1 101 OK\r\nX: y\r\n\r\n", new String(headers,StandardCharsets.US_ASCII));
    }
}
