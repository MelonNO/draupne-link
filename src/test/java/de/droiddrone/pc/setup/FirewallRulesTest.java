package de.droiddrone.pc.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two inbound rules the setup makes in Windows' firewall: both let packets in from this PC's own networks and
 * from Tailscale's addresses, not from anywhere - the link port's (name lookup, spectators, a phone's check-in) as the
 * phone download's always did. And a rule from before, open to any address, counts as not made: the setup makes it
 * again. Only the script's text and what netsh's answer is read as; this PC's firewall is not touched.
 */
class FirewallRulesTest {
    private static String addLine(String script, String rule) {
        List<String> lines = script.lines().filter(l -> l.contains(" add rule name=" + rule + " ")).toList();
        assertEquals(1, lines.size(), "one line adds " + rule + ": " + script);
        return lines.get(0);
    }

    @Test
    void bothRulesLetInThisPcsNetworksAndTailscaleOnly() {
        String script = WindowsSetup.firewallScript(6286, 8765);
        String link = addLine(script, WindowsSetup.LINK_RULE), apk = addLine(script, WindowsSetup.APK_RULE);
        assertTrue(link.contains(" dir=in action=allow protocol=UDP localport=6286 "), link);
        assertTrue(apk.contains(" dir=in action=allow protocol=TCP localport=8765 "), apk);
        for (String line : new String[]{link, apk}) {
            assertTrue(line.contains(" remoteip=LocalSubnet,100.64.0.0/10 "), "from this PC's own networks and Tailscale's range: " + line);
            assertTrue(line.endsWith(" profile=any"), line);
            assertFalse(line.toLowerCase().contains("remoteip=any"), line);
        }
        // each is taken away before it is made: an older, wider one of the same name does not stay beside the new one
        for (String rule : new String[]{WindowsSetup.LINK_RULE, WindowsSetup.APK_RULE}) {
            int delete = script.indexOf(" delete rule name=" + rule + " "), add = script.indexOf(" add rule name=" + rule + " ");
            assertTrue(delete >= 0 && delete < add, rule + " deleted, then added: " + script);
        }
    }

    /** netsh's answer to "show rule name=...", as an English Windows writes it. */
    private static String shown(String name, String remoteIp, String protocol, int port) {
        return "\r\nRule Name:                            " + name + "\r\n"
                + "----------------------------------------------------------------------\r\n"
                + "Enabled:                              Yes\r\n"
                + "Direction:                            In\r\n"
                + "Profiles:                             Domain,Private,Public\r\n"
                + "Grouping:                             \r\n"
                + "LocalIP:                              Any\r\n"
                + "RemoteIP:                             " + remoteIp + "\r\n"
                + "Protocol:                             " + protocol + "\r\n"
                + "LocalPort:                            " + port + "\r\n"
                + "RemotePort:                           Any\r\n"
                + "Edge traversal:                       No\r\n"
                + "Action:                               Allow\r\n";
    }

    @Test
    void aRuleOpenToAnyAddressCountsAsNotMade() {
        String link = WindowsSetup.LINK_RULE, apk = WindowsSetup.APK_RULE;
        assertTrue(WindowsSetup.ruleReady(apk, new Shell.Result(0, shown(apk, "LocalSubnet,100.64.0.0/10", "TCP", 8765) + "Ok.\r\n")));
        assertTrue(WindowsSetup.ruleReady(link, new Shell.Result(0, shown(link, "LocalSubnet,100.64.0.0/10", "UDP", 6286) + "Ok.\r\n")));
        // the link port's rule as every install up to now made it
        assertFalse(WindowsSetup.ruleReady(link, new Shell.Result(0, shown(link, "Any", "UDP", 6286) + "Ok.\r\n")), "open to any address: to be made again");
        // the same on a Windows in another language: its words differ, the range's numbers do not
        String german = shown(link, "Beliebig", "UDP", 6286).replace("RemoteIP:", "Remote-IP:").replace("Rule Name:", "Regelname:");
        assertFalse(WindowsSetup.ruleReady(link, new Shell.Result(0, german + "OK.\r\n")));
        assertTrue(WindowsSetup.ruleReady(link, new Shell.Result(0, german.replace("Beliebig", "LokalesSubnetz,100.64.0.0/10") + "OK.\r\n")));
        // none of that name
        assertFalse(WindowsSetup.ruleReady(link, new Shell.Result(1, "\r\nNo rules match the specified criteria.\r\n")));
        assertFalse(WindowsSetup.ruleReady(link, new Shell.Result(0, "")));
        assertFalse(WindowsSetup.ruleReady(link, new Shell.Result(-1, "java.io.IOException: netsh: not started")));
        // two of the name, one of them still open to any address
        assertFalse(WindowsSetup.ruleReady(link, new Shell.Result(0, shown(link, "LocalSubnet,100.64.0.0/10", "UDP", 6286)
                + shown(link, "Any", "UDP", 6286) + "Ok.\r\n")), "every rule of the name must be the narrow one");
        assertTrue(WindowsSetup.ruleReady(link, new Shell.Result(0, shown(link, "LocalSubnet,100.64.0.0/10", "UDP", 6286)
                + shown(link, "LocalSubnet,100.64.0.0/10", "UDP", 6286) + "Ok.\r\n")));
    }
}
