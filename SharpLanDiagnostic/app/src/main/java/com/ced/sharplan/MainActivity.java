package com.ced.sharplan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Color;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Continues the original diagnostic app; no guessed remote-key mappings. */
public class MainActivity extends Activity {
    interface Work { void run() throws Exception; }
    static final int NONE = 0, LEGACY = 1, UPNP = 2, EXPORT = 41;
    static final int RESPONSE_LIMIT = 262144;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final AtomicBoolean busy = new AtomicBoolean();
    final StringBuilder log = new StringBuilder();
    final Map<String, Button> keyButtons = new LinkedHashMap<>();
    LinearLayout root, devices, controls;
    TextView status, logView;
    EditText ip;
    CheckBox confirmTarget;
    Button scanButton, manualButton, diagnoseButton, exportButton, shareButton;
    volatile Device selected;
    volatile DatagramSocket discoverySocket;
    Network lan;
    List<LinkAddress> localAddresses = new ArrayList<>();
    volatile int protocol = NONE;
    volatile boolean powerReady, volumeReady, muteReady;
    volatile String connection = "Select your television.";
    Service volumeService, muteService;
    DiagnosticStore captures;
    EditText portInput;
    TextView rendererView, managerView;
    Button researchButton, eventButton;
    volatile String rendererState = "Not inspected; read-only renderer state is separate from TV controls.";
    volatile String managerState = "Not inspected. No vendor ServiceID/ActionID mapping verified.";
    volatile GenaObserver observer;
    volatile Device researchTarget;


    static class Service {
        String type = "", id = "", control = "", scpd = "", event = "";
        boolean scpdRead;
        final Set<String> evented = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();
        int minimum = 0, maximum = 100;
    }

    static class Device {
        String name = "", model = "", manufacturer = "", ip = "", location = "", udn = "", presentation = "";
        int reportedPort = 54871;
        boolean userConfirmed;
        final List<Service> services = new CopyOnWriteArrayList<>();
        boolean sharp() { return LanPolicy.sharp(manufacturer, model)
                || AwoxResearch.reportedProfile(name, manufacturer, model); }
        boolean exact() { return LanPolicy.exactModel(model); }
        String label() {
            return (exact() ? "TARGET LC-40UI7352E\n"
                    : AwoxResearch.reportedProfile(name, manufacturer, model)
                    ? "Your tested Sharp (MStar/AwoX profile)\n"
                    : sharp() ? "SHARP / UMC candidate\n" : "")
                    + (name.isEmpty() ? "Unidentified device" : name)
                    + "\n" + ip + (model.isEmpty() ? "" : " · " + model);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        File saved = new File(getFilesDir(), "sharp-diagnostic.txt");
        if (saved.exists()) {
            try (InputStream in = new FileInputStream(saved)) {
                log.append(new String(readLimit(in, 1000000), StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        captures = new DiagnosticStore(new File(getFilesDir(), "captures"));
        buildUi();
        append("Sharp LAN Diagnostic v0.3 — AwoX research. REAL-TV REPORT: renderer volume/mute "
                + "did not affect physical TV. ALL TV controls remain locked. No unknown vendor actions.");
    }

    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    TextView tv(String message, int size) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setPadding(0, dp(7), 0, dp(7));
        return view;
    }

    Button btn(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setContentDescription(label);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    Button addButton(String label, Runnable action) {
        Button button = btn(label, action);
        root.addView(button);
        return button;
    }

    void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(11, 13, 18));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(20));
        scroll.addView(root);
        root.addView(tv("Sharp LAN Diagnostic", 26));
        root.addView(tv("LC-40UI7352E · AQUOS NET+ · local only", 14));
        status = tv("", 15);
        root.addView(status);

        scanButton = addButton("Discover TVs", () -> job(this::discover));
        ip = new EditText(this);
        ip.setHint("TV IPv4 address, e.g. 192.168.1.50");
        ip.setTextColor(Color.WHITE);
        ip.setHintTextColor(Color.GRAY);
        ip.setSingleLine();
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setText("192.168.1.45");
        root.addView(ip);

        manualButton = addButton("Select manual TV IP", this::manual);
        root.addView(tv("Discovered devices", 18));
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        root.addView(devices);

        confirmTarget = new CheckBox(this);
        confirmTarget.setText("I confirm this IP belongs to my Sharp TV");
        confirmTarget.setVisibility(View.GONE);
        root.addView(confirmTarget);
        diagnoseButton = addButton("Inspect selected TV — read-only", this::diagnose);
        root.addView(tv(AwoxResearch.RENDERER_LABEL, 17));
        rendererView = tv("", 13);
        root.addView(rendererView);
        root.addView(tv("AwoX X_ServiceManager findings", 19));
        managerView = tv("", 13);
        managerView.setTextIsSelectable(true);
        root.addView(managerView);
        portInput = new EditText(this);
        portInput.setHint("UPnP/web port from your report or current discovery");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText("54871");
        root.addView(portInput);
        researchButton = addButton("Inspect /web + AwoX SCPD — GET only", this::startResearch);
        eventButton = addButton("Observe ServiceManager events for 30 seconds", this::startEvents);
        root.addView(tv("Run GET inspection first. During event observation, use the physical TV remote. "
                + "No JavaScript is executed and no unknown vendor action or property is sent.", 13));


        root.addView(tv("Remote", 20));
        root.addView(tv("All TV keys are locked in this research build. Renderer replies and HTTP 200 "
                + "are not physical TV evidence.", 13));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);
        row("Power", "Home", "Input");
        row("Volume +", "Mute", "Channel +");
        row("Volume −", "Menu", "Channel −");
        row("Up");
        row("Left", "OK", "Right");
        row("Down");
        row("Back");
        row("1", "2", "3");
        row("4", "5", "6");
        row("7", "8", "9");
        row("0");
        root.addView(tv("Navigation, Home, Input and number keys remain locked "
                + "until a model-compatible key protocol is established. "
                + "DLNA media services do not establish full TV control.", 13));

        root.addView(tv("Diagnostics", 20));
        exportButton = addButton("Export logs + raw captures (ZIP)", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "SharpLanDiagnostic-0.3-logs.zip");
            startActivityForResult(intent, EXPORT);
        });
        shareButton = addButton("Share logs + raw captures", this::share);
        logView = tv("", 12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);
        setContentView(scroll);
        refreshUi();
    }

    void row(String... labels) {
        LinearLayout line = new LinearLayout(this);
        controls.addView(line);
        for (String key : labels) {
            Button button = btn(key, () -> job(() -> sendKey(key)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), 1);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            button.setLayoutParams(params);
            line.addView(button);
            keyButtons.put(key, button);
        }
    }

    void ui(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

    void append(String message) {
        synchronized (log) {
            log.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date())).append("  ").append(message).append('\n');
            if (log.length() > 240000) log.delete(0, log.length() - 180000);
            try (OutputStream out = openFileOutput("sharp-diagnostic.txt", Context.MODE_PRIVATE)) {
                out.write(log.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
        ui(this::refreshUi);
    }

    String report() {
        synchronized (log) {
            return "Sharp LC-40UI7352E diagnostic report\n"
                    + "This is not a claim that physical TV controls work.\n\n" + log;
        }
    }

    void refreshUi() {
        if (status == null) return;
        boolean idle = !busy.get();
        String p = protocol == LEGACY ? "Legacy query replies only — TV keys locked"
                : protocol == UPNP ? "DLNA renderer replies only — TV keys locked"
                : "No physical TV remote interface verified";
        status.setText((selected == null ? "No TV selected" : selected.label())
                + "\n" + p + "\n" + (idle ? connection : "Working…"));
        scanButton.setEnabled(idle);
        manualButton.setEnabled(idle);
        diagnoseButton.setEnabled(idle && selected != null);
        ip.setEnabled(idle);
        confirmTarget.setEnabled(idle);
        exportButton.setEnabled(idle);
        shareButton.setEnabled(idle);
        portInput.setEnabled(idle);
        researchButton.setEnabled(idle && selected != null);
        Service manager = selected == null ? null : manager(selected);
        eventButton.setEnabled(idle && researchTarget == selected
                && manager != null && manager.scpdRead && !manager.event.isEmpty());
        rendererView.setText(rendererState);
        managerView.setText(managerState);

        for (int n = 0; n < devices.getChildCount(); n++)
            devices.getChildAt(n).setEnabled(idle);
        for (Map.Entry<String, Button> entry : keyButtons.entrySet())
            entry.getValue().setEnabled(idle && supports(entry.getKey()));
        synchronized (log) {
            logView.setText(log.substring(Math.max(0, log.length() - 16000)));
        }
    }

    boolean supports(String key) {
        // No key mapping has physically controlled this TV. Never promote renderer responses.
        return normalTvKeyAllowed(key, protocol);
    }

    static boolean normalTvKeyAllowed(String key, int detectedProtocol) {
        // No app command + matching TV state + user-confirmed physical effect exists.
        // The IP confirmation checkbox is identity confirmation, never command verification.
        return TvControlGate.canUnlock(detectedProtocol == UPNP, false, false);
    }

    void resetControl() {
        protocol = NONE;
        powerReady = volumeReady = muteReady = false;
        volumeService = muteService = null;
        researchTarget = null;
    }

    void job(Work action) { runJob(action, true); }

    void localJob(Work action) { runJob(action, false); }

    void runJob(Work action, boolean network) {
        if (!busy.compareAndSet(false, true)) return;
        refreshUi();
        ex.submit(() -> {
            try {
                if (network) selectLan();
                action.run();
            } catch (Exception e) {
                resetControl();
                connection = "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                append(connection);
            } finally {
                busy.set(false);
                ui(this::refreshUi);
            }
        });
    }

    void selectLan() throws IOException {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network previous = lan;
        lan = null;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(network);
            LinkProperties p = cm.getLinkProperties(network);
            if (c == null || p == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                continue;
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
            List<LinkAddress> addresses = new ArrayList<>();
            for (LinkAddress address : p.getLinkAddresses())
                if (address.getAddress() instanceof Inet4Address
                        && LanPolicy.privateAddress(address.getAddress().getHostAddress()))
                    addresses.add(address);
            if (!addresses.isEmpty()) {
                if (previous != null && !previous.equals(network)) {
                    resetControl();
                    connection = "Network changed. Inspect the TV again before using controls.";
                }
                lan = network;
                localAddresses = addresses;
                return;
            }
        }
        throw new IOException("No private IPv4 Wi-Fi/Ethernet LAN. "
                + "Connect the phone and TV to the same network.");
    }

    boolean onLan(String address) {
        for (LinkAddress local : localAddresses)
            if (LanPolicy.sameSubnet(address, local.getAddress().getHostAddress(),
                    local.getPrefixLength())) return true;
        return false;
    }

    void requireLocal(String address) throws IOException {
        if (!onLan(address)) throw new IOException("Destination is outside the connected LAN: " + address);
    }

    void manual() {
        if (busy.get()) return;
        try {
            Device d = new Device();
            d.ip = LanPolicy.address(ip.getText().toString().trim()).getHostAddress();
            d.name = "Manual TV";
            choose(d);
        } catch (Exception e) {
            Toast.makeText(this, "Enter a numeric IPv4 address.", Toast.LENGTH_LONG).show();
        }
    }

    void choose(Device d) {
        if (busy.get()) return;
        selected = d;
        resetControl();
        d.userConfirmed = d.sharp();
        researchTarget = null;
        rendererState = "Not inspected. DLNA state never unlocks TV keys.";
        managerState = "No X_ServiceManager findings for this selection.";
        confirmTarget.setChecked(false);
        confirmTarget.setVisibility(d.sharp() ? View.GONE : View.VISIBLE);
        connection = d.sharp() ? "TV identity found. Ready for read-only inspection."
                : "Confirm this IP is your Sharp TV before inspection.";
        append("Selected " + d.label() + "; manufacturer=" + d.manufacturer);
        refreshUi();
        if (!d.location.isEmpty()) {
            try {
                d.reportedPort = AwoxResearch.port(new URL(d.location));
                portInput.setText(Integer.toString(d.reportedPort));
            }
            catch (Exception ignored) { }
        }
        if (d.sharp()) diagnose();
    }

    void show(Device d) {
        ui(() -> devices.addView(btn(d.label(), () -> choose(d))));
    }

    Map<String, Set<String>> ssdp(String filter, int duration) throws Exception {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm.createMulticastLock("sharpdiag");
        lock.setReferenceCounted(false);
        lock.acquire();
        try (DatagramSocket socket = new DatagramSocket(null)) {
            discoverySocket = socket;
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            lan.bindSocket(socket);
            socket.setSoTimeout(400);
            for (String target : new String[]{"ssdp:all", "upnp:rootdevice",
                    "urn:schemas-upnp-org:device:MediaRenderer:1"}) {
                String message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                        + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n";
                byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(bytes, bytes.length,
                        InetAddress.getByName("239.255.255.250"), 1900));
            }
            long end = android.os.SystemClock.elapsedRealtime() + duration;
            int count = 0;
            while (android.os.SystemClock.elapsedRealtime() < end && count < 192
                    && !Thread.currentThread().isInterrupted()) {
                DatagramPacket response = new DatagramPacket(new byte[16384], 16384);
                try { socket.receive(response); }
                catch (SocketTimeoutException e) { continue; }
                count++;
                String sender = response.getAddress().getHostAddress();
                if (!onLan(sender) || (filter != null && !filter.equals(sender))) continue;
                String raw = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                String location = LanPolicy.ssdpHeaders(raw).get("location");
                if (location == null) continue;
                try {
                    LanPolicy.endpoint(null, location, sender);
                    if (!found.containsKey(sender) && found.size() >= 48) continue;
                    Set<String> locations = found.computeIfAbsent(sender, ignored -> new LinkedHashSet<>());
                    if (locations.size() < 3 && locations.add(location))
                        append("SSDP response from " + sender + "\n" + raw);
                } catch (Exception e) {
                    append("Rejected SSDP LOCATION: " + e.getMessage());
                }
            }
        } finally {
            discoverySocket = null;
            if (lock.isHeld()) lock.release();
        }
        return found;
    }

    void discover() throws Exception {
        resetControl();
        append("SSDP discovery on Wi-Fi/Ethernet; multicast 239.255.255.250:1900.");
        Map<String, Set<String>> found = ssdp(null, 6000);
        ui(() -> devices.removeAllViews());
        for (Map.Entry<String, Set<String>> entry : found.entrySet()) {
            Device device = new Device();
            device.ip = entry.getKey();
            for (String location : entry.getValue()) {
                try { readDescription(device, location); }
                catch (Exception e) { append("Description error " + location + ": " + e.getMessage()); }
            }
            append("Device " + device.label() + "; manufacturer=" + device.manufacturer
                    + "; exact model=" + device.exact());
            show(device);
        }
        connection = "Scan complete: " + found.size() + " UPnP hosts. Select your TV or enter its IP.";
        append(connection + " An empty scan does not prove LAN control impossible.");
    }

    static Document xml(String source) throws Exception {
        String upper = source.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
            throw new IOException("DTD/entity declarations are rejected.");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new org.xml.sax.SAXException("External entities are rejected.");
        });
        return builder.parse(new InputSource(new StringReader(source)));
    }

    static String text(Document document, String tag) {
        NodeList list = document.getElementsByTagNameNS("*", tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    static String child(Element element, String name) {
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(n.getLocalName()))
                return n.getTextContent().trim();
        return "";
    }

    void readDescription(Device d, String location) throws Exception {
        String raw = request(d.ip, location, null, null);
        append("Device XML " + location + "\n" + raw);
        Document doc = xml(raw);
        NodeList deviceNodes = doc.getElementsByTagNameNS("*", "device");
        if (deviceNodes.getLength() == 0) throw new IOException("No UPnP device element.");
        Element device = (Element) deviceNodes.item(0);
        String model = child(device, "modelName");
        if (d.name.isEmpty() || LanPolicy.exactModel(model)) {
            d.name = child(device, "friendlyName");
            d.model = model;
            d.manufacturer = child(device, "manufacturer");
            d.udn = child(device, "UDN");
        } else if (d.model.isEmpty()) {
            d.model = model;
            d.manufacturer = child(device, "manufacturer");
        }
        d.location = location;
        String base = text(doc, "URLBase");
        if (base.isEmpty()) base = location;
        else base = LanPolicy.endpoint(null, base, d.ip).toString();
        String presentation = child(device, "presentationURL");
        if (!presentation.isEmpty()) {
            try { d.presentation = LanPolicy.endpoint(base, presentation, d.ip).toString(); }
            catch (Exception e) { append("Rejected presentation URL: " + e.getMessage()); }
        }
        NodeList services = doc.getElementsByTagNameNS("*", "service");
        for (int i = 0; i < services.getLength(); i++) {
            Element element = (Element) services.item(i);
            Service service = new Service();
            service.type = child(element, "serviceType");
            service.id = child(element, "serviceId");
            String control = child(element, "controlURL"), scpd = child(element, "SCPDURL");
            String event = child(element, "eventSubURL");
            try {
                if (!control.isEmpty()) service.control =
                        LanPolicy.endpoint(base, control, d.ip).toString();
                if (!scpd.isEmpty()) service.scpd =
                        LanPolicy.endpoint(base, scpd, d.ip).toString();
                if (!event.isEmpty()) service.event = LanPolicy.endpoint(base, event, d.ip).toString();
                boolean duplicate = false;
                for (Service existing : d.services)
                    if (existing.type.equals(service.type) && existing.control.equals(service.control))
                        duplicate = true;
                if (!duplicate) {
                    d.services.add(service);
                    append("Service " + service.type + "\n control=" + service.control
                            + "\n SCPD=" + service.scpd + "\n event=" + service.event);
                }
            } catch (Exception e) { append("Rejected service URL: " + e.getMessage()); }
        }
    }

    String request(String target, String address, String action, String body) throws Exception {
        requireLocal(target);
        if (body != null && !AwoxResearch.readOnlySoap(action))
            throw new IOException("Non-read-only or unidentified SOAP action blocked: " + action);
        URL url = LanPolicy.endpoint(null, address, target);
        HttpURLConnection c = (HttpURLConnection) lan.openConnection(url, java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(1500);
        c.setReadTimeout(2200);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        try {
            if (body == null) {
                c.setRequestMethod("GET");
                append("SEND HTTP GET " + address);
            } else {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                c.setRequestProperty("SOAPACTION", "\"" + action + "\"");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                append("SEND SOAP " + address + " " + action + "\n" + body);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int code = c.getResponseCode();
            InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String response = "";
            if (stream != null) try (InputStream in = stream) {
                response = new String(readLimit(in, RESPONSE_LIMIT), StandardCharsets.UTF_8);
            }
            capture("http", address, code, c.getHeaderFields(), response.getBytes(StandardCharsets.UTF_8), false);
            append("RECV HTTP " + code + " " + address + (body == null ? "" : "\n" + response));
            if (code < 200 || code >= 300)
                throw new IOException("HTTP " + code + " " + response.substring(0, Math.min(800, response.length())));
            return response;
        } finally { c.disconnect(); }
    }

    static byte[] readLimit(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > maximum) throw new IOException("Response exceeds size limit.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    void diagnose() {
        Device d = selected;
        if (d == null || busy.get()) return;
        boolean confirmed = d.sharp() || confirmTarget.isChecked();
        if (!confirmed) {
            Toast.makeText(this, "Confirm that this IP is your Sharp TV first.", Toast.LENGTH_LONG).show();
            return;
        }
        d.userConfirmed = confirmed;
        try { d.reportedPort = parseNumber(portInput.getText().toString().trim(), 1, 65535); }
        catch (Exception e) { append("Invalid UPnP/web port."); return; }
        job(() -> inspect(d));
    }

    void inspect(Device d) throws Exception {
        resetControl();
        requireLocal(d.ip);
        captures.begin("SharpLanDiagnostic 0.3\nSelected TV: " + d.ip
                + "\nUser-reported target LC-40UI7352E; no physical key verified.\n");
        append("=== READ-ONLY INSPECTION of selected TV " + d.ip + " ===");
        if (d.location.isEmpty()) {
            append("Looking for an advertised description from this manually selected IP.");
            Set<String> locations = ssdp(d.ip, 3000).get(d.ip);
            if (locations != null) for (String location : locations) {
                try { readDescription(d, location); }
                catch (Exception e) { append("Manual description lookup: " + e.getMessage()); }
            }
        }
        append("Selected identity: " + d.label() + "; maker=" + d.manufacturer
                + "; UDN=" + d.udn + "; exact target=" + d.exact());
        Set<Integer> ports = new LinkedHashSet<>(Arrays.asList(80, 443, 10002));
        if (!d.location.isEmpty()) addPort(ports, d.location);
        for (Service s : d.services) {
            addPort(ports, s.control);
            addPort(ports, s.scpd);
            if (ports.size() >= 24) break;
        }
        boolean legacyPort = false;
        for (int port : ports) {
            boolean open = tcpOpen(d.ip, port);
            if (port == 10002) legacyPort = open;
        }

        if (legacyPort) {
            try {
                parseNumber(tcp(d.ip, "POWR", "????"), 0, 1);
                parseNumber(tcp(d.ip, "VOLM", "????"), 0, 100);
                protocol = LEGACY;
                powerReady = volumeReady = false;
                try {
                    parseNumber(tcp(d.ip, "MUTE", "????"), 1, 2);
                    muteReady = false;
                } catch (Exception e) { append("Legacy mute unavailable: " + e.getMessage()); }
                append("Legacy Sharp query replies observed. All TV keys remain locked: "
                        + "no physical TV key effect confirmed.");
            } catch (Exception e) {
                resetControl();
                append("Legacy Sharp TCP NOT verified: " + e.getMessage());
            }
        }

        for (Service s : d.services) {
            try {
                readScpd(d, s);
                safeSoap(d, s);
            } catch (Exception e) {
                append("UPnP service " + s.type + ": " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
        if (manager(d) != null || AwoxResearch.reportedProfile(d.name, d.manufacturer, d.model)) {
            try { inspectAwox(d); }
            catch (Exception e) { append("AwoX GET inspection: " + e.getMessage()); }
        }
        connection = "Read-only inspection finished. All physical TV keys remain locked.";
        append(connection);
    }

    void addPort(Set<Integer> ports, String address) {
        if (address.isEmpty() || ports.size() >= 24) return;
        try {
            URL url = new URL(address);
            ports.add(url.getPort() > 0 ? url.getPort() : url.getDefaultPort());
        } catch (Exception ignored) { }
    }

    boolean tcpOpen(String host, int port) throws Exception {
        requireLocal(host);
        try (Socket socket = lan.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(LanPolicy.address(host), port), 650);
            append("TCP " + host + ":" + port + " OPEN (not protocol verification)");
            return true;
        } catch (IOException e) {
            append("TCP " + port + " unavailable/filtered: " + e.getClass().getSimpleName());
            return false;
        }
    }

    String line(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < 256) {
            int c;
            try { c = input.read(); }
            catch (SocketTimeoutException e) { if (out.size() == 0) throw e; else break; }
            if (c < 0) break;
            if (c == '\r' || c == '\n') { if (out.size() > 0) break; else continue; }
            out.write(c);
        }
        return out.toString(StandardCharsets.US_ASCII.name()).trim();
    }

    String tcp(String host, String operation, String argument) throws Exception {
        requireLocal(host);
        if (!"????".equals(argument)) throw new IOException("Legacy write commands disabled in research build.");
        String command = LanPolicy.frame(operation, argument);
        try (Socket socket = lan.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(LanPolicy.address(host), 10002), 1500);
            socket.setSoTimeout(200);
            String greeting = "";
            try { greeting = line(socket.getInputStream()); }
            catch (SocketTimeoutException ignored) { }
            if (!greeting.isEmpty()) {
                append("TCP banner: " + greeting);
                throw new IOException("Greeting/authentication detected; no credentials or commands sent.");
            }
            socket.setSoTimeout(2200);
            append("SEND TCP " + host + ":10002 " + command.replace("\r", "\\r"));
            socket.getOutputStream().write(command.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = line(socket.getInputStream());
            append("RECV TCP [" + response + "]");
            if (response.isEmpty()) throw new IOException("No protocol response.");
            return response;
        }
    }

    static int parseNumber(String raw, int min, int max) throws IOException {
        if (raw == null || !raw.trim().matches("[0-9]{1,5}"))
            throw new IOException("Expected numeric response; got " + raw);
        int n = Integer.parseInt(raw.trim());
        if (n < min || n > max) throw new IOException("Response outside supported range: " + n);
        return n;
    }

    void readScpd(Device d, Service s) throws Exception {
        if (s.scpd.isEmpty()) throw new IOException("No advertised SCPD.");
        String raw = request(d.ip, s.scpd, null, null);
        append("SCPD XML " + s.scpd + "\n" + raw);
        Document doc = xml(raw);
        if (!"scpd".equals(doc.getDocumentElement().getLocalName()))
            throw new IOException("Endpoint is not an SCPD document.");
        s.actions.clear();
        NodeList actions = doc.getElementsByTagNameNS("*", "action");
        for (int i = 0; i < actions.getLength(); i++)
            s.actions.add(child((Element) actions.item(i), "name"));
        NodeList variables = doc.getElementsByTagNameNS("*", "stateVariable");
        for (int i = 0; i < variables.getLength(); i++) {
            Element variable = (Element) variables.item(i);
            if (!s.type.contains("RenderingControl") || !"Volume".equals(child(variable, "name"))) continue;
            NodeList ranges = variable.getElementsByTagNameNS("*", "allowedValueRange");
            if (ranges.getLength() > 0) {
                Element range = (Element) ranges.item(0);
                int min = parseNumber(child(range, "minimum"), 0, 100);
                int max = parseNumber(child(range, "maximum"), min, 100);
                s.minimum = min;
                s.maximum = max;
            }
        }
        s.evented.clear();
        for (int i = 0; i < variables.getLength(); i++) {
            Element variable = (Element) variables.item(i);
            if ("yes".equalsIgnoreCase(variable.getAttribute("sendEvents")))
                s.evented.add(child(variable, "name"));
        }
        s.scpdRead = true;
        append("Advertised actions " + s.type + ": " + s.actions + "; evented=" + s.evented);
        if (s.type.equals(AwoxResearch.TYPE)) {
            for (int i = 0; i < actions.getLength(); i++) {
                Element action = (Element) actions.item(i);
                NodeList arguments = action.getElementsByTagNameNS("*", "argument");
                StringBuilder schema = new StringBuilder(child(action, "name"));
                for (int j = 0; j < arguments.getLength(); j++) {
                    Element arg = (Element) arguments.item(j);
                    schema.append("\n  ").append(child(arg, "direction")).append(" ")
                            .append(child(arg, "name")).append(" -> ")
                            .append(child(arg, "relatedStateVariable"));
                }
                append("X_ServiceManager schema (not ID values): " + schema);
            }
            analyze("X_ServiceManager SCPD", raw);
            managerState = "SCPD read. Actions=" + s.actions + "; evented=" + s.evented
                    + "\nNo GetProperty IDs identified; no GetProperty, PerformAction or SetProperty sent.";
        }
    }

    Document soap(Device d, Service s, String action, String arguments) throws Exception {
        if (!s.actions.contains(action)) throw new IOException("Action not advertised: " + action);
        if (!s.type.matches("urn:schemas-upnp-org:service:(RenderingControl|AVTransport|ConnectionManager):[0-9]+"))
            throw new IOException("Unrecognized service namespace.");
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + s.type + "\">" + arguments
                + "</u:" + action + "></s:Body></s:Envelope>";
        Document doc = xml(request(d.ip, s.control, s.type + "#" + action, body));
        if (doc.getElementsByTagNameNS("*", "Fault").getLength() > 0)
            throw new IOException("SOAP Fault: " + doc.getDocumentElement().getTextContent());
        if (doc.getElementsByTagNameNS(s.type, action + "Response").getLength() == 0)
            throw new IOException("No matching SOAP action response.");
        append("RESPONDING UPnP interface: " + s.type + " / " + action);
        return doc;
    }

    String channelArgs() { return "<InstanceID>0</InstanceID><Channel>Master</Channel>"; }

    void safeSoap(Device d, Service s) throws Exception {
        if (s.type.matches("urn:schemas-upnp-org:service:RenderingControl:[0-9]+")) {
            append(AwoxResearch.RENDERER_LABEL);
            if (s.actions.contains("GetVolume")) {
                try {
                    int n = parseNumber(text(soap(d, s, "GetVolume", channelArgs()), "CurrentVolume"), 0, 100);
                    rendererState = AwoxResearch.RENDERER_LABEL + "\nRenderer volume=" + n;
                    if (protocol != LEGACY) protocol = UPNP;
                    append("DLNA renderer volume=" + n + "; NOT the physical TV volume.");
                } catch (Exception e) { append("Renderer GetVolume: " + e.getMessage()); }
            }
            if (s.actions.contains("GetMute")) {
                try {
                    boolean muted = parseBoolean(text(soap(d, s, "GetMute", channelArgs()), "CurrentMute"));
                    rendererState += "\nRenderer mute=" + muted + " (not verified physical TV mute).";
                    append("DLNA renderer mute=" + muted + "; no TV key unlocked.");
                } catch (Exception e) { append("Renderer GetMute: " + e.getMessage()); }
            }
        } else if (s.type.matches("urn:schemas-upnp-org:service:AVTransport:[0-9]+")
                && s.actions.contains("GetTransportInfo"))
            soap(d, s, "GetTransportInfo", "<InstanceID>0</InstanceID>");
        else if (s.type.matches("urn:schemas-upnp-org:service:ConnectionManager:[0-9]+")
                && s.actions.contains("GetProtocolInfo"))
            soap(d, s, "GetProtocolInfo", "");
        else if (s.type.equals(AwoxResearch.TYPE))
            append("X_ServiceManager kept separate. Unknown ID values are never queried or invoked.");
        powerReady = volumeReady = muteReady = false;
    }

    static boolean parseBoolean(String raw) throws IOException {
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) return true;
        if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) return false;
        throw new IOException("Invalid boolean response: " + raw);
    }



    void sendKey(String key) throws Exception {
        throw new IOException("All normal TV keys are locked pending identified mappings "
                + "and actual physical-TV evidence. No command sent.");
    }

    void share() {
        localJob(() -> {
            File snapshot = new File(getCacheDir(), "report-" + UUID.randomUUID().toString() + ".zip");
            try (OutputStream out = new FileOutputStream(snapshot)) { captures.zip(out, report()); }
            ui(() -> {
                Uri uri = Uri.parse("content://" + getPackageName() + ".diagnostics/" + snapshot.getName());
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_SUBJECT, "Sharp LC-40UI7352E AwoX diagnostic");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setClipData(ClipData.newRawUri("Diagnostic archive", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(intent, "Share logs and captures")); }
                catch (Exception e) { append("Share failed: " + e.getMessage()); }
            });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri destination = data.getData();
        localJob(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("Cannot open export destination.");
                captures.zip(out, report());
                append("Diagnostic ZIP exported with raw captures, metadata, ID candidates and log.");
            }
        });
    }

    Service manager(Device d) {
        for (Service service : d.services) if (AwoxResearch.TYPE.equals(service.type)) return service;
        return null;
    }

    boolean confirmSelection(Device d) {
        if (d == null) return false;
        if (!d.sharp() && !confirmTarget.isChecked()) {
            Toast.makeText(this, "Confirm this IP is your Sharp TV first.", Toast.LENGTH_LONG).show();
            return false;
        }
        d.userConfirmed = true;
        return true;
    }

    void startResearch() {
        Device d = selected;
        if (busy.get() || !confirmSelection(d)) return;
        try {
            String port = portInput.getText().toString().trim();
            d.reportedPort = parseNumber(port, 1, 65535);
        } catch (Exception e) {
            Toast.makeText(this, "Enter the reported/discovered port, 1–65535.", Toast.LENGTH_LONG).show();
            return;
        }
        job(() -> {
            captures.begin("SharpLanDiagnostic 0.3 AwoX research\nSelected confirmed TV: "
                    + d.ip + "\nUser-entered reported port: " + d.reportedPort
                    + "\nNo physical TV key is verified.\n");
            inspectAwox(d);
        });
    }

    void capture(String kind, String source, int code, Map<String, List<String>> headers,
                 byte[] body, boolean truncated) {
        try {
            String saved = captures.capture(kind, source, code, headers, body, truncated);
            append("RAW CAPTURE " + saved + " source=" + source + " bytes=" + body.length
                    + (truncated ? " [TRUNCATED AT SAFETY LIMIT]" : ""));
        } catch (Exception e) { append("Capture could not be saved: " + e.getMessage()); }
    }

    void analyze(String source, String body) {
        List<String> findings = AwoxResearch.findings(source, body);
        try { captures.findings(findings); }
        catch (Exception e) { append("Findings save: " + e.getMessage()); }
        int ids = 0;
        for (String finding : findings) {
            if (finding.startsWith("UNVERIFIED")) ids++;
            append(finding);
        }
        managerState = "X_ServiceManager research: " + source + "\n"
                + ids + " textual ID candidates; " + findings.size() + " contexts captured."
                + "\nCandidates are unverified and never sent. No physical TV key mapping proven.";
    }

    static class ResearchResponse {
        URL url;
        int code;
        byte[] bytes;
        String body, location = "";
        boolean truncated;
    }

    ResearchResponse researchGet(Device d, URL url, int limit) throws Exception {
        requireLocal(d.ip);
        LanPolicy.endpoint(null, url.toString(), d.ip);
        append("GET-ONLY RESEARCH " + url);
        HttpURLConnection connection = (HttpURLConnection) lan.openConnection(url, java.net.Proxy.NO_PROXY);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(2500);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            ResearchResponse response = new ResearchResponse();
            response.url = url;
            response.code = connection.getResponseCode();
            response.location = connection.getHeaderField("Location");
            Map<String, List<String>> headers = new LinkedHashMap<>(connection.getHeaderFields());
            InputStream raw = response.code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (raw != null) {
                InputStream decoded = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                        ? new java.util.zip.GZIPInputStream(raw) : raw;
                if (decoded != raw) headers.put("X-Diagnostic-Decoded-Content-Encoding", Arrays.asList("gzip"));
                try (InputStream in = decoded) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        int remaining = limit - bytes.size();
                        bytes.write(buffer, 0, Math.min(count, remaining));
                        if (count > remaining || bytes.size() == limit) {
                            response.truncated = count > remaining || in.read() != -1;
                            break;
                        }
                    }
                }
            }
            response.bytes = bytes.toByteArray();
            response.body = new String(response.bytes, StandardCharsets.UTF_8);
            capture("web", url.toString(), response.code, headers, response.bytes, response.truncated);
            append("WEB HTTP " + response.code + " " + url + "\n"
                    + response.body.substring(0, Math.min(3000, response.body.length())));
            return response;
        } finally { connection.disconnect(); }
    }

    void inspectAwox(Device d) throws Exception {
        requireLocal(d.ip);
        if (!d.userConfirmed && !d.sharp()) throw new IOException("Selected TV is not confirmed.");
        researchTarget = null;
        Service service = manager(d);
        URL origin;
        // The explicit port field can override a stale DHCP/reboot-era advertised port.
        if (!d.presentation.isEmpty()
                && AwoxResearch.port(new URL(d.presentation)) == d.reportedPort)
            origin = LanPolicy.endpoint(null, d.presentation, d.ip);
        else origin = new URL("http", d.ip, d.reportedPort, "/web");
        origin = AwoxResearch.resource(origin, origin, origin.toString());
        append("=== AwoX GET research origin " + origin + " ===\n"
                + "Known endpoints are user-reported or advertised, not a guessed key API.");
        if (service == null || service.scpd.isEmpty()
                || !AwoxResearch.sameOrigin(origin, new URL(service.scpd))) {
            service = new Service();
            service.type = AwoxResearch.TYPE;
            service.id = "urn:schemas-awox-com:serviceId:X_ServiceManager";
            service.scpd = new URL(origin, "/MediaRenderer_X_ServiceManager/scpd.xml").toString();
            service.control = new URL(origin, "/MediaRenderer_X_ServiceManager/control").toString();
            service.event = new URL(origin, "/MediaRenderer_X_ServiceManager/event").toString();
            d.services.removeIf(item -> item.type.equals(AwoxResearch.TYPE));
            d.services.add(service);
            append("Using exact X_ServiceManager endpoint paths from your real-TV report. "
                    + "GET validates the SCPD; control endpoint will NOT be invoked.");
        }
        // Fetch the reported presentation page first. Never instantiate a WebView.
        try { crawlWeb(d, origin); }
        catch (Exception e) { append("/web inspection: " + e.getMessage()); }
        try {
            readScpd(d, service);
            if (!service.event.isEmpty()
                    && AwoxResearch.sameOrigin(origin, new URL(service.event))) {
                researchTarget = d;
                managerState += "\nSCPD GET completed. Event observation is available."
                        + "\nPress the physical remote during the 30-second observation.";
            }
        } catch (Exception e) {
            managerState = "X_ServiceManager SCPD not validated: " + e.getMessage()
                    + "\nRaw web captures retained. All TV keys locked.";
            append(managerState);
        }
        connection = "AwoX GET inspection finished. Export the ZIP to inspect full resources.";
        append(connection);
    }

    void crawlWeb(Device d, URL origin) throws Exception {
        class Page {
            final URL url; final int depth;
            Page(URL url, int depth) { this.url = url; this.depth = depth; }
        }
        ArrayDeque<Page> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new Page(origin, 0));
        int remaining = 4 * 1024 * 1024;
        while (!queue.isEmpty() && visited.size() < 16 && remaining > 0
                && !Thread.currentThread().isInterrupted()) {
            Page next = queue.removeFirst();
            URL url;
            try { url = AwoxResearch.resource(origin, next.url, next.url.toString()); }
            catch (Exception e) { append("Skipped unsafe resource: " + e.getMessage()); continue; }
            if (!visited.add(url.toString())) continue;
            try {
                ResearchResponse response = researchGet(d, url, Math.min(524288, remaining));
                remaining -= response.bytes.length;
                analyze(url.toString(), response.body);
                if (response.code >= 300 && response.code < 400 && response.location != null) {
                    if (next.depth < 3) {
                        try {
                            queue.addFirst(new Page(AwoxResearch.resource(
                                    origin, url, response.location), next.depth + 1));
                        } catch (Exception e) { append("Rejected web redirect: " + e.getMessage()); }
                    }
                } else if (response.code >= 200 && response.code < 300 && next.depth < 2) {
                    for (URL reference : AwoxResearch.references(origin, url, response.body))
                        if (queue.size() < 64 && !visited.contains(reference.toString()))
                            queue.addLast(new Page(reference, next.depth + 1));
                }
            } catch (Exception e) { append("Resource GET failed " + url + ": " + e.getMessage()); }
        }
        append("Web inspection finished: " + visited.size() + " bounded resources. "
                + "No JS execution, forms, unknown queries or action-like links. "
                + "Dynamic non-static endpoints were not guessed.");
    }

    void startEvents() {
        Device d = selected;
        if (busy.get() || !confirmSelection(d)) return;
        job(() -> {
            Service service = manager(d);
            if (researchTarget != d || service == null || !service.scpdRead || service.event.isEmpty())
                throw new IOException("Run GET-only AwoX inspection on this network first.");
            requireLocal(d.ip);
            URL event = LanPolicy.endpoint(null, service.event, d.ip);
            if (!AwoxResearch.sameOrigin(event, new URL(service.scpd)))
                throw new IOException("Event URL differs from inspected ServiceManager origin.");
            String local = null;
            for (LinkAddress address : localAddresses)
                if (LanPolicy.sameSubnet(d.ip, address.getAddress().getHostAddress(), address.getPrefixLength())) {
                    local = address.getAddress().getHostAddress(); break;
                }
            if (local == null) throw new IOException("No matching local callback address.");
            managerState = "Observing ServiceManager events for up to 30 seconds. "
                    + "Use the PHYSICAL TV remote for Volume, Mute, navigation and Input."
                    + "\nEvent changes are correlation evidence, not a verified command mapping.";
            append(managerState);
            observer = new GenaObserver(lan.getSocketFactory(), d.ip, local, event, new GenaObserver.Sink() {
                @Override public void log(String message) {
                    append(message);
                    capture("gena-transport", event.toString(), 0, Collections.emptyMap(),
                            message.getBytes(StandardCharsets.UTF_8), false);
                }
                @Override public void event(GenaProtocol.Message message) {
                    Map<String, List<String>> headers = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : message.headers.entrySet())
                        headers.put(entry.getKey(), Arrays.asList(entry.getValue()));
                    capture("event", event.toString(), 200, headers, message.body, false);
                    String raw = new String(message.body, StandardCharsets.UTF_8);
                    append("VALIDATED EVENT SID=" + message.headers.get("sid")
                            + " SEQ=" + message.headers.get("seq") + "\n" + raw);
                    analyze("X_ServiceManager event", raw);
                    try {
                        Document doc = xml(raw);
                        NodeList properties = doc.getElementsByTagNameNS("*", "property");
                        for (int i = 0; i < properties.getLength(); i++) {
                            Node node = properties.item(i).getFirstChild();
                            while (node != null) {
                                if (node instanceof Element) {
                                    String name = node.getLocalName(), value = node.getTextContent();
                                    append("EVENT PROPERTY " + name + " = "
                                            + value.substring(0, Math.min(12000, value.length())));
                                    capture("event-decoded", event + "#" + name, 200,
                                            Collections.emptyMap(), value.getBytes(StandardCharsets.UTF_8), false);
                                    analyze("Event property " + name, value);
                                }
                                node = node.getNextSibling();
                            }
                        }
                    } catch (Exception e) { append("Event XML retained but could not parse: " + e.getMessage()); }
                }
            });
            ui(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            try { observer.observe(); }
            finally {
                observer = null;
                ui(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
            managerState += "\nObservation finished. Export logs + captures ZIP; all TV keys remain locked.";
            append(managerState);
        });
    }

    @Override protected void onDestroy() {
        GenaObserver active = observer;
        if (active != null) active.cancel();
        DatagramSocket socket = discoverySocket;
        if (socket != null) socket.close();
        ex.shutdownNow();
        super.onDestroy();
    }
}
