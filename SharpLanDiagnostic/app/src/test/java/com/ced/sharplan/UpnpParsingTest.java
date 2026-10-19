package com.ced.sharplan;

import org.junit.Test;
import org.w3c.dom.Document;
import java.io.IOException;
import static org.junit.Assert.*;

public class UpnpParsingTest {
    @Test public void everyNormalKeyStaysLockedForEveryPreviouslyRespondingProtocol() {
        for (int protocol : new int[]{MainActivity.NONE, MainActivity.LEGACY, MainActivity.UPNP})
            for (String key : new String[]{"Power", "Volume +", "Volume −", "Mute", "Up", "Down",
                    "Left", "Right", "OK", "Back", "Home", "Input", "Channel +", "Channel −", "Menu", "0"})
                assertFalse(key + " must stay locked", MainActivity.normalTvKeyAllowed(key, protocol));
    }

    @Test public void readsNamespacedSoapVolume() throws Exception {
        String xml = "<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'>"
                + "<s:Body><u:GetVolumeResponse "
                + "xmlns:u='urn:schemas-upnp-org:service:RenderingControl:1'>"
                + "<CurrentVolume>18</CurrentVolume>"
                + "</u:GetVolumeResponse></s:Body></s:Envelope>";
        Document doc = MainActivity.xml(xml);
        assertEquals(1, doc.getElementsByTagNameNS(
                "urn:schemas-upnp-org:service:RenderingControl:1", "GetVolumeResponse").getLength());
        assertEquals(18, MainActivity.parseNumber(MainActivity.text(doc, "CurrentVolume"), 0, 100));
    }

    @Test(expected = IOException.class)
    public void rejectsExternalEntityDescription() throws Exception {
        MainActivity.xml("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                + "<root><friendlyName>&x;</friendlyName></root>");
    }

    @Test(expected = IOException.class)
    public void rejectsErrAsProtocolVerification() throws Exception {
        MainActivity.parseNumber("ERR", 0, 100);
    }

    @Test(expected = IOException.class)
    public void rejectsHtmlAsProtocolVerification() throws Exception {
        MainActivity.parseNumber("<html>1</html>", 0, 1);
    }

    @Test(expected = IOException.class)
    public void rejectsOutOfRangePowerState() throws Exception {
        MainActivity.parseNumber("200", 0, 1);
    }

    @Test public void parsesBooleanResponsesStrictly() throws Exception {
        assertTrue(MainActivity.parseBoolean("1"));
        assertFalse(MainActivity.parseBoolean("false"));
        try {
            MainActivity.parseBoolean("OK");
            fail("An acknowledgement is not a boolean state.");
        } catch (IOException expected) { }
    }
}
