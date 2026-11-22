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

/**
 * Q-006 (Tailscale only): the released app's answer to "is the link's rule widened here?" - never. The phone answers
 * over Tailscale and nothing else.
 * <p>
 * {@link PhoneTailscale} asks {@link #BUILT_IN} before it asks anything else of this class. The value is a constant the
 * compiler knows, so the widened rule is not in the compiled app at all; the method below exists only so that
 * PhoneTailscale compiles, and the release build drops it with this class.
 * <p>
 * A test build made for the rig alone (the Android emulator has no Tailscale) has another BenchNet in its own source
 * set, which is not in this folder (the phone app's README says where and why), as with BenchFc. PC test
 * ReleaseHasNoBenchCodeTest holds this file to {@code BUILT_IN = false}.
 */
final class BenchNet {
    /** False in every released app. */
    static final boolean BUILT_IN = false;

    private BenchNet() {
    }

    static boolean wide() {
        return false;
    }
}
