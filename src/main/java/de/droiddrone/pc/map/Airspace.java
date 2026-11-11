package de.droiddrone.pc.map;

import java.util.List;

/**
 * 1.0.64 (the pilot: "displaying D-areas and NOTAMS and R-areas in the map"): one area drawn on the map - from openAIP
 * (restricted, prohibited, danger) or from a NOTAM pasted from IPPC.no.
 * @param ring       its edge, each {lat, lon}
 * @param lower      its floor as said ("GND", "1500 FT AMSL", "FL95"), or ""
 * @param upper      its ceiling as said, or ""
 * @param info       what a click on it shows (name, kind, limits; a NOTAM's times and text), lines
 * @param later      a NOTAM not active yet (starts within a day): drawn dashed
 */
public record Airspace(String id, String name, Kind kind, List<double[]> ring, String lower, String upper, List<String> info, boolean later) {
    public enum Kind {
        RESTRICTED("R-AREA"), PROHIBITED("P-AREA"), DANGER("D-AREA"), NOTAM("NOTAM");

        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** "GND-3500 FT" for the label, or "" when neither is known. */
    public String limits() {
        if (lower.isEmpty() && upper.isEmpty()) return "";
        return (lower.isEmpty() ? "?" : lower) + "-" + (upper.isEmpty() ? "?" : upper);
    }

    /** The middle of its edge's bounding box {lat, lon}, for its label. */
    public double[] middle() {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] p : ring) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        return new double[]{(minLat + maxLat) / 2, (minLon + maxLon) / 2};
    }

    /** Whether any of its edge lies in the box {minLat, maxLat, minLon, maxLon} (or the box lies in it, roughly). */
    public boolean touches(double[] box) {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] p : ring) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        return maxLat >= box[0] && minLat <= box[1] && maxLon >= box[2] && minLon <= box[3];
    }

    /** A circle's edge: 48 points round {lat, lon} at radiusM. */
    public static List<double[]> circle(double lat, double lon, double radiusM) {
        List<double[]> out = new java.util.ArrayList<>(48);
        double dLat = radiusM / 111_320.0, dLon = radiusM / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        for (int i = 0; i < 48; i++) {
            double a = 2 * Math.PI * i / 48;
            out.add(new double[]{lat + dLat * Math.cos(a), lon + dLon * Math.sin(a)});
        }
        return out;
    }
}
