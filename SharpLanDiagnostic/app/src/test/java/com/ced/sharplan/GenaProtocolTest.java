package com.ced.sharplan;

import org.junit.Test;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class GenaProtocolTest {
    GenaProtocol.Message parse(String wire) throws Exception {
        return GenaProtocol.read(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), 1024);
    }
    String notification(String id, String path) {
        return "NOTIFY " + path + " HTTP/1.1\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n"
                + "SID: " + id + "\r\nSEQ: 0\r\nContent-Length: 4\r\n\r\n<e/>";
    }

    @Test public void readsInitialNotifyBeforeSubscriptionReply() throws Exception {
        GenaProtocol.Message message = parse(notification("uuid:abc-123", "/notify/token"));
        GenaProtocol.validateNotify(message, "/notify/token", null);
        GenaProtocol.validateNotify(message, "/notify/token", "uuid:abc-123");
        assertEquals("<e/>", new String(message.body, StandardCharsets.UTF_8));
    }

    @Test(expected = IOException.class) public void rejectsWrongSid() throws Exception {
        GenaProtocol.validateNotify(parse(notification("uuid:other", "/notify/token")),
                "/notify/token", "uuid:expected");
    }

    @Test(expected = IOException.class) public void rejectsWrongCallbackToken() throws Exception {
        GenaProtocol.validateNotify(parse(notification("uuid:abc", "/notify/wrong")),
                "/notify/token", "uuid:abc");
    }

    @Test(expected = IOException.class) public void rejectsNonNotifyMethod() throws Exception {
        GenaProtocol.validateNotify(parse(notification("uuid:abc", "/notify/token")
                .replace("NOTIFY ", "POST ")), "/notify/token", "uuid:abc");
    }

    @Test(expected = IOException.class) public void rejectsBadEventSequence() throws Exception {
        GenaProtocol.validateNotify(parse(notification("uuid:abc", "/notify/token")
                .replace("SEQ: 0", "SEQ: 4294967296")), "/notify/token", "uuid:abc");
    }

    @Test(expected = IOException.class) public void rejectsOversizedBody() throws Exception {
        parse("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n");
    }

    @Test(expected = IOException.class) public void rejectsAmbiguousBodyFraming() throws Exception {
        parse("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n");
    }

    @Test(expected = IOException.class) public void rejectsDuplicateContentLength() throws Exception {
        parse("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Length: 4\r\n\r\n<e/>");
    }

    @Test public void readsBoundedChunkedEvent() throws Exception {
        GenaProtocol.Message message = parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4\r\n<e/>\r\n0\r\n\r\n");
        assertEquals("<e/>", new String(message.body, StandardCharsets.UTF_8));
    }

    @Test(expected = IOException.class) public void rejectsTruncatedBody() throws Exception {
        parse("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc");
    }

    @Test public void buildsTemporaryObserverAndCleanupWithoutVendorActions() throws Exception {
        URL event = new URL("http://192.168.1.45:54871/MediaRenderer_X_ServiceManager/event");
        URL callback = new URL("http://192.168.1.5:41234/notify/token");
        String wire = GenaProtocol.subscribe(event, callback);
        assertTrue(wire.startsWith("SUBSCRIBE /MediaRenderer_X_ServiceManager/event HTTP/1.1"));
        assertTrue(wire.contains("TIMEOUT: Second-45"));
        assertTrue(wire.contains("CALLBACK: <http://192.168.1.5:41234/notify/token>"));
        assertFalse(wire.contains("PerformAction"));
        String cancel = GenaProtocol.unsubscribe(event, "uuid:abc-123");
        assertTrue(cancel.startsWith("UNSUBSCRIBE "));
        assertFalse(cancel.contains("CALLBACK"));
    }

    @Test(expected = IOException.class) public void rejectsSidInjection() throws Exception {
        GenaProtocol.sid("uuid:abc\r\nCALLBACK: <http://example.com/>");
    }

    @Test(expected = IOException.class) public void rejectsExternalCallback() throws Exception {
        GenaProtocol.subscribe(new URL("http://192.168.1.45:54871/event"),
                new URL("http://example.com/notify"));
    }
}
