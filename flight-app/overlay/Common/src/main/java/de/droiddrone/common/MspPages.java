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

import java.util.Arrays;

/**
 * DRAUPNE Flight: Betaflight's MSP_BOXNAMES and MSP_BOXIDS come in pages ({@link FcCommon#BF_BOXMODES_PAGE_COUNT}),
 * one request each; gathered here until the last (the phone's Msp had the same 45 lines twice). A reply that repeats
 * a page already taken this round is no new page; a round ends at the last page, or at an empty one after the second.
 * One thread.
 */
public final class MspPages {
    private final byte[][] pages = new byte[FcCommon.BF_BOXMODES_PAGE_COUNT][];
    private byte page;
    private byte[] joined;

    /** The page to ask for next. */
    public byte page() {
        return page;
    }

    /** The next round starts at the first page. */
    public void restart() {
        page = 0;
    }

    /**
     * A page's reply (null when empty). True when the round is complete: {@link #joined()} then has the pages, in order,
     * as one.
     */
    public boolean add(byte[] pageData) {
        for (int i = 0; i < page; i++) {
            if (Arrays.equals(pages[i], pageData)) return false;
        }
        pages[page] = pageData;
        page++;
        if (page != FcCommon.BF_BOXMODES_PAGE_COUNT && !(pageData == null && page > 1)) return false;
        page = 0;
        int total = 0;
        for (byte[] p : pages) if (p != null) total += p.length;
        joined = null;
        if (total > 0) {
            joined = new byte[total];
            int offset = 0;
            for (byte[] p : pages) {
                if (p == null) continue;
                System.arraycopy(p, 0, joined, offset, p.length);
                offset += p.length;
            }
        }
        return true;
    }

    /** The pages of the round {@link #add} completed, as one; null when they were all empty. */
    public byte[] joined() {
        return joined;
    }
}
