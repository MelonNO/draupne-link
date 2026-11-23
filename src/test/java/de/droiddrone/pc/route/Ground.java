package de.droiddrone.pc.route;

import java.util.function.DoubleBinaryOperator;

/** Made-up terrain for the route tests: heights as a function of metres east / north of a place in Norway. */
final class Ground implements TerrainSource {
    static final double LAT0 = 60.0, LON0 = 10.0;
    /** As the app measures (Mercator.distanceM: a sphere of 6371008.8 m). */
    static final double M_PER_DEG_LAT = Math.toRadians(1) * 6371008.8;
    static final double M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos(Math.toRadians(LAT0));

    private final DoubleBinaryOperator heightOfEastNorth;

    Ground(DoubleBinaryOperator heightOfEastNorth) {
        this.heightOfEastNorth = heightOfEastNorth;
    }

    /** {lat, lon} of a place east / north metres from the origin. */
    static double[] at(double eastM, double northM) {
        return new double[]{LAT0 + northM / M_PER_DEG_LAT, LON0 + eastM / M_PER_DEG_LON};
    }

    static double east(double lon) {
        return (lon - LON0) * M_PER_DEG_LON;
    }

    static double north(double lat) {
        return (lat - LAT0) * M_PER_DEG_LAT;
    }

    @Override
    public double heightM(double lat, double lon) {
        return heightOfEastNorth.applyAsDouble(east(lon), north(lat));
    }

    static Ground flat(double h) {
        return new Ground((e, n) -> h);
    }

    /** A steep north-south ridge at 500 m east: 100 m in the valley, 250 m on top, sides up to ~65 degrees. */
    static Ground ridge() {
        return new Ground((e, n) -> 100 + 150 * Math.exp(-Math.pow((e - 500) / 60, 2)));
    }

    /** A sharp step: 100 m, then a 40 m cliff at 300 m east. */
    static Ground cliff() {
        return new Ground((e, n) -> e < 300 ? 100 : 140);
    }

    /** Gentle bumps, 3.6 m from lowest to highest on 300 m (a line 5 m smoothing allows need not follow them). */
    static Ground bumpy() {
        return new Ground((e, n) -> 300 + 1.0 * Math.sin(e / 25) + 0.8 * Math.cos(e / 11));
    }

    static Route route(double[]... eastNorth) {
        Route r = new Route("TEST");
        for (double[] p : eastNorth) {
            double[] ll = at(p[0], p[1]);
            r.add(ll[0], ll[1]);
        }
        return r;
    }
}
