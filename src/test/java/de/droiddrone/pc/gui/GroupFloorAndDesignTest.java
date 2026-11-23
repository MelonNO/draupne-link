package de.droiddrone.pc.gui;

import de.droiddrone.pc.air.AirParams;
import de.droiddrone.pc.air.GroupDesign;
import de.droiddrone.pc.air.GroupLink;
import de.droiddrone.pc.map.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fleet PRD FL-14 (the floor's - / + and a typed value on the group's map label) and FL-9 (the group designer). */
class GroupFloorAndDesignTest {
    static final double LAT = 59.91, LON = 10.73;

    private static GroupLink.Picture picture() {
        return new GroupLink.Picture(LAT, LON, List.of(new double[]{LAT + 0.0002, LON}), List.of(new double[]{LAT + 0.0005, LON}),
                62, 82, 120, 10, 40, 6, "Lead");
    }

    @Test
    void theFloorFromTheGroupsLabel(@TempDir Path dir) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            MapView map = new MapView(new TileCache(dir, "test", false));
            map.setSize(800, 600);
            map.setFollow(false);
            map.centreOn(LAT, LON, 16);
            map.setGroup(picture());
            List<String> did = new ArrayList<>();
            boolean[] mine = {true};
            map.setGroupFloor(new MapView.GroupFloor() {
                public boolean shown() {
                    return mine[0];
                }

                public void step(double deltaM) {
                    did.add("step " + Math.round(deltaM));
                }

                public void typed() {
                    did.add("typed");
                }
            });
            BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            map.paint(g);
            MapGroupLayer.FloorButtons b = map.groupFloorButtons();
            assertNotNull(b, "the label has its floor buttons");
            assertEquals(0, map.groupFloorAt(center(b.minus())));
            assertEquals(1, map.groupFloorAt(center(b.plus())));
            assertEquals(2, map.groupFloorAt(center(b.value())));
            map.groupFloorClicked(map.groupFloorAt(center(b.minus())));
            map.groupFloorClicked(map.groupFloorAt(center(b.plus())));
            map.groupFloorClicked(map.groupFloorAt(center(b.value())));
            assertEquals(List.of("step -5", "step 5", "typed"), did);

            // a watching PC (or a group not flown from here): no buttons, nothing pressed
            mine[0] = false;
            map.paint(g);
            g.dispose();
            assertNull(map.groupFloorButtons());
            assertEquals(-1, map.groupFloorAt(center(b.minus())));
            map.groupFloorClicked(0);
            assertEquals(3, did.size());
        });
    }

    private static Point center(java.awt.Rectangle r) {
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }

    @Test
    void theDesignersChecks() {
        AirParams d = AirParams.defaults();
        assertNull(GroupDesign.problems(d, 8, 0), "the PRD's numbers fit");
        assertEquals(50, GroupDesign.topM(d, 8, 0), 1e-9, "the abort layer at 40, the cube's one layer above it");
        assertEquals(1, GroupDesign.layers(8));
        assertEquals(2, GroupDesign.layers(9));

        // AC-13: over the ceiling
        AirParams high = GroupDesign.with(d, 20, 10, 20, 120, 100, 100, 120);
        String why = GroupDesign.problems(high, 24, 0);
        assertNotNull(why);
        assertTrue(why.contains("Top layer 130 m") && why.contains("ceiling of 120 m"), why);
        assertNull(GroupDesign.problems(high, 16, 0), "two layers: 120 m, at the ceiling");
        assertNotNull(GroupDesign.problems(high, 16, 1), "and a reserve's approach layer over it: refused");

        // AC-35: the sizes; layers closer than 10 m
        AirParams tight = GroupDesign.with(d, 12, 8, 20, 90, 60, 40, 120);
        String size = GroupDesign.problems(tight, 8, 0);
        assertTrue(size.contains("Cell size at least 15 m"), size);
        assertTrue(size.contains("Distance from the lead at least 70 m"), size);
        assertTrue(size.contains("Layers at least 10 m apart"), size);
    }

    @Test
    void theDesignerPanelChecksAsTheNumbersChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            GroupDesignPanel p = new GroupDesignPanel(AirParams.defaults());
            assertEquals("Fits.", p.check.getText());
            assertNull(p.problems());
            p.floor.setValue(100);
            p.followers.setValue(24);
            assertNotNull(p.problems());
            assertTrue(p.check.getText().contains("ceiling"), p.check.getText());
            p.floor.setValue(40);
            assertNull(p.problems());
            p.leash.setValue(100);
            assertTrue(p.problems().contains("Leash at least"), p.problems());
            p.leash.setValue(150);
            p.cell.setValue(25);
            AirParams out = p.params();
            assertEquals(25, out.cellM());
            assertEquals(150, out.leashM());
            assertEquals(10, out.radiusM(), "the protection radius is not the designer's");
        });
    }

    @Test
    void groupDesignIsOfferedBeforeTheGroupAndTakenForIt() throws Exception {
        AirParams[] asked = {null};
        FleetOps ops = new FleetOps(new FleetOps.Host() {
            public String flownPhone() {
                return "Lead";
            }

            public java.net.InetSocketAddress addressOf(String phone) {
                return null;
            }

            public String key() {
                return "DD";
            }

            public void log(String line) {
            }

            public void alert(String text) {
            }

            public void flyNow(String phone) {
            }

            public AirParams design(AirParams now) {
                asked[0] = now;
                return GroupDesign.with(now, 25, 12, 25, 160, 110, 50, 150);
            }
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                ops.items("F1").stream().filter(i -> i.getText().equals("GROUP DESIGN...")).findFirst().orElseThrow().doClick();
                assertEquals(40, asked[0].floorM(), "opened on the floor now");
                assertEquals(25, ops.design().cellM());
                assertEquals(50, ops.floorM(), "the designer's floor is the group's");
                assertFalse(ops.floorControls(), "no group yet: no floor buttons");
            });
        } finally {
            ops.close();
        }
    }
}
