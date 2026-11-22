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

import java.io.File;
import java.io.IOException;

/**
 * DRAUPNE Flight: the phone's recordings (upstream's Video folder) and overwatch clips (Overwatch) stay out of the
 * phone's gallery. Both lie in the app's media folder (Android/media/&lt;the app&gt;/), which Android's media index reads
 * for every gallery app on the phone: picture and sound of every recording and every clip were listed there. A
 * {@code .nomedia} file in each folder keeps the index from listing them; the files stay where they were - the PC's clip
 * download reads the folder as before, and they are reached over USB at the same path.
 * <p>
 * Pure: the phone marks its folders at the service's start; the PC's tests check it.
 */
public final class NoMedia {
    /** The marker's name: Android's media index leaves a folder that holds it, and every folder under it, out. */
    public static final String MARKER = ".nomedia";

    private NoMedia() {
    }

    /**
     * The folder (made if it is not there) and its marker.
     * @return true when the marker was made now - the media index is to look at the folder again, once (its earlier
     *         entries go); false when it was there already, or could not be made
     */
    public static boolean mark(File dir) {
        if (dir == null || !dir.isDirectory() && !dir.mkdirs()) return false;
        File marker = new File(dir, MARKER);
        if (marker.exists()) return false;
        try {
            return marker.createNewFile();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
