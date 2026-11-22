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

import de.droiddrone.common.UpdateTestSource;

/**
 * THE UPDATE TEST'S BUILDS ONLY - NEVER RELEASED (flight-app/bench-overlay/README.md, "The update test's builds").
 * {@code flight-app\build.ps1 -UpdateTest} lays this file over the released app's own BenchNet (the one that says no),
 * in a source copy of its own.
 * <p>
 * Q-006: the released app answers over Tailscale and nothing else. The bench's update test (PhoneUpdateScenario) runs
 * these builds in the Android emulator too, which has no Tailscale: the PC reaches them there from 10.0.2.2 through the
 * emulator's forwarded port. So in the emulator, and only there, their link takes every address as before Q-006; on a
 * real phone they hold the released app's rule. The emulator is known as the test rig's build knows it (its hardware's
 * name ranchu or goldfish, or its product sdk_...). Said once in the log with the update test's marker.
 */
final class BenchNet {
    /** True in the update test's builds alone. */
    static final boolean BUILT_IN = true;
    private static volatile Boolean emulator;

    private BenchNet() {
    }

    /** The link's rule widened: in the Android emulator, never on a real phone. */
    static boolean wide() {
        Boolean e = emulator;
        if (e == null) {
            String hardware = String.valueOf(android.os.Build.HARDWARE);
            String product = String.valueOf(android.os.Build.PRODUCT);
            e = hardware.equals("ranchu") || hardware.equals("goldfish") || product.startsWith("sdk_") || product.equals("sdk");
            emulator = e;
            log(UpdateTestSource.MARKER + (e ? ": the Android emulator - the link answers every address, as before Q-006 (an update test's build only)"
                    : ": a real phone - the link answers over Tailscale only, as the released app"));
        }
        return e;
    }
}
