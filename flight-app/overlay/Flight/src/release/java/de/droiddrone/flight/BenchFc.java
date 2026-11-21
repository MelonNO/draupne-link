/*
 *  This file is part of DRAUPNE Flight, the phone app of DRAUPNE Link (based on DroidDrone).
 *
 *  It is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 *  version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details. You should have received a copy of the GNU General Public License along with it. If not, see
 *  <https://www.gnu.org/licenses/>.
 */

package de.droiddrone.flight;

/**
 * The test rig's door to a flight controller - shut. This is the BenchFc of every app that is released: it says
 * "not built in" and does nothing.
 * <p>
 * {@link Serial} asks {@link #BUILT_IN} before each of its few steps that could go another way than USB. The value is
 * a constant the compiler knows, so those steps are not in the compiled app at all; the methods below exist only so
 * that Serial compiles, and the release build drops them with this class.
 * <p>
 * A test build made for the rig alone has another BenchFc in its own source set, which is not in this folder (the
 * phone app's README says where and why). It lives in this build type's source set, not in main, because Android
 * refuses a class that is in main and in a build type both. PC test ReleaseHasNoBenchCodeTest holds this file to
 * {@code BUILT_IN = false}.
 */
final class BenchFc {
    /** False in every released app. */
    static final boolean BUILT_IN = false;

    private BenchFc() {
    }

    static boolean wanted() {
        return false;
    }

    static boolean active() {
        return false;
    }

    static boolean attached() {
        return false;
    }

    static boolean open(Serial serial, int id) {
        return false;
    }

    static boolean write(byte[] data) throws java.io.IOException {
        return false;
    }

    static void close() {
    }
}
