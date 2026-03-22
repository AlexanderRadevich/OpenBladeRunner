package com.cutter.plotterctl;

import java.util.ArrayList;
import java.util.List;

public class HpglParser {

    public static class PathPoint {
        public final boolean penDown;
        public final int x;
        public final int y;
        public PathPoint(boolean penDown, int x, int y) {
            this.penDown = penDown;
            this.x = x;
            this.y = y;
        }
    }

    public static class ParseResult {
        public final List<PathPoint> points;
        public final int minX, minY, maxX, maxY;
        public final int existingSpeed;
        public final int existingForce;

        public ParseResult(List<PathPoint> points, int minX, int minY, int maxX, int maxY,
                           int speed, int force) {
            this.points = points;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.existingSpeed = speed;
            this.existingForce = force;
        }
    }

    public static ParseResult parse(String hpgl) {
        List<PathPoint> points = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int speed = 0, force = 0;

        String[] cmds = hpgl.split(";");
        for (String cmd : cmds) {
            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;

            if (cmd.startsWith("PU") || cmd.startsWith("PD")) {
                boolean pd = cmd.startsWith("PD");
                String coords = cmd.substring(2);
                // Could have multiple coordinate pairs: PD100,200,300,400
                String[] parts = coords.split(",");
                for (int i = 0; i + 1 < parts.length; i += 2) {
                    try {
                        int x = Integer.parseInt(parts[i].trim());
                        int y = Integer.parseInt(parts[i + 1].trim());
                        points.add(new PathPoint(pd, x, y));
                        // Only use PD (cut) points for bounding box
                        // so PU return moves (e.g. PU0,0) don't inflate the design size
                        if (pd) {
                            if (x < minX) minX = x;
                            if (y < minY) minY = y;
                            if (x > maxX) maxX = x;
                            if (y > maxY) maxY = y;
                        }
                    } catch (NumberFormatException e) {
                        // skip bad coords
                    }
                }
            } else if (cmd.startsWith("VS")) {
                try { speed = Integer.parseInt(cmd.substring(2).trim()); } catch (Exception e) {}
            } else if (cmd.startsWith("FS")) {
                try { force = Integer.parseInt(cmd.substring(2).trim()); } catch (Exception e) {}
            }
        }

        if (points.isEmpty()) {
            minX = minY = maxX = maxY = 0;
        }

        return new ParseResult(points, minX, minY, maxX, maxY, speed, force);
    }

    /**
     * Rewrite HPGL to inject speed and force after IN;PA; and strip existing VS/FS.
     */
    public static String rewrite(String hpgl, int speed, int force) {
        StringBuilder sb = new StringBuilder();
        boolean injected = false;
        String[] cmds = hpgl.split(";");
        for (String cmd : cmds) {
            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;
            // Skip existing VS/FS
            if (cmd.startsWith("VS") || cmd.startsWith("FS")) continue;
            sb.append(cmd).append(";");
            // Inject after PA or IN
            if (!injected && (cmd.equals("PA") || cmd.equals("IN"))) {
                // Check if next is PA
                if (cmd.equals("PA")) {
                    sb.append("VS").append(speed).append(";");
                    sb.append("FS").append(force).append(";");
                    injected = true;
                }
            }
        }
        if (!injected) {
            // Prepend
            return "IN;PA;VS" + speed + ";FS" + force + ";" + sb.toString();
        }
        return sb.toString();
    }

    /**
     * Swap X and Y in all HPGL coordinates.
     * Needed when plotter axes are: HPGL_X=roller, HPGL_Y=head
     * but the file was authored with: X=head, Y=roller.
     */
    public static String swapAxes(String hpgl) {
        StringBuilder sb = new StringBuilder();
        String[] cmds = hpgl.split(";");
        for (String cmd : cmds) {
            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;
            if (cmd.startsWith("PU") || cmd.startsWith("PD")) {
                String prefix = cmd.substring(0, 2);
                String coords = cmd.substring(2);
                String[] parts = coords.split(",");
                StringBuilder newCmd = new StringBuilder(prefix);
                for (int i = 0; i + 1 < parts.length; i += 2) {
                    if (i > 0) newCmd.append(",");
                    // Swap: old X becomes new Y, old Y becomes new X
                    newCmd.append(parts[i + 1].trim()).append(",").append(parts[i].trim());
                }
                sb.append(newCmd).append(";");
            } else {
                sb.append(cmd).append(";");
            }
        }
        return sb.toString();
    }

    /**
     * Apply X/Y offset to all coordinates in HPGL data.
     * Offset is in HPGL units (40 units/mm).
     */
    public static String applyOffset(String hpgl, int dx, int dy) {
        StringBuilder sb = new StringBuilder();
        String[] cmds = hpgl.split(";");
        for (String cmd : cmds) {
            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;
            if (cmd.startsWith("PU") || cmd.startsWith("PD")) {
                String prefix = cmd.substring(0, 2);
                String coords = cmd.substring(2);
                String[] parts = coords.split(",");
                StringBuilder newCmd = new StringBuilder(prefix);
                for (int i = 0; i + 1 < parts.length; i += 2) {
                    try {
                        int x = Integer.parseInt(parts[i].trim()) + dx;
                        int y = Integer.parseInt(parts[i + 1].trim()) + dy;
                        if (i > 0) newCmd.append(",");
                        newCmd.append(x).append(",").append(y);
                    } catch (NumberFormatException e) {
                        if (i > 0) newCmd.append(",");
                        newCmd.append(parts[i]).append(",").append(parts[i + 1]);
                    }
                }
                sb.append(newCmd).append(";");
            } else {
                sb.append(cmd).append(";");
            }
        }
        return sb.toString();
    }

    public static String previewJson(ParseResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"minX\":").append(r.minX);
        sb.append(",\"minY\":").append(r.minY);
        sb.append(",\"maxX\":").append(r.maxX);
        sb.append(",\"maxY\":").append(r.maxY);
        sb.append(",\"speed\":").append(r.existingSpeed);
        sb.append(",\"force\":").append(r.existingForce);
        sb.append(",\"points\":[");
        for (int i = 0; i < r.points.size(); i++) {
            PathPoint p = r.points.get(i);
            if (i > 0) sb.append(",");
            sb.append("[").append(p.penDown ? 1 : 0).append(",").append(p.x).append(",").append(p.y).append("]");
        }
        sb.append("]}");
        return sb.toString();
    }
}
