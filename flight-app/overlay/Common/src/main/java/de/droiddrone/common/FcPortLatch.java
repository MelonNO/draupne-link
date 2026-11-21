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

package de.droiddrone.common;

import java.util.function.Supplier;

/**
 * DRAUPNE Flight (F-070a): the settings the flight controller's port is opened and written with, held from one
 * restart of the port to the next.
 * <p>
 * Found by a reader of the code (2026-11-22): the PC's Config packet writes these settings into the phone's Config at
 * once, while the restart of the port that applies them waits until the flight controller reads disarmed (pc.31). But
 * the stick writer asked Config at every write which port to write to: "use the native serial port" changed in flight
 * sent the sticks to a port that was not open - none reached the flight controller from that moment. And a port lost
 * in flight (a loose plug) was looked for again with the new settings.
 * <p>
 * Now the port's users ask here ({@link #inUse}). What is in use changes in one place: where the port is about to be
 * opened ({@link #forOpen}), and only when the restart that applies a change was asked for since
 * ({@link #restartAsked} - asked by the code that waited for the disarm). Whatever Config says meanwhile, a port
 * opened again after it was lost is opened as it was.
 * <p>
 * Pure; any thread may ask (the stick writer reads one volatile field).
 */
public final class FcPortLatch {
    /** What the port is opened with: the phone's own serial port or USB, and how. */
    public static final class Settings {
        public final boolean nativePort;
        public final String nativePath;
        public final int baudRate, usbPortIndex, fcProtocol;

        public Settings(boolean nativePort, String nativePath, int baudRate, int usbPortIndex, int fcProtocol) {
            this.nativePort = nativePort;
            this.nativePath = nativePath;
            this.baudRate = baudRate;
            this.usbPortIndex = usbPortIndex;
            this.fcProtocol = fcProtocol;
        }
    }

    private volatile Settings inUse;
    private volatile boolean restartAsked;

    public FcPortLatch(Settings atStart) {
        inUse = atStart;
    }

    /** The settings of the port that is open, or was last looked for. */
    public Settings inUse() {
        return inUse;
    }

    /** The restart that applies changed settings was asked for: the next opening takes them. */
    public void restartAsked() {
        restartAsked = true;
    }

    /**
     * Before each try to open the port: what to open it with. The settings as they are now ({@code now}, asked only
     * then) when a restart was asked for since the last try, else the ones in use.
     */
    public Settings forOpen(Supplier<Settings> now) {
        if (restartAsked) {
            restartAsked = false; // first: one asked for while the settings are read is taken at the next try
            inUse = now.get();
        }
        return inUse;
    }
}
