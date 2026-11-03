/*
 *  This file is part of DRAUPNE Link's build of DRAUPNE Flight.
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The start, as on the PC: a black screen, the DRAUPNE mark fades in fast in the middle, then comes apart - the
 * four brackets fly to the screen's corners (the cards' bracket corners), the link between the bars folds away, the
 * crosshair shrinks out - as the black lifts and the app shows through. About 3 s; a tap skips it. The mark is
 * drawn from its SVG (a 512 x 512 design) in the icon's red.
 */
final class BootRevealView extends View {
    private static final long FADE_IN_MS = 560, HOLD_MS = 320, FLY_MS = 1560, OUT_MS = 560;
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FLY_MS + OUT_MS;
    private static final int RED = 0xFFFB1921, DARK = 0xFF121212, BLACK = 0xFF000000;
    private static final float BOX = 512f;

    private final long startedAt = SystemClock.uptimeMillis();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private boolean done;
    /** What the mark's pieces become: the D's bars the CONNECT button, its diagonals the LOCK SCREEN button's outline, the crosshair the pill. */
    private View connect, lock, pill;
    private final int[] where = new int[2], here = new int[2];
    private final RectF rect = new RectF();

    private BootRevealView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setClickable(true); // takes the taps meanwhile: one skips it
    }

    /** Over the activity's content, from its first frame; gone when done. */
    static void show(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        BootRevealView v = new BootRevealView(activity);
        v.connect = activity.findViewById(R.id.startStopService);
        v.lock = activity.findViewById(R.id.lockScreen);
        v.pill = activity.findViewById(R.id.tvPill);
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.bringToFront();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }

    private void finish() {
        if (done) return;
        done = true;
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    private static float ease(float p) {
        p = Math.max(0, Math.min(1, p));
        return p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (done) return;
        long t = SystemClock.uptimeMillis() - startedAt;
        if (t >= TOTAL_MS) {
            post(this::finish);
            return;
        }
        int w = getWidth(), h = getHeight();
        float size = Math.min(w, h) * 0.5f;
        float lx = (w - size) / 2, ly = (h - size) / 2;

        if (t < FADE_IN_MS + HOLD_MS) {
            canvas.drawColor(BLACK);
            float alpha = Math.min(1f, t / (float) FADE_IN_MS);
            drawAll(canvas, lx, ly, size, alpha);
        } else {
            float fly = ease((t - FADE_IN_MS - HOLD_MS) / (float) FLY_MS);
            float out = t < FADE_IN_MS + HOLD_MS + FLY_MS ? 0 : (t - FADE_IN_MS - HOLD_MS - FLY_MS) / (float) OUT_MS;
            float pieceAlpha = 1 - out;
            // the black lifts as the pieces fly
            canvas.drawColor(Color.argb(Math.round(255 * (1 - fly)), 0, 0, 0));
            // the D comes apart: its bars become the CONNECT button, its diagonals the LOCK SCREEN button's outline;
            // without those on screen yet, the link between the bars folds into the middle and goes
            if (!drawD(canvas, lx, ly, size, fly, pieceAlpha)) {
                float linkSize = size * (1 - fly);
                if (linkSize > 2) drawLink(canvas, (w - linkSize) / 2, (h - linkSize) / 2, linkSize, (1 - fly) * pieceAlpha);
            }
            // the brackets to the screen's corners, the size of the cards' brackets
            float cornerSize = 150 * density * 0.6f;
            float cs = lerp(size, cornerSize, fly);
            float inset = -cs * 72 / BOX + 4 * density; // the bracket's own margin in its box, minus a little
            for (int corner = 0; corner < 4; corner++) {
                boolean right = corner == 1 || corner == 3, bottom = corner >= 2;
                float tx = right ? w - cs - inset : inset, ty = bottom ? h - cs - inset : inset;
                drawBracket(canvas, corner, lerp(lx, tx, fly), lerp(ly, ty, fly), cs, pieceAlpha);
            }
            // the crosshair flies to the status pill and shrinks to its height, where it fades; no pill yet: it
            // stays in the middle, shrinking out
            float travel = ease(Math.min(1f, fly / 0.85f));
            if (rectOf(pill)) {
                float chSize = lerp(size, rect.height() * 512 / 88f, travel); // the disc (88 of 512) ends the pill's height
                float cx = lerp(w / 2f, rect.centerX(), travel), cy = lerp(h / 2f, rect.centerY(), travel);
                drawCrosshair(canvas, cx - chSize / 2, cy - chSize / 2, chSize, pieceAlpha * (1 - 0.6f * travel));
            } else {
                float chSize = size * (1 - fly * 0.7f);
                drawCrosshair(canvas, (w - chSize) / 2, (h - chSize) / 2, chSize, pieceAlpha * (1 - fly));
            }
        }
        postInvalidateOnAnimation();
    }

    /** A view's bounds in this view's coordinates into {@link #rect}; false when it has none yet. */
    private boolean rectOf(View v) {
        if (v == null || v.getWidth() == 0 || v.getHeight() == 0 || !v.isShown()) return false;
        v.getLocationInWindow(where);
        getLocationInWindow(here);
        rect.set(where[0] - here[0], where[1] - here[1], where[0] - here[0] + v.getWidth(), where[1] - here[1] + v.getHeight());
        return true;
    }

    /**
     * The D of the mark coming apart: its two bars (42 x 236 in the 512 box, at x 137 and 333) fly to the left and
     * right halves of the CONNECT button and fill it red (the button is red: it is there when they fade); its two
     * diagonals (the X clipped to the bars' box: (158,138)-(354,374) and its mirror) swing level and stretch into the
     * LOCK SCREEN button's outline. false when those buttons aren't laid out yet.
     */
    private boolean drawD(Canvas c, float lx, float ly, float size, float fly, float pieceAlpha) {
        if (!rectOf(connect)) return false;
        float cl = rect.left, ct = rect.top, cw = rect.width(), ch = rect.height();
        if (!rectOf(lock)) return false;
        float ll = rect.left, lt = rect.top, lw = rect.width(), lh = rect.height();
        float s = size / BOX;
        float travel = ease(Math.min(1f, fly / 0.85f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(red(pieceAlpha));
        float[][] bars = {{137, 138, 42, 236}, {333, 138, 42, 236}};
        for (int i = 0; i < 2; i++) {
            float tx = i == 0 ? cl : cl + cw / 2, tw = cw / 2;
            float x = lerp(lx + bars[i][0] * s, tx, travel), y = lerp(ly + bars[i][1] * s, ct, travel);
            float bw = lerp(bars[i][2] * s, tw, travel), bh = lerp(bars[i][3] * s, ch, travel);
            rect.set(x, y, x + bw, y + bh);
            c.drawRoundRect(rect, 4 * density * travel, 4 * density * travel, paint);
        }
        float[][] diagonals = {{158, 138, 354, 374}, {158, 374, 354, 138}};
        float morph = ease(Math.max(0, (fly - 0.1f) / 0.9f));
        for (int i = 0; i < 2; i++) {
            float x1 = lx + diagonals[i][0] * s, y1 = ly + diagonals[i][1] * s, x2 = lx + diagonals[i][2] * s, y2 = ly + diagonals[i][3] * s;
            float length0 = (float) Math.hypot(x2 - x1, y2 - y1), angle0 = (float) Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
            float cx = lerp((x1 + x2) / 2, ll + lw / 2, morph), cy = lerp((y1 + y2) / 2, lt + lh / 2, morph);
            float length = lerp(length0, lw, morph), thick = lerp(42 * s, lh, morph);
            c.save();
            c.translate(cx, cy);
            c.rotate(angle0 * (1 - morph)); // level on arrival
            rect.set(-length / 2, -thick / 2, length / 2, thick / 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(red((1 - morph) * pieceAlpha));
            c.drawRoundRect(rect, 4 * density * morph, 4 * density * morph, paint); // solid while it is the mark's stroke
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setColor(red(morph * pieceAlpha));
            c.drawRoundRect(rect, 4 * density * morph, 4 * density * morph, paint); // the button's outline as it arrives
            c.restore();
        }
        return true;
    }

    private void drawAll(Canvas c, float x, float y, float size, float alpha) {
        for (int corner = 0; corner < 4; corner++) drawBracket(c, corner, x, y, size, alpha);
        drawLink(c, x, y, size, alpha);
        drawCrosshair(c, x, y, size, alpha);
    }

    private int red(float alpha) {
        return Color.argb(Math.round(255 * Math.max(0, Math.min(1, alpha))), 0xFB, 0x19, 0x21);
    }

    /** One corner bracket of the mark whose box is at x, y with this size. */
    private void drawBracket(Canvas c, int corner, float x, float y, float size, float alpha) {
        float s = size / BOX;
        path.reset();
        if (corner == 0) {
            path.moveTo(72, 130); path.lineTo(72, 72); path.lineTo(130, 72);
        } else if (corner == 1) {
            path.moveTo(440, 130); path.lineTo(440, 72); path.lineTo(382, 72);
        } else if (corner == 2) {
            path.moveTo(72, 382); path.lineTo(72, 440); path.lineTo(130, 440);
        } else {
            path.moveTo(440, 382); path.lineTo(440, 440); path.lineTo(382, 440);
        }
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(22);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setColor(red(alpha));
        c.drawPath(path, paint);
        c.restore();
    }

    /** The X between the two bars. */
    private void drawLink(Canvas c, float x, float y, float size, float alpha) {
        float s = size / BOX;
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        paint.setColor(red(alpha));
        c.save();
        c.clipRect(137, 138, 375, 374);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(42);
        c.drawLine(106.888f, 76.457f, 405.112f, 435.543f, paint);
        c.drawLine(106.888f, 435.543f, 405.112f, 76.457f, paint);
        c.restore();
        paint.setStyle(Paint.Style.FILL);
        c.drawRect(137, 138, 179, 374, paint);
        c.drawRect(333, 138, 375, 374, paint);
        c.restore();
    }

    /** The crosshair, on its dark disc. */
    private void drawCrosshair(Canvas c, float x, float y, float size, float alpha) {
        float s = size / BOX;
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(255 * Math.max(0, Math.min(1, alpha))), 0x12, 0x12, 0x12));
        c.drawCircle(256, 256, 44, paint);
        paint.setColor(red(alpha));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10);
        c.drawCircle(256, 256, 24, paint);
        c.drawLine(267, 256, 295, 256, paint);
        c.drawLine(256, 267, 256, 295, paint);
        c.drawLine(245, 256, 217, 256, paint);
        c.drawLine(256, 245, 256, 217, paint);
        c.restore();
    }
}
