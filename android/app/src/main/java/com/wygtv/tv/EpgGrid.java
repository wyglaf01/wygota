package com.wygtv.tv;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Plain-Java timeline math for the TV guide grid (kept free of Android classes so it can be unit tested). */
final class EpgGrid {
    static final long MINUTE = 60_000L;
    static final long SLOT = 30 * MINUTE;
    static final long WINDOW = 4 * SLOT;

    static final class Program {
        final long start, end;
        final String title, description;
        Program(long start, long end, String title, String description) {
            this.start = start; this.end = end;
            this.title = title == null ? "" : title.trim();
            this.description = description == null ? "" : description.trim();
        }
    }

    /** One block on a channel row, clipped to the visible window. A null program is a gap with no guide data. */
    static final class Cell {
        final long start, end;
        final Program program;
        Cell(long start, long end, Program program) { this.start = start; this.end = end; this.program = program; }
        boolean contains(long t) { return t >= start && t < end; }
    }

    private EpgGrid() {}

    /** Xtream sends epoch seconds in *_timestamp; older panels only send "yyyy-MM-dd HH:mm:ss" in server time. */
    static long parseTime(String timestamp, String text, TimeZone zone) {
        if (timestamp != null && timestamp.trim().matches("\\d{9,11}")) return Long.parseLong(timestamp.trim()) * 1000L;
        if (text == null || text.trim().isEmpty()) return -1;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(zone);
        f.setLenient(false);
        try { return f.parse(text.trim()).getTime(); } catch (ParseException e) { return -1; }
    }

    /** Sorted, without broken entries, and with overlaps trimmed so blocks never stack. */
    static List<Program> clean(List<Program> in) {
        List<Program> sorted = new ArrayList<>();
        for (Program p : in) if (p != null && p.start >= 0 && p.end > p.start) sorted.add(p);
        Collections.sort(sorted, (a, b) -> Long.compare(a.start, b.start));
        List<Program> out = new ArrayList<>();
        for (Program p : sorted) {
            if (!out.isEmpty()) {
                Program last = out.get(out.size() - 1);
                if (p.start < last.end) {
                    if (p.end <= last.end) continue;
                    p = new Program(last.end, p.end, p.title, p.description);
                }
            }
            out.add(p);
        }
        return out;
    }

    static long windowStart(long now) { return now - Math.floorMod(now, SLOT); }

    /** Blocks covering [winStart, winEnd) exactly, gaps filled with empty cells. */
    static List<Cell> cells(List<Program> programs, long winStart, long winEnd) {
        List<Cell> out = new ArrayList<>();
        long t = winStart;
        for (Program p : programs) {
            if (p.end <= t || p.start >= winEnd) continue;
            long s = Math.max(p.start, t), e = Math.min(p.end, winEnd);
            if (s - t >= MINUTE) out.add(new Cell(t, s, null));
            else if (s > t && !out.isEmpty()) { Cell prev = out.remove(out.size() - 1); out.add(new Cell(prev.start, s, prev.program)); }
            else s = t;
            out.add(new Cell(s, e, p));
            t = e;
            if (t >= winEnd) break;
        }
        if (t < winEnd) {
            if (winEnd - t < MINUTE && !out.isEmpty()) { Cell prev = out.remove(out.size() - 1); out.add(new Cell(prev.start, winEnd, prev.program)); }
            else out.add(new Cell(t, winEnd, null));
        }
        return out;
    }

    static int indexAt(List<Cell> cells, long t) {
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).contains(t)) return i;
        return cells.isEmpty() ? -1 : (t < cells.get(0).start ? 0 : cells.size() - 1);
    }

    static Program at(List<Program> programs, long t) {
        for (Program p : programs) if (t >= p.start && t < p.end) return p;
        return null;
    }

    static Program next(List<Program> programs, Program after) {
        for (Program p : programs) if (p.start >= after.end) return p;
        return null;
    }

    static int progressPercent(Program p, long now) {
        if (now <= p.start) return 0;
        if (now >= p.end) return 100;
        return (int) ((now - p.start) * 100 / (p.end - p.start));
    }

    static long minutesLeft(Program p, long now) {
        return Math.max(0, (p.end - Math.max(now, p.start) + MINUTE - 1) / MINUTE);
    }
}
