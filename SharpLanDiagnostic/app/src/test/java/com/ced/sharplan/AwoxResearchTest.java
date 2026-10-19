package com.ced.sharplan;

import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import static org.junit.Assert.*;

public class AwoxResearchTest {
    private URL origin() throws Exception { return new URL("http://192.168.1.45:54871/web/"); }

    @Test public void identifiesOnlyTheReportedMStarProfile() {
        assertTrue(AwoxResearch.reportedProfile("Sharp_UHD_TV (78:dd:d9:48:53:2e)", "MStar", "MStarDMR"));
        assertFalse(AwoxResearch.reportedProfile("Sharp_UHD_TV (11:22:33:44:55:66)", "MStar", "MStarDMR"));
        assertFalse(AwoxResearch.reportedProfile("My NAS", "MStar", "MStarDMR"));
    }

    @Test public void neverPromotesRendererOrUnprovenReplies() {
        assertFalse(TvControlGate.canUnlock(true, true, true));
        assertFalse(TvControlGate.canUnlock(false, false, true));
        assertFalse(TvControlGate.canUnlock(false, true, false));
        assertFalse(TvControlGate.canUnlock(false, false, false));
        assertTrue(TvControlGate.canUnlock(false, true, true));
    }

    @Test public void onlyAllowsKnownReadOnlySoap() {
        String vendor = AwoxResearch.TYPE + "#";
        assertFalse(AwoxResearch.readOnlySoap(vendor + "PerformAction"));
        assertFalse(AwoxResearch.readOnlySoap(vendor + "SetProperty"));
        assertFalse(AwoxResearch.readOnlySoap(vendor + "GetProperty"));
        assertFalse(AwoxResearch.readOnlySoap("urn:schemas-upnp-org:service:RenderingControl:1#SetVolume"));
        assertFalse(AwoxResearch.readOnlySoap("urn:schemas-upnp-org:service:RenderingControl:1#SetMute"));
        assertTrue(AwoxResearch.readOnlySoap("urn:schemas-upnp-org:service:RenderingControl:1#GetVolume"));
        assertTrue(AwoxResearch.readOnlySoap("urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo"));
    }

    @Test public void followsStaticControlScriptWithoutExecutingIt() throws Exception {
        String page = "<script src='js/control.js?v=2'></script><a href='/web/services.json'>schema</a>"
                + "<form action='/reset'><input name='cmd'></form>"
                + "<script>fetch('/control?key=power');</script>"
                + "<script src='https://example.com/remote.js'></script>";
        List<URL> refs = AwoxResearch.references(origin(), origin(), page);
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(u -> u.toString().equals(
                "http://192.168.1.45:54871/web/js/control.js?v=2")));
    }

    @Test public void discoversQuotedJsonAndXmlReferences() throws Exception {
        List<URL> refs = AwoxResearch.references(origin(), origin(),
                "var a='/web/services.json'; var b=\"/web/manager.xml\";");
        assertEquals(2, refs.size());
    }

    @Test(expected = IOException.class) public void rejectsDifferentPort() throws Exception {
        AwoxResearch.resource(origin(), origin(), "http://192.168.1.45:80/web/config.xml");
    }

    @Test(expected = IOException.class) public void rejectsDifferentHost() throws Exception {
        AwoxResearch.resource(origin(), origin(), "http://192.168.1.1/web/config.xml");
    }

    @Test(expected = IOException.class) public void rejectsActionQuery() throws Exception {
        AwoxResearch.resource(origin(), origin(), "/web/config.json?ActionID=7");
    }

    @Test(expected = IOException.class) public void rejectsMutatingResourcePath() throws Exception {
        AwoxResearch.resource(origin(), origin(), "/web/reboot.json");
    }

    @Test(expected = IOException.class) public void rejectsEncodedTraversal() throws Exception {
        AwoxResearch.resource(origin(), origin(), "/web/%2e%2e/config.xml");
    }

    @Test public void preservesEncodedStaticResourcePath() throws Exception {
        assertEquals("http://192.168.1.45:54871/web/a%20b.js",
                AwoxResearch.resource(origin(), origin(), "/web/a%20b.js#x").toString());
    }

    @Test public void separatesIdentifierValuesFromSchemas() {
        List<String> result = AwoxResearch.findings("event",
                "{\"ServiceID\":\"tv\",\"ActionID\":12,\"PropertyID\":\"volume\"}"
                        + "<ServiceID>other</ServiceID>");
        assertTrue(result.stream().anyMatch(s -> s.contains("ServiceID=tv")));
        assertTrue(result.stream().anyMatch(s -> s.contains("ActionID=12")));
        assertTrue(result.stream().anyMatch(s -> s.contains("PropertyID=volume")));
        assertTrue(result.stream().anyMatch(s -> s.contains("ServiceID=other")));
        assertTrue(result.stream().allMatch(s -> s.contains("UNVERIFIED") || s.contains("not physical proof")));
    }

    @Test public void candidateExtractionIsBounded() {
        assertTrue(AwoxResearch.findings("script",
                String.join(" ", Collections.nCopies(10000, "ActionID=123 volume"))).size() <= 144);
    }
}
