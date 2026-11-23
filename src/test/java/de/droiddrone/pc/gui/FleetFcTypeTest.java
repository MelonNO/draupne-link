package de.droiddrone.pc.gui;

import de.droiddrone.common.FcInfo;
import de.droiddrone.pc.LastKnown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fleet PRD AP-1 / GU-1: FLEET says which flight controller each drone has (ARDU / BTFL), as this PC last saw it
 * connected - a phone's check-in carries no FC type, so the PC keeps it on disk with the phone's last place and mode.
 * Names are made up.
 */
class FleetFcTypeTest {
    private static final long T = 1_800_000_000_000L;

    @Test
    void theTypeIsKeptOnDiskWithThePhonesOtherFacts(@TempDir Path dir) {
        Path file = dir.resolve("last-known.txt");
        LastKnown a = new LastKnown(file);
        a.place("MobileAlpha", 60.1, 10.7, T);
        a.fc("MobileAlpha", LastKnown.fcWord(FcInfo.FC_VARIANT_ARDUPILOT), T + 1000);
        a.mode("MobileAlpha", "IDLE", T + 2000);          // a later change keeps the type
        a.battery("MobileAlpha", 70, 3.8, T + 3000);
        a.fc("MobileBravo", LastKnown.fcWord(FcInfo.FC_VARIANT_BETAFLIGHT), T);
        a.flush();

        LastKnown b = new LastKnown(file);
        assertEquals("ARDU", b.get("MobileAlpha").fc());
        assertEquals("IDLE", b.get("MobileAlpha").mode());
        assertEquals("BTFL", b.get("MobileBravo").fc());
        b.rename("MobileAlpha", "MobileGolf", T + 4000);
        assertEquals("ARDU", b.get("MobileGolf").fc(), "a rename keeps it");
    }

    @Test
    void aFileFromBeforeHasNoTypeAndStillReads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("last-known.txt");
        // nine columns, as 1.0.71 writes them
        Files.write(file, List.of("MobileAlpha\t60.1000000\t10.7000000\t" + T + "\t55\t3.60\t" + T + "\tIDLE\t" + T));
        LastKnown.Entry k = new LastKnown(file).get("MobileAlpha");
        assertNotNull(k);
        assertEquals("", k.fc());
        assertEquals(55, k.phonePercent());
        assertNull(FleetController.fcOf(new LastKnown(file), "MobileAlpha"), "no type: FLEET shows none");
    }

    @Test
    void unknownOrBlankTypesChangeNothing() {
        LastKnown k = new LastKnown(null);
        k.fc("MobileAlpha", "ARDU", T);
        k.fc("MobileAlpha", LastKnown.fcWord(FcInfo.FC_VARIANT_UNKNOWN), T + 1);
        assertEquals("ARDU", k.get("MobileAlpha").fc(), "an FC not identified yet leaves the type known before");
        assertEquals("INAV", LastKnown.fcWord(FcInfo.FC_VARIANT_INAV));
        assertEquals("PX4", LastKnown.fcWord(FcInfo.FC_VARIANT_PX4));
    }

    @Test
    void theDetailStripWritesTheType() {
        FleetCards.Card plain = FleetCardsTest.sample().get(0);
        FleetCards.Card ardu = plain.withFc("ARDU");
        assertEquals("ARDU", ardu.fc());
        assertSame(ardu, ardu.withFc("ARDU"), "no change: the same card");
        assertNull(ardu.withFc("").fc());
        assertEquals("ARDU", ardu.withWas("WAS IDLE").withBlind(true).fc(), "the other with-s keep it");
        assertTrue(lit(ardu) > lit(plain), "the type is drawn beside the name");
    }

    private static int lit(FleetCards.Card c) {
        BufferedImage img = new BufferedImage(FleetManagementPanel.BODY_WIDTH - 16, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        FleetManagementPanel.paintDetail(g, c, img.getWidth());
        g.dispose();
        int lit = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) != 0) lit++;
        return lit;
    }
}
