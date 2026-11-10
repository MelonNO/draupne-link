package de.droiddrone.pc.fleet;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tour's six drones' pictures (2026-11-09; tactical since 2026-11-10): each scene draws its models in front of the
 * drone, something in it moves from one picture to the next, a still camera's picture is the same drawn afresh as
 * from what it keeps, and the quads keep their own colours. With SHOW_SCENES_DIR set, each scene's pictures are
 * written there as PNGs (to look at).
 */
class ShowSceneTest {
    private static final int W = 640, H = 360;

    private static BufferedImage picture(ShowScene s, Scene.View v, long at, boolean analysis) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        s.render(img, v, at, analysis);
        return img;
    }

    /** Pixels that differ by more than a few grey levels. */
    private static int changed(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
                if (d > 24) n++;
            }
        }
        return n;
    }

    @Test
    void everySceneShowsSomethingMoving() throws Exception {
        String dir = System.getenv("SHOW_SCENES_DIR");
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 30);
        for (ShowScene.Kind k : ShowScene.Kind.values()) {
            ShowScene s = new ShowScene(k, 0, 0, 30, 0);
            BufferedImage empty = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            new SimWorld(0, 0).paint(empty.createGraphics(), new Scene.Camera(v, W, H), v, W, H, 1000, true, false);
            BufferedImage a = picture(s, v, 1000, true);
            assertTrue(changed(empty, a) > W * H / 4, k + ": its land and models are in the picture (" + changed(empty, a) + " px)");
            // through a minute: most half seconds something moves (a convoy comes and goes, a patrol halts). 40 px: the
            // mortar's crew alone changes about 50 against the finale's smooth sky (2026-11-10: 60 was set when smoke
            // drifted over the ghost blocks' edges)
            int moving = 0, tries = 12;
            for (int i = 0; i < tries; i++) {
                long t = 1000 + i * 5000L;
                if (changed(picture(s, v, t, true), picture(s, v, t + 500, true)) > 40) moving++;
            }
            assertTrue(moving >= tries / 2, k + ": something moved in " + moving + " of " + tries + " half seconds");
            if (dir != null) {
                Files.createDirectories(Path.of(dir));
                for (int i = 0; i < 3; i++) {
                    ImageIO.write(picture(s, v, 3000 + i * 9000L, false), "png", Path.of(dir, k.name().toLowerCase() + "-" + i + ".png").toFile());
                }
            }
        }
    }

    @Test
    void aStillCamerasPictureIsTheSameAsOneDrawnAfresh() {
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 30);
        for (ShowScene.Kind k : ShowScene.Kind.values()) {
            ShowScene kept = new ShowScene(k, 0, 0, 30, 0);
            picture(kept, v, 1000, true); // what stands still is drawn and kept
            BufferedImage again = picture(kept, v, 7300, true);
            BufferedImage fresh = picture(new ShowScene(k, 0, 0, 30, 0), v, 7300, true);
            assertEquals(0, changed(again, fresh), k + ": drawn from what it kept, the picture is the same");
        }
    }

    @Test
    void theQuadsFlyARouteAndComeBackToTheirPads() {
        ShowScene s = new ShowScene(ShowScene.Kind.DRONES, 0, 0, 0, 0);
        for (int i = 0; i < ShowScene.PADS.length; i++) {
            double maxUp = 0, maxSpeed = 0, maxRoll = 0, farthest = 0;
            boolean landed = false;
            for (double t = 0; t < ShowScene.SORTIE_S; t += 0.1) {
                double[] q = s.quad(i, t);
                maxUp = Math.max(maxUp, q[2]);
                maxSpeed = Math.max(maxSpeed, q[6]);
                maxRoll = Math.max(maxRoll, Math.abs(q[5]));
                farthest = Math.max(farthest, q[0]);
                if (q[2] == 0) {
                    landed = true;
                    assertEquals(ShowScene.PADS[i][0], q[0], 1e-9, "on its pad");
                }
            }
            assertTrue(landed, "quad " + i + " lands");
            assertTrue(maxUp > 10 && maxUp < 20, "quad " + i + " climbs over the trees: " + maxUp + " m");
            assertTrue(maxSpeed > 4 && maxSpeed < 16, "quad " + i + " at a real speed: " + maxSpeed + " m/s");
            assertTrue(maxRoll > 5 && maxRoll <= 45, "quad " + i + " banks into its turns: " + maxRoll + " degrees");
            assertTrue(farthest > 30, "quad " + i + " goes out over the treeline: " + farthest + " m");
        }
    }

    @Test
    void theFpvQuadsKeepTheirOwnColours() {
        // the pilot's FPV model drawn alone, untinted, against what the same model shows with a tint
        Raster3D.Mesh m = Raster3D.fpvDrone();
        Scene.View v = new Scene.View(0, 0, 1.3, 0, 0, 0);
        Scene.Camera cam = new Scene.Camera(v, 320, 180);
        BufferedImage own = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), tinted = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Raster3D.draw(Raster3D.frame(own), cam, m, 1.2, 0, 1.5, 30, 0, 0, 1, -1);
        Raster3D.draw(Raster3D.frame(tinted), cam, m, 1.2, 0, 1.5, 30, 0, 0, 1, 0x2f8fe8);
        int drawn = 0, blue = 0;
        for (int c : Scene.pixels(own)) {
            if (c == 0) continue;
            drawn++;
            if ((c & 0xff) > (c >> 16 & 0xff) + 40) blue++;
        }
        assertTrue(drawn > 150, "the quad is in the picture: " + drawn + " px");
        assertTrue(changed(own, tinted) > drawn / 2, "a tint would change it: so none is put on");
        assertTrue(blue < drawn / 2, "its own colours, not a tint's: " + blue + " of " + drawn + " px blue");
    }

    @Test
    void theFpvDroneIsThinnedForTheVideo() {
        Raster3D.Mesh m = Raster3D.fpvDrone();
        assertTrue(m.triangles() > 1000 && m.triangles() < 20_000, m.triangles() + " triangles");
    }

    @Test
    void theModelsAreLoadedTexturedAndOfTheirSize() {
        assertEquals(7.65, length("btr80"), 0.05);
        assertEquals(7.37, length("ural"), 0.05);
        assertEquals(4.03, length("uaz"), 0.05);
        Raster3D.Mesh soldier = Raster3D.model("soldier");
        assertEquals(ShowScene.KNEEL_BACK + 1, soldier.frameCount(), "a walk cycle and the poses");
        assertTrue(soldier.textures().length >= 1 && soldier.uv() != null, "textured");
        float top = 0, kneelTop = 0;
        for (int i = 2; i < soldier.p().length; i += 3) {
            top = Math.max(top, soldier.frames()[ShowScene.STAND][i]);
            kneelTop = Math.max(kneelTop, soldier.frames()[ShowScene.KNEEL][i]);
        }
        assertTrue(top > 1.7 && top < 1.95, "standing about 1.8 m: " + top);
        assertTrue(kneelTop > 1.1 && kneelTop < 1.5, "kneeling lower: " + kneelTop);
        assertTrue((Raster3D.model("spruce-0").flags() & Raster3D.CARD) != 0, "the trees are impostor cards");
        for (String name : new String[]{"btr80", "ural", "uaz", "mortar", "soldier", "soldier-far", "recon-quad"}) {
            assertTrue(Raster3D.model(name).triangles() < 7000, name + " decimated: " + Raster3D.model(name).triangles());
        }
    }

    private static double length(String name) {
        float[] p = Raster3D.model(name).p();
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < p.length; i += 3) {
            lo = Math.min(lo, p[i]);
            hi = Math.max(hi, p[i]);
        }
        return hi - lo;
    }

    @Test
    void theConvoySlowsForTheBend() {
        ShowScene s = new ShowScene(ShowScene.Kind.CONVOY, 0, 0, 0, 0);
        double fastest = 0, slowest = Double.MAX_VALUE;
        for (double t = 0; t < 60; t += 0.25) {
            double[] q = s.convoyVehicle(0, t);
            if (q == null || q[3] < 0.5) continue;
            fastest = Math.max(fastest, q[3]);
            slowest = Math.min(slowest, q[3]);
        }
        assertTrue(fastest > ShowScene.ROAD_FAST - 0.5, "fast on the straights: " + fastest);
        assertTrue(slowest < ShowScene.ROAD_SLOW + 1, "slow in the bend: " + slowest);
    }

    @Test
    void thePatrolHaltsAndGoesOnTheSameWayEachMinute() {
        double a = ShowScene.patrolWalked(5), b = ShowScene.patrolWalked(5 + ShowScene.P_CYCLE);
        assertEquals(ShowScene.PATROL_SPAN, b - a, 1e-6, "a minute's walk is the file's whole round: no jump where it is seen");
        double halt = ShowScene.P_WALK1 + 1;
        assertEquals(ShowScene.patrolWalked(halt), ShowScene.patrolWalked(halt + 8), 1e-9, "halted, in cover");
    }

    @Test
    void theCheckpointsUazStopsAndDrivesOn() {
        ShowScene s = new ShowScene(ShowScene.Kind.CHECKPOINT, 0, 0, 0, 0);
        double[] in = s.cpCar(2), stopped = s.cpCar(15), out = s.cpCar(30);
        assertTrue(in[1] > 5, "coming in");
        assertEquals(0, stopped[1], 1e-9, "stopped at the barrier");
        assertTrue(out[0] > stopped[0] + 10 && out[1] > 1, "on through the chicane");
    }

    @Test
    void theTourSixSeeSixScenesTheDroneTeamFirst() {
        assertEquals(6, TourShowcase.SCENES.length);
        assertEquals(ShowScene.Kind.DRONES, TourShowcase.SCENES[0], "the first is the drone the pilot flies");
        assertEquals(6, java.util.Set.of(TourShowcase.SCENES).size());
    }

    @Test
    void aDroneOnTheGroundLooksFromJustOverTheGrassOneInTheAirFromWhereItIs() {
        Scene.View ground = new Scene.View(1, 2, 0, 0, 0, 40);
        Scene.View eye = ShowScene.eye(ground);
        assertEquals(ShowScene.GROUND_EYE_M, new Scene.Camera(eye, 320, 180).cu, 1e-9, "the lens just over the grass, not on a mast");
        assertEquals(ShowScene.UPTILT_DEG, eye.pitchDeg(), 1e-9, "tilted up, as an FPV camera");
        assertEquals(40, eye.headingDeg());
        // lifting off it goes over into the drone's own view, no jump
        Scene.View nearlyUp = ShowScene.eye(new Scene.View(1, 2, 0.999, 0, 0, 40));
        assertEquals(0.999, nearlyUp.alt(), 1e-3);
        assertEquals(0, nearlyUp.pitchDeg(), 0.01);
        Scene.View flying = new Scene.View(1, 2, 12, 5, -20, 40);
        assertSame(flying, ShowScene.eye(flying));
    }

    @Test
    void theDroneSitsOnRealLandItsGrassInFrontOfTheLens() {
        // BACK_M behind the scene, on the island's land - not on the memory's white floor - with blades of grass in view
        // (the pilot, 2026-11-10: "reintroduce the grass" after an hour on the floor)
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 30);
        for (ShowScene.Kind k : ShowScene.Kind.values()) {
            BufferedImage img = picture(new ShowScene(k, 0, 0, 30, 0), v, 4000, true);
            int c = img.getRGB(W / 2, H * 3 / 4);
            int r = c >> 16 & 0xff, g = c >> 8 & 0xff, b = c & 0xff;
            assertTrue(g > b + 15 && g > r && g < 200, k + ": grass under the drone, not the floor: " + Integer.toHexString(c & 0xffffff));
            int dark = 0;
            for (int x = 0; x < W; x++) {
                int p = img.getRGB(x, H - 2);
                if ((p >> 8 & 0xff) < 90) dark++;
            }
            assertTrue(dark > W / 20, k + ": blades along the bottom (" + dark + " px)");
        }
    }

    @Test
    void theQuadsAndTheMortarAreHeardThePatrolNot() {
        Scene.View v = new Scene.View(0, 0, 0, 0, 0, 0);
        assertTrue(energy(ShowScene.Kind.DRONES, v, 9000) > 0.01, "the quads in the air");
        assertTrue(energy(ShowScene.Kind.MORTAR, v, (long) (ShowScene.FIRE_AT * 1000) + 50) > 0.01, "the mortar's report");
        assertTrue(energy(ShowScene.Kind.HIDE, v, 3000) > 0.01, "the BTR idling");
        assertEquals(0, energy(ShowScene.Kind.PATROL, v, 3000), "a patrol walks quietly");
    }

    private static double energy(ShowScene.Kind k, Scene.View v, long at) {
        ShowScene s = new ShowScene(k, 0, 0, 0, 0);
        float[] l = new float[1024], r = new float[1024];
        double e = 0;
        for (int i = 0; i < 10; i++) {
            java.util.Arrays.fill(l, 0);
            java.util.Arrays.fill(r, 0);
            s.addSound(l, r, v, at + i * 21L);
            for (int j = 0; j < l.length; j++) e += l[j] * l[j] + r[j] * r[j];
        }
        return e;
    }

    @Test
    void theCamouflageNetsArePeggedToTheGround() {
        // the pilot (2026-11-10): "a lot of the camo nets are floating" - exported, the lowest edge was 0.43 m up
        for (String name : new String[]{"net-small", "net-large"}) {
            float[] p = Raster3D.model(name).p();
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE, top = 0;
            for (int i = 0; i < p.length; i += 3) {
                x0 = Math.min(x0, p[i]);
                x1 = Math.max(x1, p[i]);
                y0 = Math.min(y0, p[i + 1]);
                y1 = Math.max(y1, p[i + 1]);
                top = Math.max(top, p[i + 2]);
            }
            int edge = 0;
            for (int i = 0; i < p.length; i += 3) {
                boolean onEdge = p[i] - x0 < 1e-3 || x1 - p[i] < 1e-3 || p[i + 1] - y0 < 1e-3 || y1 - p[i + 1] < 1e-3;
                if (!onEdge) continue;
                edge++;
                assertTrue(p[i + 2] <= 0.05f, name + ": its edge on the ground, not " + p[i + 2] + " m up");
            }
            assertTrue(edge > 40, name + ": its outline found (" + edge + ")");
            assertTrue(top > 2, name + ": still held up on its poles in the middle (" + top + " m)");
        }
    }

    @Test
    void theLensBendBlendsNeighboursInsteadOfDoublingPixels() {
        // the pilot (2026-11-10): "in the sim the resolution is very low" - nearest-pixel reading doubled rows
        int w = 320, h = 180;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = x % 2 == 0 ? 0xffffff : 0x000000;
        DroneLens.apply(px, w, h, false);
        int between = 0;
        for (int x = 100; x < 220; x++) {
            int g = px[(h / 2) * w + x] >> 8 & 0xff;
            if (g > 40 && g < 215) between++;
        }
        assertTrue(between > 30, "stripes read between pixels near the middle: " + between + " of 120 in between");
    }

    /**
     * Behind the action the BTR finale's world, not a wall of white ghost blocks (2026-11-10, the pilot: the "simulation
     * wall" "more similar to the btr simulation finale"): the top of the picture its pale grey-blue sky - not white, not
     * light blue.
     */
    @Test
    void behindTheActionTheFinalesWorldNotAWallOfBlocks() {
        for (ShowScene.Kind k : new ShowScene.Kind[]{ShowScene.Kind.PATROL, ShowScene.Kind.HIDE, ShowScene.Kind.CONVOY}) {
            BufferedImage img = picture(new ShowScene(k, 0, 0, 30, 0), new Scene.View(0, 0, 0, 0, 0, 30), 12_000, true);
            int sky = 0, all = 0;
            for (int y = 0; y < H / 3; y++) {
                for (int x = 0; x < W; x++) {
                    int p = img.getRGB(x, y), r = p >> 16 & 255, b = p & 255;
                    all++;
                    if (r <= 250 && b - r <= 18) sky++;
                }
            }
            assertTrue(sky > all * 0.9, k + ": the finale's sky above the action, " + sky + " of " + all);
        }
    }
}
