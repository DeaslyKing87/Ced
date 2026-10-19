package com.ced.sharplan;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class DiagnosticStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void exportsRawResourcesAndMetadataAlongsideLog() throws Exception {
        File directory = temp.newFolder("captures");
        DiagnosticStore store = new DiagnosticStore(directory);
        store.begin("User-confirmed TV");
        String source = "{\"ServiceID\":\"tv\",\"ActionID\":12}";
        store.capture("script", "http://192.168.1.45:54871/web/a.js", 200,
                Collections.emptyMap(), source.getBytes(StandardCharsets.UTF_8), false);
        store.findings(Arrays.asList("UNVERIFIED ID CANDIDATE ServiceID=tv"));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        store.zip(archive, "read-only diagnostic log");
        Map<String, String> contents = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                contents.put(entry.getName(), out.toString("UTF-8"));
            }
        }
        assertEquals("read-only diagnostic log", contents.get("diagnostic-log.txt"));
        assertTrue(contents.values().contains(source));
        assertTrue(contents.values().stream().anyMatch(s -> s.contains("SHA-256:")));
        assertTrue(contents.keySet().stream().anyMatch(s -> s.endsWith("FINDINGS.txt")));
    }

    @Test public void preservesCapturesAcrossStoreInstances() throws Exception {
        File directory = temp.newFolder("persist");
        DiagnosticStore first = new DiagnosticStore(directory);
        first.begin("old session");
        first.capture("event", "TV event", 200, Collections.emptyMap(), new byte[]{65}, false);
        DiagnosticStore reopened = new DiagnosticStore(directory);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reopened.zip(out, "new report");
        assertTrue(out.size() > 200);
    }

    @Test public void quotaStopsNewCapturesWithoutDeletingOldData() throws Exception {
        File directory = temp.newFolder("quota");
        DiagnosticStore store = new DiagnosticStore(directory, 300);
        store.begin("keep this identity");
        try {
            store.capture("large", "web", 200, Collections.emptyMap(), new byte[1000], false);
            fail("Quota should stop capture.");
        } catch (IOException expected) { }
        File[] sessions = directory.listFiles();
        assertNotNull(sessions);
        assertTrue(new File(sessions[0], "session.txt").isFile());
    }
}
