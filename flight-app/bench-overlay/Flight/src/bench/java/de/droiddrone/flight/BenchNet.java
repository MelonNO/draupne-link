/*
 *  This file is part of DRAUPNE Flight, based on DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

/**
 * THE TEST RIG'S BUILD ONLY (flight-app/bench-overlay/README.md). NEVER RELEASED.
 * <p>
 * Q-006: the released app answers over Tailscale and nothing else (TailscaleOnly). The Android emulator has no
 * Tailscale: the bench's PC reaches the app there through the emulator's own forwarded port, from 10.0.2.2 (older
 * images through a router of their own). So in the emulator, and only there, this build's link takes every address
 * as the app did before Q-006 - its socket on every address at the link port, every sender heard and answered. On a
 * real phone this build holds the released app's rule: there it is reached over Tailscale like any drone.
 * <p>
 * The emulator is known by its hardware's name (ranchu; goldfish before it) or its product (sdk_...): no phone that
 * flies has either. Read once, said once in the log with the rig's marker.
 */
final class BenchNet {
    /** True in the test rig's build alone. */
    static final boolean BUILT_IN = true;
    private static volatile Boolean emulator;

    private BenchNet() {
    }

    /** The link's rule widened: in the Android emulator, never on a real phone. */
    static boolean wide() {
        Boolean e = emulator;
        if (e == null) {
            e = inTheEmulator();
            emulator = e;
            log(BenchFc.MARKER + (e ? ": the Android emulator - the link answers every address, as before Q-006 (the test rig's build only)"
                    : ": a real phone - the link answers over Tailscale only, as the released app"));
        }
        return e;
    }

    private static boolean inTheEmulator() {
        String hardware = String.valueOf(android.os.Build.HARDWARE);
        String product = String.valueOf(android.os.Build.PRODUCT);
        return hardware.equals("ranchu") || hardware.equals("goldfish") || product.startsWith("sdk_") || product.equals("sdk");
    }
}
