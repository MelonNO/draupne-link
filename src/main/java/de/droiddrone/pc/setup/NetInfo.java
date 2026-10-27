package de.droiddrone.pc.setup;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** This PC's addresses. */
public final class NetInfo {
    private NetInfo() {
    }

    public static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    /** Private IPv4 addresses on the local network (192.168.x.x etc.). */
    public static List<String> lanAddresses() {
        return addresses(false);
    }

    /** Tailscale IPv4 addresses (100.64.0.0/10). */
    public static List<String> tailscaleAddresses() {
        return addresses(true);
    }

    private static List<String> addresses(boolean tailscale) {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (tailscale ? isTailscale(a) : a.isSiteLocalAddress()) found.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) {
        }
        return found;
    }

    /** Asks api.ipify.org which address this connection has on the internet. Only called when the user asks. */
    public static String publicIp() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return response.body().trim();
    }
}
