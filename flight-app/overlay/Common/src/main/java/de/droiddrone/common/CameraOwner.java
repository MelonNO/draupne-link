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

package de.droiddrone.common;

/**
 * DRAUPNE Flight pc.38 (the review's M19): who started the phone's camera last (moved out of its Udp, unchanged),
 * counted on the video starter thread - every video start (a pilot's StartVideo, a restart, observation) and
 * overwatch's camera on take a new number. Overwatch's camera off, its camera opened again and its event video act
 * only while its number is still the last: ending overwatch slowly (a long clip hashed) once let its off run after the
 * pilot's StartVideo and close the new camera.
 * <p>
 * Changed on one thread (the video starter's); volatile for the thread that only asks ({@link #isOverwatchs}: the
 * video's dead-man on the telemetry thread). The PC's tests check it.
 */
public final class CameraOwner {
    private volatile int owner, overwatchs = -1;

    /**
     * A video start: the camera is that stream's now - overwatch's late camera off or event video leaves it alone.
     * @return it was overwatch's until now: what overwatch set on it (watch-only, its recorder) goes
     */
    public boolean videoTakes() {
        boolean wasOverwatch = overwatchs == owner;
        owner++;
        return wasOverwatch;
    }

    /** Overwatch's camera on: the camera is overwatch's from here. */
    public void overwatchTakes() {
        overwatchs = ++owner;
    }

    /** Overwatch closes its camera (asked {@link #isOverwatchs} first): nobody's. */
    public void overwatchLetsGo() {
        overwatchs = -1;
    }

    /** The camera is still the one overwatch started: no video start took it since. */
    public boolean isOverwatchs() {
        return overwatchs == owner;
    }
}
