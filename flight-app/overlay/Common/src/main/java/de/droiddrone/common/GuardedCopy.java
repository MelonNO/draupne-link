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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * DRAUPNE Flight pc.38 (the review's H9): an update's download, asked again before every chunk whether it may go on.
 * A remote update used to check the flight controller only before the download and before the install, so 50 MB
 * went on over 4G beside the sticks once the drone was armed. Now the download stops at the first chunk after the
 * guard says no. Pure Java (the PC's tests drive it).
 */
public final class GuardedCopy {
    /** The guard said no: nothing more was read. */
    public static final class Stopped extends IOException {
        public Stopped(String why) {
            super(why);
        }
    }

    private GuardedCopy() {
    }

    /**
     * Copies in to out, the digest updated with every byte, the guard asked before each read.
     * @param total the length expected, or -1 unknown (no percent then)
     * @param percent told every 5 % (may be null)
     * @param mayGoOn the guard; false stops the copy with {@link Stopped}
     * @param why the Stopped exception's text
     * @return the bytes copied
     */
    public static long copy(InputStream in, OutputStream out, MessageDigest digest, long total, IntConsumer percent,
                            BooleanSupplier mayGoOn, String why) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int shown = -1;
        while (true) {
            if (!mayGoOn.getAsBoolean()) throw new Stopped(why);
            int n = in.read(buf);
            if (n <= 0) break;
            out.write(buf, 0, n);
            if (digest != null) digest.update(buf, 0, n);
            done += n;
            int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
            if (pct / 5 != shown / 5) {
                shown = pct;
                if (percent != null) percent.accept(pct);
            }
        }
        return done;
    }
}
