package de.droiddrone.pc.gui;

import de.droiddrone.pc.map.MapGraphics;
import de.droiddrone.pc.map.MapGraphics.Affiliation;
import de.droiddrone.pc.map.MapGraphics.Echelon;
import de.droiddrone.pc.map.MapGraphics.UnitType;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A unit symbol on the map to MIL-STD-2525D / APP-6D (1.0.36). The geometry is the standard's, in its 200 x 200
 * symbol space (the frame's centre at 100,100), scaled to a fixed size on screen whatever the zoom: the frame by
 * affiliation and domain (land units a rectangle / diamond / square / quatrefoil, drones the air frames - the
 * arch, the chevron, the open box, the lobed arch - open at the bottom where the standard leaves them open), the
 * standard's light fill colours, the main icon in the frame's centre, sector-1 amplifiers above it (the drones'
 * role letter), the echelon amplifier above the frame, the headquarters' staff down from the frame's left bottom
 * corner, and the name under it all. The paths are the standard's as spatialillusions' milsymbol writes them.
 */
final class UnitSymbol {
    /** Screen pixels per unit of the standard's 200-unit symbol space: a friendly land frame is 45 x 30 px. */
    static final double SCALE = 0.3;
    private static final Color BOX = new Color(10, 10, 10, 215);
    private static final Color HALO = new Color(0, 0, 0, 120);
    /** The standard's icon stroke: 3 units. */
    private static final float ICON_STROKE = 3f;
    private static final Font ICON_FONT = new Font("Arial", Font.BOLD, 45);

    private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)");
    private static final Map<String, Path2D> PATHS = new ConcurrentHashMap<>();

    /** Land frames (the fill's and stroke's outline). */
    private static final Map<Affiliation, String> LAND_FRAME = new EnumMap<>(Map.of(
            Affiliation.FRIEND, "M25,50 l150,0 0,100 -150,0 z",
            Affiliation.HOSTILE, "M 100,28 L172,100 100,172 28,100 100,28 Z",
            Affiliation.NEUTRAL, "M45,45 l110,0 0,110 -110,0 z",
            Affiliation.UNKNOWN, "M63,63 C63,20 137,20 137,63 C180,63 180,137 137,137 C137,180 63,180 63,137 C20,137 20,63 63,63 Z"));
    /** Air frames: friend and unknown stay open at the bottom (the fill closes them, the stroke does not). */
    private static final Map<Affiliation, String> AIR_FRAME = new EnumMap<>(Map.of(
            Affiliation.FRIEND, "M 155,150 C 155,50 115,30 100,30 85,30 45,50 45,150",
            Affiliation.HOSTILE, "M 45,150 L45,70 100,20 155,70 155,150",
            Affiliation.NEUTRAL, "M 45,150 L 45,30,155,30,155,150",
            Affiliation.UNKNOWN, "M 65,150 c -55,0 -50,-90 0,-90 0,-50 70,-50 70,0 50,0 55,90 0,90"));

    /** One drawn part of an icon: a path (filled and / or stroked) or a text amplifier centred on (100, y). */
    private record Part(String d, boolean fill, boolean stroke, String text, double y, double size) {
        static Part path(String d) {
            return new Part(d, true, true, null, 0, 0);
        }

        static Part outline(String d) {
            return new Part(d, false, true, null, 0, 0);
        }

        static Part solid(String d) {
            return new Part(d, true, false, null, 0, 0);
        }

        /** The standard's sector-1 text amplifier (milsymbol's textm1). */
        static Part sector1(String text) {
            return new Part(null, false, false, text, 71, 28);
        }
    }

    private static final String ARMOUR = "M125,80 C150,80 150,120 125,120 L75,120 C50,120 50,80 75,80 Z";
    private static final String UAV = "m 60,84 40,20 40,-20 0,8 -40,25 -40,-25 z";
    private static final String VT_UAV = "m 70,84 30,14 30,-14 V 81 L 100,91 70,81 Z  m 0,9 30,15 30,-15 v 25 l -30,-10 -30,10 z";

    private UnitSymbol() {
    }

    /** Drones are air-domain symbols: the air frames and icons. */
    static boolean isAir(UnitType unit) {
        return switch (unit) {
            case ISR_FIXED, ATTACK_FIXED, ISR_ROTOR, ATTACK_ROTOR, UTILITY -> true;
            default -> false;
        };
    }

    /** The frame's tight box in symbol space. */
    private static Rectangle2D frameBox(boolean air, Affiliation affiliation) {
        return path((air ? AIR_FRAME : LAND_FRAME).get(affiliation)).getBounds2D();
    }

    /** The frame's bounds on screen for a symbol centred at (cx, cy): the hit area. */
    static Rectangle bounds(int cx, int cy, UnitType unit, Affiliation affiliation) {
        Rectangle2D b = frameBox(isAir(unit), affiliation);
        int x = cx + (int) Math.round((b.getX() - 100) * SCALE), y = cy + (int) Math.round((b.getY() - 100) * SCALE);
        return new Rectangle(x, y, (int) Math.round(b.getWidth() * SCALE), (int) Math.round(b.getHeight() * SCALE));
    }

    static void paint(Graphics2D g0, int cx, int cy, UnitType unit, Affiliation affiliation, Echelon echelon, String name) {
        boolean air = isAir(unit);
        Path2D frame = path((air ? AIR_FRAME : LAND_FRAME).get(affiliation));
        Rectangle2D box = frame.getBounds2D();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.translate(cx, cy);
            g.scale(SCALE, SCALE);
            g.translate(-100, -100);
            g.setColor(HALO);
            g.setStroke(stroke(4 / SCALE));
            g.draw(frame);
            g.setColor(new Color(affiliation.fillRgb));
            g.fill(frame);
            g.setColor(Color.BLACK);
            g.setStroke(stroke(1.6 / SCALE));
            g.draw(frame);
            if (unit == UnitType.HQ) g.draw(path(String.format(Locale.ROOT, "M%s,%s l0,60", box.getX(), box.getMaxY())));
            g.setStroke(new BasicStroke(ICON_STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            for (Part part : icon(unit, affiliation, box)) {
                if (part.text() != null) {
                    g.setFont(ICON_FONT.deriveFont((float) part.size()));
                    FontMetrics fm = g.getFontMetrics();
                    // the standard centres the text on y: a capital's middle is about 0.36 em above its baseline
                    g.drawString(part.text(), (float) (100 - fm.stringWidth(part.text()) / 2.0), (float) (part.y() + part.size() * 0.36));
                } else {
                    Path2D p = path(part.d());
                    if (part.fill()) g.fill(p);
                    if (part.stroke()) g.draw(p);
                }
            }
            if (echelon != null) echelon(g, echelon, box.getY());
        } finally {
            g.dispose();
        }
        if (name != null && !name.isEmpty()) {
            Rectangle b = bounds(cx, cy, unit, affiliation);
            g0.setFont(Theme.headingFont(11f));
            FontMetrics fm = g0.getFontMetrics();
            String text = name.toUpperCase(Locale.ROOT);
            int tw = fm.stringWidth(text) + 10;
            int x = cx - tw / 2, y = b.y + b.height + (unit == UnitType.HQ ? (int) Math.round(60 * SCALE) + 2 : 4);
            g0.setColor(BOX);
            g0.fillRect(x, y, tw, fm.getHeight() + 2);
            g0.setColor(Theme.TEXT);
            g0.drawString(text, x + 5, y + 1 + fm.getAscent());
        }
    }

    private static BasicStroke stroke(double widthUnits) {
        return new BasicStroke((float) widthUnits, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /** The icon's parts in symbol space; the land icons are drawn to fit the affiliation's frame, as the standard draws them. */
    private static List<Part> icon(UnitType unit, Affiliation aff, Rectangle2D box) {
        List<Part> parts = new ArrayList<>();
        switch (unit) {
            case INFANTRY -> parts.add(Part.outline(byAffiliation(aff,
                    "M25,50 L175,150 M25,150 L175,50", "M60,70L140,130M60,130L140,70", "M45,45L155,155M45,155L155,45", "M50,65L150,135M50,135L150,65")));
            case MECHANIZED -> {
                parts.add(Part.outline(byAffiliation(aff,
                        "M25,50 L175,150 M25,150 L175,50", "M60,70L140,130M60,130L140,70", "M45,45L155,155M45,155L155,45", "M50,65L150,135M50,135L150,65")));
                parts.add(Part.outline(ARMOUR));
            }
            case ARMOUR -> parts.add(Part.outline(ARMOUR));
            case ARTILLERY -> parts.add(Part.path("M100,85 c8.3,0 15,6.7 15,15 0,8.3 -6.7,15 -15,15 -8.3,0 -15,-6.7 -15,-15 0,-8.3 6.7,-15 15,-15 z"));
            case RECON -> parts.add(Part.outline(byAffiliation(aff, "M25,150L175,50", "M60,130L140,70", "M45,155L155,45", "M50,135L150,65")));
            case ANTI_TANK -> parts.add(Part.outline(byAffiliation(aff,
                    "M25,150 L100,52 175,150", "M60,132 L100,30 140,132", "M45,150 L100,47 155,150", "M55,135 L100,33 145,135")));
            case ENGINEER -> parts.add(Part.outline("M 60,118 V 83 h 80 v 35 M 100,83 V 110"));
            case AIR_DEFENCE -> parts.add(Part.outline(byAffiliation(aff,
                    "M25,150 C25,110 175,110 175,150", "M70,140 C70,115 130,115 130,140", "M45,150 C45,110 155,110 155,150", "m 55,135 c 10,-20 80,-20 90,0")));
            case ISR_FIXED -> {
                parts.add(Part.solid(UAV));
                parts.add(Part.sector1("R"));
            }
            case ATTACK_FIXED -> {
                parts.add(Part.solid(UAV));
                parts.add(Part.sector1("A"));
            }
            case ISR_ROTOR -> {
                parts.add(Part.path(VT_UAV));
                parts.add(Part.sector1("R"));
            }
            case ATTACK_ROTOR -> {
                parts.add(Part.path(VT_UAV));
                parts.add(Part.sector1("A"));
            }
            case UTILITY -> {
                parts.add(Part.solid(UAV));
                parts.add(Part.sector1("U"));
            }
            case SUPPLY -> parts.add(Part.outline(byAffiliation(aff, "M25,120 l150,0", "M50,120 l100,0", "M45,120 l110,0", "M35,120 l130,0")));
            case MEDIC -> parts.add(Part.outline(String.format(Locale.ROOT, "M100,%s L100,%s M%s,100 L%s,100",
                    box.getY(), box.getMaxY(), box.getX(), box.getMaxX())));
            case HQ -> parts.add(Part.outline(byAffiliation(aff, "M25,80 l150,0", "M50,80 l100,0", "M45,80 l110,0", "M35,80 l130,0")));
            case UNIT -> {
            }
        }
        return parts;
    }

    private static String byAffiliation(Affiliation aff, String friend, String hostile, String neutral, String unknown) {
        return switch (aff) {
            case FRIEND -> friend;
            case HOSTILE -> hostile;
            case NEUTRAL -> neutral;
            case UNKNOWN -> unknown;
        };
    }

    /**
     * The echelon amplifier above the frame, placed from the frame's top (y1) as the standard does: team a slashed
     * circle, squad / section / platoon one to three dots, company / battalion / regiment one to three bars, brigade
     * / division one or two crosses. Drawn with a white halo first so it reads on a dark map.
     */
    private static void echelon(Graphics2D g, Echelon echelon, double y1) {
        List<Object> shapes = new ArrayList<>(); // Path2D to stroke, Ellipse2D to fill
        switch (echelon) {
            case TEAM -> {
                shapes.add(new Ellipse2D.Double(85, y1 - 35, 30, 30));
                shapes.add(path(String.format(Locale.ROOT, "M80,%s L120,%s", y1 - 10, y1 - 30)));
            }
            case SQUAD -> shapes.add(dot(100, y1));
            case SECTION -> {
                shapes.add(dot(85, y1));
                shapes.add(dot(115, y1));
            }
            case PLATOON -> {
                shapes.add(dot(70, y1));
                shapes.add(dot(100, y1));
                shapes.add(dot(130, y1));
            }
            case COMPANY -> shapes.add(bars(y1, 100));
            case BATTALION -> shapes.add(bars(y1, 90, 110));
            case REGIMENT -> shapes.add(bars(y1, 80, 100, 120));
            case BRIGADE -> shapes.add(crosses(y1, 87.5));
            case DIVISION -> shapes.add(crosses(y1, 70, 105));
        }
        for (int pass = 0; pass < 2; pass++) {
            g.setColor(pass == 0 ? Color.WHITE : Color.BLACK);
            g.setStroke(stroke(pass == 0 ? 9 : ICON_STROKE));
            for (Object s : shapes) {
                if (s instanceof Ellipse2D e) {
                    if (e.getWidth() > 20 || pass == 0) g.draw(e); // the team's ring; the dots' halo
                    if (e.getWidth() <= 20 && pass == 1) g.fill(e);
                } else {
                    g.draw((Path2D) s);
                }
            }
        }
    }

    private static Ellipse2D dot(double cx, double y1) {
        return new Ellipse2D.Double(cx - 7.5, y1 - 27.5, 15, 15);
    }

    private static Path2D bars(double y1, double... xs) {
        StringBuilder d = new StringBuilder();
        for (double x : xs) d.append(String.format(Locale.ROOT, "M%s,%s l0,-25 ", x, y1 - 10));
        return path(d.toString());
    }

    private static Path2D crosses(double y1, double... xs) {
        StringBuilder d = new StringBuilder();
        for (double x : xs) d.append(String.format(Locale.ROOT, "M%s,%s l25,-25 m0,25 l-25,-25 ", x, y1 - 10));
        return path(d.toString());
    }

    /** An SVG path (M/L/H/V/C/Z, absolute and relative, implicit repeats) as a Path2D, cached. */
    static Path2D path(String d) {
        return PATHS.computeIfAbsent(d, UnitSymbol::parse);
    }

    private static Path2D parse(String d) {
        List<String> t = new ArrayList<>();
        Matcher m = TOKEN.matcher(d);
        while (m.find()) t.add(m.group());
        Path2D.Double p = new Path2D.Double();
        double x = 0, y = 0, sx = 0, sy = 0;
        char cmd = 0;
        int i = 0;
        while (i < t.size()) {
            if (Character.isLetter(t.get(i).charAt(0))) {
                cmd = t.get(i++).charAt(0);
                if (cmd == 'Z' || cmd == 'z') {
                    p.closePath();
                    x = sx;
                    y = sy;
                    continue;
                }
            }
            if (cmd == 0) throw new IllegalArgumentException("path without a command: " + d);
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    double nx = Double.parseDouble(t.get(i++)), ny = Double.parseDouble(t.get(i++));
                    if (rel) {
                        nx += x;
                        ny += y;
                    }
                    p.moveTo(nx, ny);
                    x = sx = nx;
                    y = sy = ny;
                    cmd = rel ? 'l' : 'L'; // the pairs after a move are lines
                }
                case 'L' -> {
                    double nx = Double.parseDouble(t.get(i++)), ny = Double.parseDouble(t.get(i++));
                    if (rel) {
                        nx += x;
                        ny += y;
                    }
                    p.lineTo(nx, ny);
                    x = nx;
                    y = ny;
                }
                case 'H' -> {
                    double nx = Double.parseDouble(t.get(i++));
                    if (rel) nx += x;
                    p.lineTo(nx, y);
                    x = nx;
                }
                case 'V' -> {
                    double ny = Double.parseDouble(t.get(i++));
                    if (rel) ny += y;
                    p.lineTo(x, ny);
                    y = ny;
                }
                case 'C' -> {
                    double[] c = new double[6];
                    for (int k = 0; k < 6; k++) c[k] = Double.parseDouble(t.get(i++));
                    if (rel) {
                        for (int k = 0; k < 6; k += 2) {
                            c[k] += x;
                            c[k + 1] += y;
                        }
                    }
                    p.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                    x = c[4];
                    y = c[5];
                }
                default -> throw new IllegalArgumentException("path command " + cmd + " in " + d);
            }
        }
        return p;
    }
}
