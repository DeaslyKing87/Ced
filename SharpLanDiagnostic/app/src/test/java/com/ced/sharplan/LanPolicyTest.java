package com.ced.sharplan;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class LanPolicyTest {
    @Test public void discoversCrLfLocationAndPreservesPort() {
        String message = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.50:49152/dmr.xml\r\n"
                + "ST: upnp:rootdevice\r\n\r\n";
        assertEquals("http://192.168.1.50:49152/dmr.xml",
                LanPolicy.ssdpHeaders(message).get("location"));
        assertEquals("upnp:rootdevice", LanPolicy.ssdpHeaders(message).get("st"));
    }

    @Test public void ignoresNonDiscoveryTraffic() {
        assertTrue(LanPolicy.ssdpHeaders("GET / HTTP/1.1\r\nLocation: http://10.0.0.2/").isEmpty());
    }

    @Test public void onlyAcceptsOnLinkPrivateUnicast() {
        assertTrue(LanPolicy.sameSubnet("192.168.1.50", "192.168.1.10", 24));
        assertFalse(LanPolicy.sameSubnet("192.168.2.50", "192.168.1.10", 24));
        assertFalse(LanPolicy.sameSubnet("192.168.1.255", "192.168.1.10", 24));
        assertFalse(LanPolicy.sameSubnet("192.168.1.0", "192.168.1.10", 24));
        assertFalse(LanPolicy.sameSubnet("8.8.8.8", "192.168.1.10", 24));
        assertFalse(LanPolicy.privateAddress("127.0.0.1"));
        assertFalse(LanPolicy.privateAddress("224.0.0.1"));
    }

    @Test(expected = IOException.class)
    public void refusesAmbiguousNumericIp() throws Exception {
        LanPolicy.address("192.168.001.5");
    }

    @Test public void resolvesAdvertisedRelativeEndpoint() throws Exception {
        assertEquals("http://192.168.1.50:49152/upnp/control",
                LanPolicy.endpoint("http://192.168.1.50:49152/device.xml",
                        "/upnp/control", "192.168.1.50").toString());
    }

    @Test(expected = IOException.class)
    public void refusesDescriptionRedirectToAnotherDevice() throws Exception {
        LanPolicy.endpoint("http://192.168.1.50/device.xml",
                "http://192.168.1.1/admin", "192.168.1.50");
    }

    @Test(expected = IOException.class)
    public void refusesInternetEndpoint() throws Exception {
        LanPolicy.endpoint(null, "https://example.com/control", "192.168.1.50");
    }

    @Test(expected = IOException.class)
    public void refusesEmbeddedCredentials() throws Exception {
        LanPolicy.endpoint(null, "http://user:pass@192.168.1.50/", "192.168.1.50");
    }

    @Test public void legacyFramesHaveEightAsciiBytesAndCarriageReturn() throws Exception {
        assertEquals("POWR????\r", LanPolicy.frame("POWR", "????"));
        assertEquals("VOLM12  \r", LanPolicy.frame("VOLM", "12"));
    }

    @Test(expected = IOException.class)
    public void preventsMultipleCommandInjection() throws Exception {
        LanPolicy.frame("POWR", "1\rPOWR0");
    }

    @Test public void identifiesExactEuropeanModelWithoutTrustingFriendlyName() {
        assertTrue(LanPolicy.exactModel("LC-40UI7352E"));
        assertTrue(LanPolicy.exactModel("LC 40 UI7352E"));
        assertFalse(LanPolicy.exactModel("LC-40LE735"));
        assertFalse(LanPolicy.sharp("Router vendor", "Smart Remote Control"));
        assertTrue(LanPolicy.sharp("UMC", "LC-40UI7352E"));
    }
}
