package de.droiddrone.pc.gui;

import de.droiddrone.pc.flights.FlightSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PREVIOUS FLIGHTS' summary strip: the chosen flight's figures as FlightSummary gives them, there without playing;
 * what the flight's files don't tell is "-" and dimmed; and the whole window - the strip, the note, the bar with its
 * marks lane, the two rows of buttons - fits a small screen with nothing cut off. Pictures to $DEBRIEF_SHOTS.
 */
class DebriefStripTest {
    @TempDir
    Path dir;

    private static List<String> texts(JLabel[] labels) {
        return java.util.Arrays.stream(labels).map(JLabel::getText).toList();
    }

    @Test
    void theChosenFlightsFiguresAreThereWithoutPlaying() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Play", d.play.getText(), "nothing plays");
                List<FlightSummary.Figure> figures = FlightSummary.of(d.data).figures();
                assertEquals(figures.stream().map(FlightSummary.Figure::label).toList(), texts(d.summaryStrip.labels));
                assertEquals(figures.stream().map(FlightSummary.Figure::value).toList(), texts(d.summaryStrip.values));
                assertEquals(List.of("DURATION", "ARMED", "DISTANCE", "FURTHEST", "MAX HEIGHT", "MIN CELL", "LINK LOSSES", "FAILSAFES", "WORST PING"),
                        texts(d.summaryStrip.labels));
                assertEquals("3:00", d.summaryStrip.values[0].getText());
                assertEquals("90 M", d.summaryStrip.values[4].getText());
                assertEquals("3.56 V", d.summaryStrip.values[5].getText());
                assertEquals("1", d.summaryStrip.values[6].getText());
                assertEquals("0", d.summaryStrip.values[7].getText());
                assertEquals("412 MS", d.summaryStrip.values[8].getText());
                for (JLabel v : d.summaryStrip.values) assertEquals(Theme.TEXT, v.getForeground(), v.getText() + ": known, bright");
                assertTrue(((javax.swing.JComponent) d.summaryStrip.values[4].getParent()).getToolTipText().contains("above take-off"),
                        "which altitude the highest one is");
            });
        }
    }

    @Test
    void whatTheFlightsFilesDontTellIsDimmed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            SummaryStrip none = new SummaryStrip(); // no flight chosen
            for (JLabel v : none.values) {
                assertEquals(FlightSummary.UNKNOWN, v.getText());
                assertEquals(Theme.MUTED, v.getForeground());
            }
        });
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            flights.choose(1); // a track and nothing else: no battery, no link
            SwingUtilities.invokeAndWait(() -> {
                SummaryStrip s = d.summaryStrip;
                List<FlightSummary.Figure> figures = d.summary.figures();
                for (int i = 0; i < figures.size(); i++) {
                    assertEquals(figures.get(i).value(), s.values[i].getText(), "as the summary gives it");
                    assertEquals(figures.get(i).known() ? Theme.TEXT : Theme.MUTED, s.values[i].getForeground(), figures.get(i).label());
                }
                assertEquals("1:30", s.values[0].getText());
                assertEquals("MAX GPS ALT", s.labels[4].getText());
                assertEquals(Theme.TEXT, s.values[4].getForeground());
                for (int i = 5; i < 9; i++) {
                    assertEquals("-", s.values[i].getText(), s.labels[i].getText() + ": not known, never 0");
                    assertEquals(Theme.MUTED, s.values[i].getForeground(), s.labels[i].getText() + " dimmed");
                }
            });
            flights.choose(0); // and back: bright again
            SwingUtilities.invokeAndWait(() -> assertEquals(Theme.TEXT, d.summaryStrip.values[8].getForeground()));
        }
    }

    /** No label of the strip is given less room than its text takes (it would end in "..."). */
    private static void assertWholeTexts(Container c, String at) {
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel l && l.isVisible()) {
                assertTrue(l.getWidth() >= l.getPreferredSize().width && l.getHeight() >= l.getPreferredSize().height,
                        at + ": \"" + l.getText() + "\" has " + l.getSize() + " of " + l.getPreferredSize());
            } else if (k instanceof Container inner) {
                assertWholeTexts(inner, at);
            }
        }
    }

    @Test
    void itFitsTheSmallScreen() throws Exception {
        try (DebriefFlight flights = new DebriefFlight(dir)) {
            PreviousFlightsDialog d = flights.open();
            SwingUtilities.invokeAndWait(() -> {
                d.addBookmark(15_000, "Take-off");
                d.addBookmark(90_000, "Turned back");
                d.note.setText("Wind from the west, stronger than forecast.\nLanded with 3.56 V.");
                d.seek(DebriefFlight.START + 75_000);
            });
            // Windows holds a window to its screen: a size bigger than this one's (the CI runner's is 1024x768) is left out
            Rectangle screen = d.getGraphicsConfiguration().getBounds();
            // the wish on a big screen; the whole of a 1093x584 screen; the least the window goes to on that screen
            int[][] sizes = {{1280, 760}, {1093, 584}, {900, 584}};
            for (int flight : new int[]{0, 2}) { // all there is to show; and the longest heading (MAX PHONE GPS ALT)
                flights.choose(flight);
                for (int[] size : sizes) {
                    if (size[0] > screen.width || size[1] > screen.height) continue;
                    String at = size[0] + "x" + size[1] + (flight == 0 ? "" : "-track-only");
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            if (flight == 0) d.seek(DebriefFlight.START + 75_000);
                            DebriefFlight.asOnASmallScreen(d);
                            Offscreen.layout(d, size[0], size[1]);
                            assertEquals(size[0], d.getWidth(), "the window took the size");
                            assertEquals(size[1], d.getHeight());
                            d.map.setReplay(d.data.path()); // fitted to the map's size now
                            d.tick();
                            Offscreen.shot(d, "flights-" + at); // first the picture to look at, then what must hold
                            if (size[0] == 1093) Offscreen.shot(d, "flights-" + at + "-at-125-percent", 1.25); // a 1366x768 screen at 125 %
                            Offscreen.assertNothingClipped(d.getRootPane(), at);
                            assertWholeTexts(d.summaryStrip, at);
                            SummaryStrip s = d.summaryStrip;
                            int rows = s.rowsAt(s.getWidth());
                            assertTrue(rows <= 2, at + ": the strip in one row or two, not " + rows);
                            if (size[0] >= 1280) assertEquals(1, rows, at + ": one row where there is room");
                            Rectangle strip = SwingUtilities.convertRectangle(s.getParent(), s.getBounds(), d.getRootPane());
                            Rectangle video = SwingUtilities.convertRectangle(d.video.getParent(), d.video.getBounds(), d.getRootPane());
                            Rectangle bar = SwingUtilities.convertRectangle(d.timeline.getParent(), d.timeline.getBounds(), d.getRootPane());
                            assertTrue(strip.y + strip.height <= video.y, at + ": the strip over the video, not on it");
                            assertTrue(video.y + video.height <= bar.y, at + ": the video over the bar");
                            assertTrue(video.height >= 130 && d.map.getHeight() >= 130, at + ": video and map keep their room: " + video);
                            assertTrue(bar.height >= 92 + PreviousFlightsDialog.Timeline.MARKS_H, at + ": the bar whole: " + bar);
                            assertTrue(d.note.getParent().getHeight() >= 40, at + ": room to write a note: " + d.note.getParent().getBounds());
                            for (javax.swing.JButton b : new javax.swing.JButton[]{d.play, d.speedButton, d.bookmark, d.report}) {
                                assertTrue(b.getWidth() >= b.getPreferredSize().width && b.getHeight() >= b.getPreferredSize().height,
                                        at + ": " + b.getText() + " whole: " + b.getBounds());
                            }
                        } catch (java.io.IOException e) {
                            throw new AssertionError(e);
                        }
                    });
                }
            }
        }
    }
}
