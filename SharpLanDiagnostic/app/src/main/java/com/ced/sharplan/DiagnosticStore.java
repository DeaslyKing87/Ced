package com.ced.sharplan;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Preserves raw diagnostic captures separately from the bounded UI log. */
public final class DiagnosticStore {
    private final File root;
    private final long quota;
    private File session;
    private int number;
    public DiagnosticStore(File root) { this(root, 32L * 1024 * 1024); }
    DiagnosticStore(File root, long quota) { this.root = root; this.quota = quota; }

    public synchronized void begin(String identity) throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create capture directory.");
        session = new File(root, System.currentTimeMillis() + "-" + UUID.randomUUID().toString());
        if (!session.mkdir()) throw new IOException("Cannot create capture session.");
        number = 0;
        save("session.txt", identity.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized String capture(String kind, String source, int status, Map<String, List<String>> headers,
                          byte[] body, boolean truncated) throws Exception {
        if (session == null) begin("Diagnostic capture");
        String prefix = String.format(Locale.ROOT, "%04d-%s", ++number,
                kind.replaceAll("[^A-Za-z0-9_-]", "_"));
        String extension = source.matches("(?i).*\\.js(?:\\?.*)?") ? ".js"
                : source.matches("(?i).*\\.json(?:\\?.*)?") ? ".json"
                : source.matches("(?i).*\\.xml(?:\\?.*)?") ? ".xml"
                : source.matches("(?i).*\\.csv(?:\\?.*)?") ? ".csv" : ".txt";
        String file = prefix + extension;
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(body));
        String metadata = "Source: " + source + "\nHTTP/status: " + status
                + "\nBytes: " + body.length + "\nTruncated: " + truncated
                + "\nSHA-256: " + digest + "\nHeaders: " + headers
                + "\nNo physical TV control is established by this capture.\n";
        long required = body.length + metadata.getBytes(StandardCharsets.UTF_8).length;
        if (size(root) + required > quota)
            throw new IOException("Capture quota reached; existing captures retained. Export logs.");
        save(file, body);
        save(prefix + ".metadata.txt", metadata.getBytes(StandardCharsets.UTF_8));
        return session.getName() + "/" + file;
    }

    public synchronized void findings(List<String> entries) throws IOException {
        if (session == null || entries.isEmpty()) return;
        String text = String.join("\n", entries) + "\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (size(root) + bytes.length > quota)
            throw new IOException("Capture quota reached; findings remain in the UI log.");
        try (OutputStream out = new FileOutputStream(new File(session, "FINDINGS.txt"), true)) {
            out.write(bytes);
        }
    }

    private void save(String name, byte[] bytes) throws IOException {
        if (size(root) + bytes.length > quota)
            throw new IOException("Capture quota reached; no existing captures deleted.");
        try (OutputStream out = new FileOutputStream(new File(session, name))) {
            out.write(bytes);
        }
    }

    private static long size(File directory) {
        if (directory.isFile()) return directory.length();
        long result = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) result += size(file);
        return result;
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    public synchronized void zip(OutputStream destination, String report) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(destination)) {
            out.putNextEntry(new ZipEntry("diagnostic-log.txt"));
            out.write(report.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (root.exists()) zipDirectory(root, "captures/", out, 0);
        }
    }

    private void zipDirectory(File directory, String prefix, ZipOutputStream out, int depth)
            throws IOException {
        if (depth > 4) throw new IOException("Capture directory too deep.");
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
                throw new IOException("Invalid capture path.");
            if (file.isDirectory()) zipDirectory(file, prefix + file.getName() + "/", out, depth + 1);
            else {
                out.putNextEntry(new ZipEntry(prefix + file.getName()));
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                out.closeEntry();
            }
        }
    }
}
