package com.ced.sharplan;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Pure Java validation shared by discovery, HTTP and legacy TCP adapters. */
public final class LanPolicy {
    private LanPolicy() {}

    public static Inet4Address address(String input) throws IOException {
        if (input == null || !input.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
            throw new IOException("Use a numeric IPv4 address.");
        String[] parts = input.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int n = Integer.parseInt(parts[i]);
            if (n > 255 || (parts[i].length() > 1 && parts[i].startsWith("0")))
                throw new IOException("Invalid IPv4 address.");
            bytes[i] = (byte) n;
        }
        return (Inet4Address) InetAddress.getByAddress(bytes);
    }

    public static boolean privateAddress(String ip) {
        try {
            byte[] b = address(ip).getAddress();
            int a = b[0] & 255, c = b[1] & 255;
            return a == 10 || (a == 172 && c >= 16 && c <= 31)
                    || (a == 192 && c == 168) || (a == 169 && c == 254);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean sameSubnet(String ip, String local, int bits) {
        try {
            if (!privateAddress(ip) || bits < 1 || bits > 32) return false;
            long candidate = number(address(ip).getAddress());
            long mask = (0xffffffffL << (32 - bits)) & 0xffffffffL;
            long network = number(address(local).getAddress()) & mask;
            if ((candidate & mask) != network) return false;
            long broadcast = network | (~mask & 0xffffffffL);
            return bits >= 31 || (candidate != network && candidate != broadcast);
        } catch (IOException e) {
            return false;
        }
    }

    private static long number(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) value = (value << 8) | (b & 255);
        return value;
    }

    public static Map<String, String> ssdpHeaders(String message) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = message.split("\\r?\\n");
        if (lines.length == 0 || !lines[0].trim().matches("HTTP/1\\.[01] 200(?: .*)?"))
            return result;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) result.putIfAbsent(
                    lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        return result;
    }

    public static URL endpoint(String base, String reference, String ip)
            throws IOException {
        if (!privateAddress(ip)) throw new IOException("Non-private destination.");
        URL url = base == null ? new URL(reference)
                : new URL(new URL(base), reference);
        if ((!url.getProtocol().equals("http") && !url.getProtocol().equals("https"))
                || url.getUserInfo() != null || !url.getHost().equals(ip))
            throw new IOException("Rejected different-host/non-HTTP endpoint: " + url);
        int port = url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
        if (port < 1 || port > 65535) throw new IOException("Invalid endpoint port.");
        return url;
    }

    public static String frame(String operation, String parameter) throws IOException {
        if (!Arrays.asList("POWR", "VOLM", "MUTE", "CHUP", "CHDW").contains(operation)
                || parameter == null || !parameter.matches("[0-9? ]{1,4}"))
            throw new IOException("Rejected legacy command.");
        return operation + String.format(Locale.ROOT, "%-4s", parameter) + "\r";
    }

    public static boolean exactModel(String model) {
        return model != null && model.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "").contains("LC40UI7352E");
    }

    public static boolean sharp(String manufacturer, String model) {
        String maker = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
        return exactModel(model) || maker.contains("sharp")
                || maker.contains("universal media corporation")
                || maker.trim().equals("umc");
    }
}
