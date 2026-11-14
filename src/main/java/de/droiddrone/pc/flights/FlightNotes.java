package de.droiddrone.pc.flights;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What the pilot writes about a flight afterwards: bookmarks - a moment of the flight and a few words - and one free
 * note. Kept in a file of its own beside the track, track-DATE.notes.txt ({@link FlightLibrary.Flight#notesFile}), so
 * EXPORT and DELETE take it with the flight. The bookmarks first, one a line - ms from the flight's start, a tab, the
 * text - then an empty line and the note as written:
 * <pre>
 * 83500 (tab) Car at the gate
 * 241000 (tab) Video froze
 *
 * Wind from the west, stronger than forecast.
 * </pre>
 * Written whole to a file beside it, then moved into place; a flight with neither has no file. A file cut short or
 * edited by hand reads as far as it makes sense. Thread-safe.
 */
public final class FlightNotes {
    /** After the track's (or the recording's) name without its ending. */
    static final String SUFFIX = ".notes.txt";

    /** A moment of the flight (ms from its start) and what the pilot wrote about it, on one line. */
    public record Bookmark(long offsetMs, String text) {}

    private final Path file;
    private final List<Bookmark> bookmarks = new ArrayList<>();
    private String note = "";

    private FlightNotes(Path file) {
        this.file = file;
    }

    /** The flight's notes; empty when it has none yet. Reads a file: not on the Swing thread. */
    public static FlightNotes load(FlightLibrary.Flight f) {
        return load(f.notesFile());
    }

    /** @param file the notes file, there or not; null keeps them in memory only */
    public static FlightNotes load(Path file) {
        FlightNotes n = new FlightNotes(file);
        if (file == null || !Files.isRegularFile(file)) return n;
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8); // bytes that aren't UTF-8 read as marks, not as an error
        } catch (IOException e) {
            return n;
        }
        if (text.startsWith("﻿")) text = text.substring(1); // Notepad's mark
        String[] lines = text.split("\r\n|\r|\n", -1);
        int i = 0;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) { // the empty line: the note follows
                i++;
                break;
            }
            Bookmark b = bookmark(line);
            if (b != null) n.add(b);
            else if (!line.matches("\\d+")) break; // no bookmark and no half of one (a file cut short): the note, written by hand
        }
        StringBuilder sb = new StringBuilder();
        for (; i < lines.length; i++) sb.append(lines[i]).append('\n');
        n.note = tidy(sb.toString());
        return n;
    }

    private static Bookmark bookmark(String line) {
        int tab = line.indexOf('\t');
        if (tab <= 0 || tab > 15) return null;
        try {
            return new Bookmark(Math.max(0, Long.parseLong(line.substring(0, tab))), oneLine(line.substring(tab + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    /** A note with \n between its lines, nothing empty before it or after it. */
    private static String tidy(String note) {
        return note == null ? "" : note.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("^(\\s*\\n)+", "").stripTrailing();
    }

    private void add(Bookmark b) {
        int at = bookmarks.size();
        while (at > 0 && bookmarks.get(at - 1).offsetMs() > b.offsetMs()) at--; // in time order; the same moment: as added
        bookmarks.add(at, b);
    }

    /** Where they are kept, or null. */
    public Path file() {
        return file;
    }

    /** The bookmarks, earliest first. */
    public synchronized List<Bookmark> bookmarks() {
        return new ArrayList<>(bookmarks);
    }

    public synchronized String note() {
        return note;
    }

    public synchronized boolean isEmpty() {
        return bookmarks.isEmpty() && note.isEmpty();
    }

    /** A bookmark at this moment (ms from the flight's start; before it counts as its start). Not saved yet. */
    public synchronized Bookmark addBookmark(long offsetMs, String text) {
        Bookmark b = new Bookmark(Math.max(0, offsetMs), oneLine(text));
        add(b);
        return b;
    }

    /** @return false when it wasn't one of them. Not saved yet. */
    public synchronized boolean removeBookmark(Bookmark b) {
        return bookmarks.remove(b);
    }

    /** The free note (null or blank: none). Not saved yet. */
    public synchronized void setNote(String text) {
        note = tidy(text);
    }

    /**
     * Writes them: whole to a file beside it, then moved into place - the file is the old one or the new one, never
     * half. With no bookmark and no note left the file goes. Writes a file: not on the Swing thread.
     */
    public synchronized void save() throws IOException {
        if (file == null) return;
        if (isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Bookmark b : bookmarks) sb.append(b.offsetMs()).append('\t').append(b.text()).append('\n');
        if (!note.isEmpty()) sb.append('\n').append(note).append('\n');
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
