package com.ced.sharplan;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded HTTP/GENA framing; no vendor actions are generated here. */
public final class GenaProtocol {
    private GenaProtocol() {}
    public static class Message {
        public String start, rawHeaders;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        public int status() throws IOException {
            if (!start.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))
                throw new IOException("Invalid HTTP status.");
            return Integer.parseInt(start.substring(9, 12));
        }
    }

    public static String sid(String value) throws IOException {
        if (value == null || !value.matches("uuid:[A-Za-z0-9-]{1,128}"))
            throw new IOException("Invalid subscription SID.");
        return value;
    }

    private static String target(URL event) throws IOException {
        LanPolicy.endpoint(null, event.toString(), event.getHost());
        if (!"http".equals(event.getProtocol()))
            throw new IOException("This diagnostic GENA transport supports HTTP event URLs only.");
        String target = event.getFile().isEmpty() ? "/" : event.getFile();
        if (target.chars().anyMatch(Character::isWhitespace) || event.getRef() != null)
            throw new IOException("Invalid event request target.");
        return target;
    }

    public static String subscribe(URL event, URL callback) throws IOException {
        target(event);
        if (!"http".equals(callback.getProtocol())
                || !LanPolicy.privateAddress(callback.getHost()) || callback.getUserInfo() != null
                || java.util.regex.Pattern.compile("[\\r\\n<>]").matcher(callback.toString()).find())
            throw new IOException("Invalid local callback.");
        return "SUBSCRIBE " + target(event) + " HTTP/1.1\r\nHOST: " + event.getHost()
                + ":" + AwoxResearch.port(event) + "\r\nCALLBACK: <" + callback + ">\r\n"
                + "NT: upnp:event\r\nTIMEOUT: Second-45\r\nConnection: close\r\n\r\n";
    }

    public static String unsubscribe(URL event, String id) throws IOException {
        return "UNSUBSCRIBE " + target(event) + " HTTP/1.1\r\nHOST: " + event.getHost()
                + ":" + AwoxResearch.port(event) + "\r\nSID: " + sid(id)
                + "\r\nConnection: close\r\n\r\n";
    }

    static String line(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < maximum) {
            int b = in.read();
            if (b < 0) throw new EOFException("Truncated HTTP line.");
            if (b == '\n') {
                byte[] bytes = out.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            out.write(b);
        }
        throw new IOException("HTTP line exceeds bound.");
    }

    public static Message read(InputStream in, int bodyLimit) throws IOException {
        Message result = new Message();
        result.start = line(in, 4096);
        StringBuilder raw = new StringBuilder(result.start).append("\r\n");
        int count = 0;
        while (true) {
            String line = line(in, 4096);
            raw.append(line).append("\r\n");
            if (raw.length() > 16384 || count++ > 64) throw new IOException("HTTP headers exceed bound.");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1 || Character.isWhitespace(line.charAt(0)))
                throw new IOException("Malformed HTTP header.");
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9!#$%&'*+.^_|~-]+") || result.headers.containsKey(key))
                throw new IOException("Invalid/duplicate HTTP header.");
            result.headers.put(key, line.substring(colon + 1).trim());
        }
        result.rawHeaders = raw.toString();
        String length = result.headers.get("content-length");
        String encoding = result.headers.get("transfer-encoding");
        if (encoding != null && length != null) throw new IOException("Ambiguous HTTP body framing.");
        if (encoding != null) {
            if (!"chunked".equalsIgnoreCase(encoding)) throw new IOException("Unsupported transfer encoding.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int chunks = 0;
            while (true) {
                String sizeText = line(in, 128).split(";", 2)[0];
                if (!sizeText.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid chunk size.");
                long size = Long.parseLong(sizeText, 16);
                if (++chunks > 4096 || size > bodyLimit - bytes.size())
                    throw new IOException("Chunked event body exceeds bound.");
                if (size == 0) {
                    int trailerBytes = 0;
                    while (true) {
                        String trailer = line(in, 4096);
                        trailerBytes += trailer.length();
                        if (trailerBytes > 8192) throw new IOException("Trailers exceed bound.");
                        if (trailer.isEmpty()) break;
                    }
                    break;
                }
                bytes.write(exact(in, (int) size));
                if (!line(in, 2).isEmpty()) throw new IOException("Invalid chunk ending.");
            }
            result.body = bytes.toByteArray();
        } else if (length != null) {
            if (!length.matches("[0-9]{1,7}")) throw new IOException("Invalid content length.");
            int size = Integer.parseInt(length);
            if (size > bodyLimit) throw new IOException("HTTP body exceeds bound.");
            result.body = exact(in, size);
        } else if (result.start.startsWith("NOTIFY ")) {
            throw new IOException("NOTIFY has no bounded body framing.");
        }
        return result;
    }

    private static byte[] exact(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("Truncated HTTP body.");
            offset += count;
        }
        return result;
    }

    public static void validateNotify(Message message, String path, String subscription) throws IOException {
        if (!message.start.equals("NOTIFY " + path + " HTTP/1.1")
                && !message.start.equals("NOTIFY " + path + " HTTP/1.0"))
            throw new IOException("Unexpected callback method/path.");
        if (!"upnp:event".equals(message.headers.get("nt"))
                || !"upnp:propchange".equals(message.headers.get("nts")))
            throw new IOException("Unexpected event notification headers.");
        String received = sid(message.headers.get("sid"));
        if (subscription != null && !received.equals(subscription))
            throw new IOException("Event SID does not match subscription.");
        String sequence = message.headers.get("seq");
        if (sequence == null || !sequence.matches("[0-9]{1,10}")
                || Long.parseLong(sequence) > 4294967295L)
            throw new IOException("Invalid event sequence.");
    }
}
