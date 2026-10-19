package com.ced.sharplan;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/** Non-executing inspection policy; textual ID candidates are never commands. */
public final class AwoxResearch {
    public static final String TYPE = "urn:schemas-awox-com:service:X_ServiceManager:1";
    public static final String RENDERER_LABEL = "DLNA renderer controls — not verified TV remote";
    private AwoxResearch() {}

    public static boolean reportedProfile(String name, String manufacturer, String model) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "MStar".equalsIgnoreCase(manufacturer)
                && "MStarDMR".equalsIgnoreCase(model)
                && n.startsWith("sharp_uhd_tv")
                && n.contains("78:dd:d9:48:53:2e");
    }

    public static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    public static int port(URL url) {
        return url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
    }

    public static URL resource(URL origin, URL base, String reference) throws IOException {
        reference = reference.replace("\\/", "/").replace("&amp;", "&").trim();
        if (reference.isEmpty() || reference.length() > 2048 || java.util.regex.Pattern.compile("[\\r\\n\\x00]").matcher(reference).find())
            throw new IOException("Invalid resource reference.");
        URL result = LanPolicy.endpoint(base.toString(), reference, origin.getHost());
        if (!sameOrigin(origin, result)) throw new IOException("Different-origin resource rejected.");
        String path;
        try { path = URLDecoder.decode(result.getPath(), "UTF-8").toLowerCase(Locale.ROOT); }
        catch (Exception e) { throw new IOException("Invalid resource path.", e); }
        if (path.contains("..") || path.matches(".*(?:^|[/_.-])"
                + "(?:reboot|reset|delete|remove|erase|logout|performaction|setproperty|"
                + "execute|apply|save|update|command)(?:[/_.-]|$).*"))
            throw new IOException("Action-like resource path rejected.");
        if (!(path.equals("/web") || path.equals("/web/")
                || path.matches(".*\\.(?:m?js|json|xml|html?|css)$")))
            throw new IOException("Only HTML/JS/JSON/XML/CSS references are inspected.");
        String query = result.getQuery();
        if (query != null) {
            for (String item : query.split("&")) {
                String[] pair = item.split("=", 2);
                if (!pair[0].toLowerCase(Locale.ROOT).matches("v|ver|version|_|cache|lang|locale")
                        || pair.length != 2 || !pair[1].matches("[A-Za-z0-9_.-]{0,80}"))
                    throw new IOException("Unrecognized resource query rejected.");
            }
        }
        try { return new URI(result.toString().split("#", 2)[0]).toURL(); }
        catch (Exception e) { throw new IOException("Invalid resource URI.", e); }
    }

    public static List<URL> references(URL origin, URL base, String body) {
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        Matcher attrs = Pattern.compile("(?is)\\b(?:src|href)\\s*=\\s*[\"']([^\"'<>]{1,2048})[\"']").matcher(body);
        while (attrs.find() && raw.size() < 96) raw.add(attrs.group(1));
        Matcher strings = Pattern.compile(
                "[\"']([^\"'\\r\\n<>]{1,1800}\\.(?:m?js|json|xml|html?|css)"
                        + "(?:\\?[^\"'\\r\\n<>]{0,200})?)[\"']", Pattern.CASE_INSENSITIVE).matcher(body);
        while (strings.find() && raw.size() < 96) raw.add(strings.group(1));
        List<URL> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String candidate : raw) {
            try {
                URL url = resource(origin, base, candidate);
                if (seen.add(url.toString())) result.add(url);
            } catch (IOException ignored) { }
        }
        result.sort(Comparator.comparingInt(u -> u.getPath().matches("(?i).*\\.(?:m?js|json|xml)$") ? 0 : 1));
        return result;
    }

    public static List<String> findings(String source, String body) {
        List<String> findings = new ArrayList<>();
        Pattern id = Pattern.compile(
                "(?i)[\"']?\\b(ServiceID|ActionID|PropertyID|Property)\\b[\"']?\\s*[:=]\\s*"
                        + "[\"']?([^\"'\\s<>,;}{()]{1,128})");
        Matcher ids = id.matcher(body);
        while (ids.find() && findings.size() < 64)
            findings.add("UNVERIFIED ID CANDIDATE " + source + " offset=" + ids.start()
                    + " " + ids.group(1) + "=" + ids.group(2));
        Matcher xmlIds = Pattern.compile(
                "(?i)<(?:[\\w.-]+:)?(ServiceID|ActionID|PropertyID|Property)\\b[^>]*>"
                        + "\\s*([^<]{1,128})<").matcher(body);
        while (xmlIds.find() && findings.size() < 80)
            findings.add("UNVERIFIED XML ID " + source + " offset=" + xmlIds.start()
                    + " " + xmlIds.group(1) + "=" + xmlIds.group(2).trim());
        Matcher terms = Pattern.compile("(?i)\\b(X_ServiceManager|ServiceManagerInfo|ServiceID|ActionID|"
                + "PropertyID|PerformAction|SetProperty|GetProperty|remote|key|keycode|button|power|"
                + "volume|mute|input|source|channel|menu|home|back|up|down|left|right|enter|ok)\\b").matcher(body);
        int contexts = 0;
        while (terms.find() && contexts++ < 64) {
            int start = Math.max(0, terms.start() - 100), end = Math.min(body.length(), terms.end() + 160);
            findings.add("TEXT EVIDENCE (not physical proof) " + source + " offset=" + terms.start()
                    + " [" + terms.group() + "] " + body.substring(start, end).replaceAll("[\\r\\n\\t]+", " "));
        }
        return findings;
    }

    public static boolean readOnlySoap(String action) {
        if (action == null) return false;
        return action.matches("urn:schemas-upnp-org:service:RenderingControl:[0-9]+#Get(?:Volume|Mute)")
                || action.matches("urn:schemas-upnp-org:service:AVTransport:[0-9]+#GetTransportInfo")
                || action.matches("urn:schemas-upnp-org:service:ConnectionManager:[0-9]+#GetProtocolInfo");
    }
}
