package de.droiddrone.pc;

/** How the PC reaches the Flight phone. The wire protocol is the same in every mode. */
public enum LinkMode {
    PHONE("phone", "Phone (Wi-Fi or VPN)",
            "Flight app: Direct connection. Address: the phone's IP - its Tailscale/ZeroTier IP when the phone is on 4G."),
    RELAY("relay", "Relay server",
            "Flight app: Connect over server, with the same address and key. Address: the relay server's public IP."),
    HOST_RELAY("host", "Host relay on this PC",
            "Flight app: Connect over server, address = this home's public IP. Forward UDP %d on the router to this PC.");

    public final String key;
    public final String title;
    private final String hint;

    LinkMode(String key, String title, String hint) {
        this.key = key;
        this.title = title;
        this.hint = hint;
    }

    public String hint(int port) {
        return hint.formatted(port);
    }

    public static LinkMode fromKey(String key) {
        for (LinkMode mode : values()) {
            if (mode.key.equalsIgnoreCase(key)) return mode;
        }
        return PHONE;
    }

    @Override
    public String toString() {
        return title;
    }
}
