package de.droiddrone.pc.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.0.64, the pilot: "Add a feature for displaying D-areas and NOTAMS and R-areas in the map" - openAIP's areas (a free
 * key) and NOTAMs pasted from IPPC.no. Made-up areas and NOTAMs in the real formats.
 */
class AirspaceTest {
    /** openAIP's airspace answer: one restricted, one danger, one CTR (not kept), one broken. */
    static final String OPENAIP = """
            {"limit":1000,"totalCount":4,"totalPages":1,"page":1,"items":[
             {"_id":"r1","name":"EN R102 OSLO","type":1,"icaoClass":8,
              "lowerLimit":{"value":0,"unit":1,"referenceDatum":0},"upperLimit":{"value":3500,"unit":1,"referenceDatum":1},
              "geometry":{"type":"Polygon","coordinates":[[[10.70,59.90],[10.80,59.90],[10.80,59.95],[10.70,59.95],[10.70,59.90]]]}},
             {"_id":"d1","name":"EN D301 TEST","type":2,
              "lowerLimit":{"value":0,"unit":1,"referenceDatum":0},"upperLimit":{"value":95,"unit":6,"referenceDatum":2},
              "geometry":{"type":"Polygon","coordinates":[[[11.0,60.0],[11.2,60.0],[11.1,60.1],[11.0,60.0]]]}},
             {"_id":"c1","name":"OSLO CTR","type":4,"geometry":{"type":"Polygon","coordinates":[[[10,59],[11,59],[11,60],[10,59]]]}},
             {"_id":"x","name":"BROKEN","type":1}
            ]}""";

    @Test
    void openAipsRestrictedAndDangerAreasAreRead() {
        List<Airspace> a = OpenAipAirspace.parse(OPENAIP);
        assertEquals(2, a.size(), "the CTR and the broken one left out");
        Airspace r = a.get(0);
        assertEquals(Airspace.Kind.RESTRICTED, r.kind());
        assertEquals("EN R102 OSLO", r.name());
        assertEquals(4, r.ring().size(), "the closing point dropped");
        assertEquals(59.90, r.ring().get(0)[0], 1e-9, "GeoJSON's lon, lat turned to lat, lon");
        assertEquals("GND-3500 FT AMSL", r.limits());
        assertEquals("FL95", a.get(1).upper());
        assertEquals(Airspace.Kind.DANGER, a.get(1).kind());
        assertTrue(r.touches(new double[]{59.92, 59.93, 10.75, 10.76}));
        assertFalse(r.touches(new double[]{61, 62, 10, 11}));
    }

    @Test
    void savedBoxesAreUsedAndNothingIsFetchedWithoutAKeyOrWhileFlying(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("59_10.json"), OPENAIP);
        boolean[] held = {true};
        OpenAipAirspace data = new OpenAipAirspace(dir, "test", true, () -> held[0]);
        OpenAipAirspace.Answer a = data.areas(new double[]{59.5, 59.95, 10.2, 10.9}, "key");
        assertEquals(2, a.areas().size(), "the saved box, as openAIP gave it");
        assertNull(a.note(), "all there");
        a = data.areas(new double[]{59.5, 60.5, 10.2, 10.9}, "key");
        assertEquals("saved areas only: a drone may be flying", a.note(), "60_10 missing and nothing fetched while held");
        assertEquals("no openAIP key (Airspace key...)", data.areas(new double[]{58.5, 58.6, 10.2, 10.3}, "").note());
        assertEquals("zoom in to fetch areas", new OpenAipAirspace(dir, "test", true, () -> false)
                .areas(new double[]{50, 60, 0, 20}, "key").note());
    }

    /**
     * 1.0.64: a refused key, too many requests and no network all read "some areas not fetched", and every view rest
     * asked each missing box again (up to 16 requests at a time) whatever openAIP had just said.
     */
    @Test
    void aFailedFetchSaysWhyAndIsNotRepeatedAtOnce(@TempDir Path dir) {
        OpenAipAirspace data = new OpenAipAirspace(dir, "test", true, () -> false);
        int[] asked = {0};
        int[] status = {429};
        data.fetcher = (lat, lon, key) -> {
            asked[0]++;
            return new OpenAipAirspace.Fetched(status[0] == 200 ? OPENAIP : null, status[0]);
        };
        double[] view = {59.5, 59.6, 10.2, 11.3}; // two boxes
        assertEquals("openAIP: too many requests - in 5 min again", data.areas(view, "key").note());
        assertEquals(1, asked[0], "the second box isn't asked after the first was turned away");
        data.areas(view, "key");
        assertEquals(1, asked[0], "nor at the next view rest");
        status[0] = 401;
        assertEquals("openAIP refuses the key (Airspace key...)", data.areas(view, "other").note(), "another key is tried");
        assertEquals(2, asked[0]);
        data.areas(view, "other");
        assertEquals(2, asked[0], "a refused key is not tried again");
        status[0] = 0;
        assertEquals("offline: saved areas only", data.areas(view, "third").note());
        status[0] = 200;
        OpenAipAirspace.Answer ok = data.areas(view, "fourth");
        assertNull(ok.note());
        assertEquals(2, ok.areas().size());
    }

    /** As IPPC.no shows them: a D-area activated (its Q) circle), a polygon in E), a radius in E), PERM, EST. */
    static final String PASTED = """
            A1234/26 NOTAMN
            Q) ENOR/QRDCA/IV/BO/W/000/040/6012N01107E003
            A) ENOR B) 2611120700 C) 2611121600
            E) DANGER AREA EN D350 ACTIVATED.
            F) GND G) 4000FT AMSL

            A1240/26 NOTAMN
            Q) ENOR/QRTCA/IV/BO/W/000/020/5955N01045E002
            A) ENOR B) 2611110000 C) 2611302359 EST
            E) TEMPO RESTRICTED AREA EN R603 ESTABLISHED FOR UAS FLYING WITHIN AREA:
            595500N 0104500E - 595700N 0105000E - 595400N 0105200E - 595500N 0104500E.
            F) GND G) 2000FT AMSL

            (A1250/26 NOTAMN
            Q) ENOR/QWULW/IV/BO/W/000/010/6000N01100E001
            A) ENOR B) 2611100800 C) PERM
            E) UAS ACTIVITY PSN 600000N 0110000E RADIUS 500M.
            F) GND G) 400FT AGL)

            Q) GARBAGE
            this is not a NOTAM
            """;

    @Test
    void pastedNotamsGetTheirAreasAndTimes() {
        List<NotamParser.Notam> n = NotamParser.parse(PASTED);
        assertEquals(3, n.size(), "the garbage left out");
        NotamParser.Notam d = n.get(0);
        assertEquals("A1234/26", d.id());
        assertEquals(48, d.ring().size(), "no points in E): the Q) circle");
        assertEquals(Instant.parse("2026-11-12T07:00:00Z"), d.start());
        assertEquals(Instant.parse("2026-11-12T16:00:00Z"), d.end());
        assertEquals("GND", d.lower());
        assertEquals("4000FT AMSL", d.upper());
        double[] mid = new Airspace("x", "x", Airspace.Kind.NOTAM, d.ring(), "", "", List.of(), false).middle();
        assertEquals(60.2, mid[0], 0.001, "6012N");
        assertEquals(11 + 7 / 60.0, mid[1], 0.001, "01107E");

        NotamParser.Notam poly = n.get(1);
        assertEquals(3, poly.ring().size(), "E)'s polygon, its closing point dropped");
        assertEquals(59 + 55 / 60.0, poly.ring().get(0)[0], 1e-6);
        assertEquals(10 + 50 / 60.0, poly.ring().get(1)[1], 1e-6);

        NotamParser.Notam circle = n.get(2);
        assertEquals("A1250/26", circle.id(), "a NOTAM in brackets too");
        assertNull(circle.end(), "PERM");
        assertEquals(48, circle.ring().size(), "E)'s radius");
        double north = circle.ring().get(0)[0] - 60.0;
        assertEquals(500 / 111_320.0, north, 1e-6, "500 m");

        Instant now = Instant.parse("2026-11-12T08:00:00Z");
        assertTrue(d.activeAt(now));
        assertFalse(d.activeAt(Instant.parse("2026-11-12T17:00:00Z")));
        assertTrue(d.overAt(Instant.parse("2026-11-12T17:00:00Z")));
        assertTrue(circle.activeAt(now));
        Airspace later = NotamParser.toAirspace(d, Instant.parse("2026-11-12T06:00:00Z"));
        assertTrue(later.later(), "not started yet: dashed");
        assertEquals("NOTAM A1234/26", later.info().get(0));
        assertTrue(later.info().get(1).contains("12 Nov 07:00Z - 12 Nov 16:00Z"), later.info().toString());
    }

    /**
     * 1.0.64: a NOTAMR took the id of the NOTAM it replaces (the last id before Q)), a NOTAMC was drawn for ever as
     * the cancelled one's circle, a FIR-wide Q-line (radius 999) tinted the whole map and took every click, and an
     * estimated end (EST) removed the NOTAM when it passed.
     */
    @Test
    void replacementsCancellationsWideCirclesAndEstimatedEnds() {
        String text = "(A1235/26 NOTAMR A1234/26\n"
                + "Q) ENOR/QRDCA/IV/BO/W/000/040/6012N01107E005\n"
                + "A) ENOR B) 2611120700 C) 2611121600 EST\n"
                + "E) DANGER AREA ACTIVATED.)\n\n"
                + "(A1236/26 NOTAMC A1200/26\n"
                + "Q) ENOR/QRDXX/IV/NBO/W/000/999/6012N01107E005\n"
                + "A) ENOR B) 2611120700\n"
                + "E) CANCELLED.)\n\n"
                + "(A1237/26 NOTAMN\n"
                + "Q) ENOR/QWULW/IV/BO/W/000/999/6455N01400E999\n"
                + "A) ENOR B) 2611120700 C) 2611301600\n"
                + "E) UNMANNED AIRCRAFT ACTIVITY WITHIN THE FIR.)\n";
        List<NotamParser.Notam> n = NotamParser.parse(text);
        assertEquals(1, n.size(), "the cancellation and the FIR-wide one have no area to draw");
        NotamParser.Notam r = n.get(0);
        assertEquals("A1235/26", r.id(), "its own id, not the one it replaces");
        assertTrue(r.endEstimated());
        assertFalse(r.overAt(Instant.parse("2026-11-13T00:00:00Z")), "an estimated end doesn't remove it");
        assertTrue(NotamParser.toAirspace(r, Instant.parse("2026-11-12T08:00:00Z")).info().get(1).contains("EST"));
        assertEquals(List.of("A1234/26", "A1200/26"), NotamParser.replaced(text), "the saved ones that go");
    }

    /**
     * Copied from a web page, a NOTAM's spaces may be no-break or thin ones: one after "Q)" left the NOTAM out
     * ("without an area"), one between a point's latitude and longitude lost E)'s polygon to the Q) circle.
     */
    @Test
    void aNotamCopiedFromAWebPageWithItsOddSpacesIsReadAsTyped() {
        String typed = "A1240/26 NOTAMR A1239/26\n"
                + "Q) ENOR/QRTCA/IV/BO/W/000/020/5955N01045E002\n"
                + "A) ENOR B) 2611110000 C) 2611302359\n"
                + "E) UAS AREA 595500N 0104500E - 595700N 0105000E - 595400N 0105200E.\n"
                + "F) GND G) 2000FT AMSL\n";
        String copied = typed.replace("Q) ", "Q) ").replace("B) ", "B) ").replace("N 0104500E", "N  0104500E")
                .replace("N 0105000E", "N 0105000E").replace(" NOTAMR", " NOTAMR").replace("UAS", "​UAS");
        NotamParser.Notam want = NotamParser.parse(typed).get(0);
        List<NotamParser.Notam> got = NotamParser.parse(copied);
        assertEquals(1, got.size(), "not left out");
        NotamParser.Notam n = got.get(0);
        assertEquals("A1240/26", n.id());
        assertEquals(3, n.ring().size(), "E)'s polygon, not the Q) circle");
        for (int i = 0; i < 3; i++) assertArrayEquals(want.ring().get(i), n.ring().get(i), 1e-9);
        assertEquals(want.start(), n.start());
        assertEquals(want.end(), n.end());
        assertEquals("2000FT AMSL", n.upper());
        assertEquals(List.of("A1239/26"), NotamParser.replaced(copied), "the one it replaces goes too");
    }

    /** The radius of E) in the orders and units NOTAMs give it; before, "500M RADIUS" drew the Q) line's 3 NM. */
    @Test
    void aRadiusIsReadBeforeOrAfterItsWordAndInFeet() {
        String[][] cases = {
                {"UAS OPS WI 500M RADIUS OF PSN 600000N 0110000E.", "500"},
                {"UAS OPS WI 2NM RADIUS OF 600000N 0110000E.", "3704"},
                {"WI 1.5 NM RADIUS CENTRED ON 600000N 0110000E", "2778"},
                {"PSN 600000N 0110000E RADIUS 0,5NM.", "926"},
                {"UAS OPS WITHIN RADIUS 1000FT OF 600000N 0110000E.", "304.8"},
                {"WI A RADIUS OF 2 KM CENTRED ON 600000N 0110000E", "2000"},
        };
        for (String[] c : cases) {
            String text = "A0125/26 NOTAMN\nQ) ENOR/QWULW/IV/BO/W/000/010/6000N01100E003\nA) ENOR B) 2611250600 C) PERM\nE) " + c[0]
                    + "\nF) GND G) 400FT AGL\n";
            List<NotamParser.Notam> n = NotamParser.parse(text);
            assertEquals(1, n.size(), c[0]);
            double north = (n.get(0).ring().get(0)[0] - 60.0) * 111_320.0;
            assertEquals(Double.parseDouble(c[1]), north, 0.5, c[0]);
        }
    }

    /**
     * ICAO's brackets round a NOTAM: the closing one was read as part of G) ("GND-400FT AGL)" on the map), or of E)
     * when there is no G), and the next NOTAM's "(" ended the E) of the one before it.
     */
    @Test
    void aNotamsBracketsAreNotPartOfItsItems() {
        String text = "(A1301/26 NOTAMN\nQ) ENOR/QRDCA/IV/BO/W/000/040/6012N01107E003\nA) ENOR B) 2611120700 C) 2611121600\n"
                + "E) DANGER AREA EN D350 ACTIVATED.)\n\n"
                + "(A1302/26 NOTAMN\nQ) ENOR/QWULW/IV/BO/W/000/010/6000N01100E001\nA) ENOR B) 2611100800 C) PERM\n"
                + "E) UAS ACTIVITY PSN 600000N 0110000E RADIUS 500M.\nF) GND G) 400FT AGL)\n";
        List<NotamParser.Notam> n = NotamParser.parse(text);
        assertEquals(2, n.size());
        assertEquals("DANGER AREA EN D350 ACTIVATED.", n.get(0).text());
        assertEquals("FL40", n.get(0).upper());
        assertEquals("A1302/26", n.get(1).id());
        assertEquals("400FT AGL", n.get(1).upper());
        assertEquals("GND-400FT AGL", NotamParser.toAirspace(n.get(1), Instant.parse("2026-11-12T08:00:00Z")).limits());
        assertTrue(n.get(1).raw().startsWith("(A1302/26") && n.get(1).raw().endsWith(")"), "kept as pasted: " + n.get(1).raw());
        // read back from the saved file, as it is written, and as the file of an earlier version has it (the "(" at
        // the end of the NOTAM before)
        String saved = n.get(0).raw() + "\n\n" + n.get(1).raw() + "\n\n";
        String older = "A1301/26 NOTAMN\nQ) ENOR/QRDCA/IV/BO/W/000/040/6012N01107E003\nA) ENOR B) 2611120700 C) 2611121600\n"
                + "E) DANGER AREA EN D350 ACTIVATED.)\n\n(\n\nA1302/26 NOTAMN\nQ) ENOR/QWULW/IV/BO/W/000/010/6000N01100E001\n"
                + "A) ENOR B) 2611100800 C) PERM\nE) UAS ACTIVITY PSN 600000N 0110000E RADIUS 500M.\nF) GND G) 400FT AGL)\n\n";
        for (String file : new String[]{saved, older}) {
            List<NotamParser.Notam> back = NotamParser.parse(file);
            assertEquals(2, back.size());
            assertEquals("400FT AGL", back.get(1).upper(), file);
            assertEquals(48, back.get(1).ring().size());
        }
    }

    @Test
    void pointsAreReadInTheirForms() {
        assertArrayEquals(new double[]{59 + 55 / 60.0, 10 + 45 / 60.0}, NotamParser.point("5955N01045E"), 1e-9);
        assertArrayEquals(new double[]{59 + 55 / 60.0 + 30 / 3600.0, 10 + 45 / 60.0}, NotamParser.point("595530N 0104500E"), 1e-9);
        assertArrayEquals(new double[]{-(33 + 30 / 60.0), -(70 + 15 / 60.0)}, NotamParser.point("3330S07015W"), 1e-9);
        assertNull(NotamParser.point("RADIUS 2NM"));
        assertNull(NotamParser.area("NO COORDINATES HERE"));
    }
}
