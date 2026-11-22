package de.droiddrone.pc.gui;

import de.droiddrone.pc.NameService;
import de.droiddrone.pc.Names;
import de.droiddrone.pc.PcConfig;
import de.droiddrone.pc.setup.Tailscale;
import de.droiddrone.testing.Waits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Labels, list and table cells, tooltips and message boxes that show text from other machines - a phone's device and
 * version text from its name reply, a host name as Tailscale has it, a release's tag, the flight controller's mode
 * names - show it as text: its characters as they came, none of them read as markup, and nothing asked of any
 * address because of it. Each place is laid out and painted as its window would, never shown on screen; a web server
 * of the test's own, on loopback, hears whoever asks.
 */
class TextFromOtherMachinesTest {
    private ServerSocket server;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<String> notAsText = new CopyOnWriteArrayList<>();
    /** A text that is a picture from the test's server to whoever lays it out as HTML; 47 characters, a name reply's device text takes 64. */
    private String markup;

    @BeforeEach
    void listen() throws Exception {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(2000);
                    String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    if (line != null) asked.add(line); // "GET /text.png HTTP/1.1"
                    s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {
                    // closed at the test's end, or a caller that went away
                }
            }
        }, "text-test-server");
        t.setDaemon(true);
        t.start();
        markup = "<html><img src=http://127.0.0.1:" + server.getLocalPort() + "/text.png>";
    }

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    // ------------------------------------------------------------------ the name map and the LINK card

    @Test
    void theNameMapShowsADevicesAndAHostsTextAsText() throws Exception {
        PcConfig config = PcConfig.defaults();
        Names names = new Names(config, null);
        names.seen(phoneReply(markup, "2.3.0-pc.55"), "100.64.0.5", markup);
        NameService service = new NameService(config, names, () -> null, 1, false, m -> { }, l -> { });
        onSwing(() -> {
            Theme.install();
            NameMapDialog d = new NameMapDialog(null, names, service, config, m -> { }, () -> { });
            try {
                d.addNotify(); // displayable as when open
                d.fill();
                JTable table = all(d, JTable.class).get(0);
                assertEquals(1, table.getRowCount());
                for (int col = 0; col < table.getColumnCount(); col++) {
                    Component cell = table.prepareRenderer(table.getCellRenderer(0, col), 0, col);
                    cell.setSize(300, table.getRowHeight());
                    paint(cell);
                    if (col == 3 || col == 4) checkAsText(table.getColumnName(col), cell);
                }
            } finally {
                d.dispose();
            }
        });
        assertAllAsTextAndNothingAsked();
    }

    @Test
    void theLineUnderThePhoneListShowsTheChosenPhonesTextAsText() throws Exception {
        Names names = new Names(PcConfig.defaults(), null);
        names.seen(phoneReply(markup, markup), "100.64.0.5", markup);
        Names.Entry e = names.get("MobileAlpha");
        onSwing(() -> {
            JLabel hint = new JLabel(MainWindow.hintHtml(e.detail() + "  -  " + e.ip()));
            paint(hint);
            checkAsText("the hint", hint);
        });
        assertAllAsTextAndNothingAsked();
    }

    // ------------------------------------------------------------------ Phone setup

    @Test
    void phoneSetupShowsAHostNameAndAPhonesTextAsText() throws Exception {
        Tailscale.Peer peer = new Tailscale.Peer(markup, "android", true, "100.64.0.5", "", "");
        Names names = new Names(PcConfig.defaults(), null);
        names.seen(phoneReply(markup, markup), "100.64.0.5", markup);
        Names.Entry e = names.get("MobileAlpha");
        onSwing(() -> {
            Theme.install();
            for (String line : new String[]{PhoneGuideWizard.peerLine(peer), PhoneGuideWizard.appOpenLine(e), PhoneGuideWizard.phoneLine(e, System.currentTimeMillis())}) {
                CheckRow row = new CheckRow("Phone").set(CheckRow.State.OK, line);
                row.setSize(700, 80);
                layout(row);
                paint(row);
                checkAsText(line, all(row, JTextComponent.class).get(0));
            }
            for (String choice : new String[]{PhoneGuideWizard.peerChoice(peer), PhoneGuideWizard.phoneChoice(e)}) {
                Component cell = new DefaultListCellRenderer().getListCellRendererComponent(new JList<>(), choice, 0, false, false);
                cell.setSize(400, 24);
                paint(cell);
                if (choice.startsWith("<")) checkAsText(choice, cell); // the host name comes first in its line
            }
        });
        assertAllAsTextAndNothingAsked();
    }

    // ------------------------------------------------------------------ the update

    @Test
    void theUpdateQuestionsShowTheReleasesTagAsText() throws Exception {
        onSwing(() -> {
            Theme.install();
            for (String question : new String[]{MainWindow.updateQuestion("9.9.9" + markup), MainWindow.updateFailedQuestion(markup)}) {
                JDialog d = Dialogs.dialog(null, new JOptionPane(question, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION), "Update");
                try {
                    paint(d.getRootPane());
                    boolean found = false;
                    for (JLabel l : all(d, JLabel.class)) found |= shown(l).contains("<img src=http://127.0.0.1:");
                    if (!found) notAsText.add("the update question does not show the tag as it came");
                } finally {
                    d.dispose();
                }
            }
        });
        assertAllAsTextAndNothingAsked();
    }

    // ------------------------------------------------------------------ RC CHECK

    @Test
    void rcCheckShowsTheFlightControllersModeNamesAsText() throws Exception {
        onSwing(() -> {
            Theme.install();
            CheckRow row = new CheckRow("Modes").set(CheckRow.State.INFO, RcCheckDialog.modesLine(List.of("ARM", markup)));
            row.setSize(700, 80);
            layout(row);
            paint(row);
            checkAsText("the modes", all(row, JTextComponent.class).get(0));
        });
        assertAllAsTextAndNothingAsked();
    }

    // ------------------------------------------------------------------ the two ways a text is put into a place

    @Test
    void aTextInOneOfTheAppsOwnHtmlLinesShowsEveryCharacterItHas() throws Exception {
        String odd = "R&D <b>Pixel</b> it's \"8\" 5 > 3 &amp; " + markup;
        onSwing(() -> {
            Theme.install();
            JLabel label = new JLabel("<html><b>Phone</b> " + AsText.html(odd) + "</html>");
            paint(label);
            assertEquals("Phone " + odd, shown(label).strip(), "in a label");
            WrappingText wrapping = new WrappingText("<b>Phone</b> " + AsText.html(odd), Theme.font(java.awt.Font.PLAIN, 13f), Theme.TEXT);
            wrapping.setSize(600, 60);
            paint(wrapping);
            assertEquals("Phone " + odd, shown(wrapping).strip(), "in a wrapping line");
        });
        assertEquals("", AsText.html(null));
        String usual = "Pixel 8 - 2.3.0-pc.55";
        assertSame(usual, AsText.html(usual), "a text without such characters is handed back as it is");
        assertAllAsTextAndNothingAsked();
    }

    @Test
    void aTextThatIsALabelsOwnShowsAsItIsHoweverItBegins() throws Exception {
        onSwing(() -> {
            Theme.install();
            for (String begins : new String[]{markup, markup.replace("<html>", "<HTML>"), markup.replace("<html>", "<HtMl>")}) {
                JLabel label = new JLabel(AsText.label(begins));
                label.setToolTipText(AsText.label(begins));
                paint(label);
                assertNull(label.getClientProperty(BasicHTML.propertyKey), "not laid out as HTML: " + begins);
                assertEquals(begins, label.getText().strip(), "every character is still there");
                javax.swing.JToolTip tip = label.createToolTip();
                tip.setTipText(label.getToolTipText());
                paint(tip);
                assertNull(tip.getClientProperty(BasicHTML.propertyKey), "its tooltip neither");
            }
            // a message box makes a label of each line of its message
            String message = "Two devices answer as MobileAlpha.\n" + markup + "\r\n" + markup + "\nRename one.";
            JDialog d = Dialogs.dialog(null, new JOptionPane(AsText.label(message), JOptionPane.WARNING_MESSAGE), "Cannot connect");
            try {
                paint(d.getRootPane());
                int lines = 0;
                for (JLabel l : all(d, JLabel.class)) {
                    assertNull(l.getClientProperty(BasicHTML.propertyKey), "a line laid out as HTML: " + l.getText());
                    if (shown(l).contains("<img src=http://127.0.0.1:")) lines++;
                }
                assertEquals(2, lines, "both lines show as they came");
            } finally {
                d.dispose();
            }
        });
        assertSame("Pixel 8 <5G>", AsText.label("Pixel 8 <5G>"), "a text Swing shows as it is anyway is handed back as it is");
        assertEquals("a <html> later in the line", AsText.label("a <html> later in the line"));
        assertNull(AsText.label(null));
        assertAllAsTextAndNothingAsked();
    }

    // ------------------------------------------------------------------ helpers

    /** A phone's name reply as it comes off the network: built, then read back the way the name service reads it. */
    private static Names.Reply phoneReply(String device, String version) {
        byte[] packet = Names.reply("DD", Names.Kind.PHONE, "MobileAlpha", device, version);
        Names.Reply reply = Names.parseReply(packet, packet.length, "DD");
        assertNotNull(reply);
        assertEquals(device, reply.device(), "the device text comes through whole");
        return reply;
    }

    private interface SwingWork {
        void run() throws Exception;
    }

    private static void onSwing(SwingWork work) throws Exception {
        Throwable[] failed = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failed[0] = t;
            }
        });
        if (failed[0] instanceof Error e) throw e;
        if (failed[0] instanceof Exception e) throw e;
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component x : k.getComponents()) layout(x);
    }

    /** Painted as on screen: what a text laid out as HTML would fetch is asked for here. */
    private static void paint(Component c) {
        if (c.getWidth() <= 0 || c.getHeight() <= 0) {
            Dimension d = c.getPreferredSize();
            c.setSize(Math.max(1, d.width), Math.max(1, d.height));
        }
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
    }

    /** The characters a component shows: its document's text when it lays its text out as HTML, else its own text. */
    private static String shown(Component c) {
        try {
            if (c instanceof JComponent j && j.getClientProperty(BasicHTML.propertyKey) instanceof View v) {
                Document d = v.getDocument();
                return d.getText(0, d.getLength());
            }
            if (c instanceof JTextComponent t) {
                Document d = t.getDocument();
                return d.getText(0, d.getLength());
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        String text = c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText() : "";
        return text == null ? "" : text;
    }

    /** Noted, not failed at once: the test's end says both what was not shown as text and what was asked for. */
    private void checkAsText(String where, Component c) {
        String shown = shown(c);
        if (!shown.contains("<img src=http://127.0.0.1:" + server.getLocalPort() + "/text.png>")) {
            notAsText.add(where + " shows \"" + shown.strip() + "\"");
        }
    }

    /**
     * Every place showed the text as it came, and nobody asked the test's server for the text's picture. That the
     * server would have heard it is shown first: a label of the test's own that does lay a picture out as HTML asks
     * within moments.
     */
    private void assertAllAsTextAndNothingAsked() throws Exception {
        JLabel control = new JLabel("<html><img src=http://127.0.0.1:" + server.getLocalPort() + "/control.png>");
        SwingUtilities.invokeAndWait(() -> paint(control));
        Waits.waitFor(() -> asked.stream().anyMatch(l -> l.contains("/control.png")), 10_000, "the control picture to be asked for");
        boolean fetched = Waits.until(() -> asked.stream().anyMatch(l -> l.contains("/text.png")), 300);
        assertEquals(List.of(), notAsText, "places that did not show the text as it came" + (fetched ? "; asked of the server: " + asked : ""));
        assertFalse(fetched, "asked of the server: " + asked);
    }

    private static <T> List<T> all(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) out.add(type.cast(c));
        if (c instanceof Container k) for (Component x : k.getComponents()) collect(x, type, out);
    }
}
