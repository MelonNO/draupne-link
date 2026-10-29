/*
 *  This file is part of DroidDrone PC's build of DroidDrone Flight.
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

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/** Card background like the Windows app's: dark panel, thin outline, red brackets in the corners. */
final class BracketDrawable extends Drawable {
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint bracket = new Paint();
    private final float arm;

    BracketDrawable(int panel, int lineColor, int accent, float density) {
        fill.setColor(panel);
        line.setColor(lineColor);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, density));
        bracket.setColor(accent);
        bracket.setStyle(Paint.Style.STROKE);
        bracket.setStrokeWidth(2f * density);
        arm = 12f * density;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float h = bracket.getStrokeWidth() / 2;
        float l = b.left + h, t = b.top + h, r = b.right - h, bo = b.bottom - h;
        c.drawRect(b, fill);
        c.drawRect(l, t, r, bo, line);
        c.drawLine(l, t, l + arm, t, bracket);
        c.drawLine(l, t, l, t + arm, bracket);
        c.drawLine(r, t, r - arm, t, bracket);
        c.drawLine(r, t, r, t + arm, bracket);
        c.drawLine(l, bo, l + arm, bo, bracket);
        c.drawLine(l, bo, l, bo - arm, bracket);
        c.drawLine(r, bo, r - arm, bo, bracket);
        c.drawLine(r, bo, r, bo - arm, bracket);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
