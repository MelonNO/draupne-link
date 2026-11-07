package de.droiddrone.pc.setup;

/**
 * Drone setup's CONFIRM belongs to one flight controller (1.0.49): the board it was pressed for, by its MCU's unique
 * id (MSP_UID). A drone plugged in afterwards - the same page, a minute later - is never written to on that press: it
 * needs its own CONFIRM. The "everything is set" state (green rows, saved as drone.confirmed) is that board's too.
 */
public final class SetupConfirm {
    /** Saved as drone.confirmed while nothing is confirmed; otherwise the board's id is saved. */
    public static final String NONE = "false";
    /** The board CONFIRM was pressed for this session: the rounds write to it, and only to it. Null = not pressed. */
    private String armedFor;
    /** The board everything on the page was written to and found set; null = not confirmed. */
    private String confirmedFor;

    /** From the saved value: a board id, or nothing ("false", "", and 1.0.48's "true", which named no board). */
    public void restore(String saved) {
        String s = saved == null ? "" : saved.trim();
        confirmedFor = s.isEmpty() || s.equalsIgnoreCase(NONE) || s.equalsIgnoreCase("true") ? null : s;
        armedFor = null;
    }

    /** CONFIRM pressed while this board was on the page (null: no board read - nothing may be written). */
    public void press(String boardId) {
        armedFor = boardId;
    }

    /** A choice changed, or a write failed: CONFIRM is needed again. */
    public void clear() {
        armedFor = null;
        confirmedFor = null;
    }

    /** CONFIRM was pressed and still holds: the rounds write. */
    public boolean armed() {
        return armedFor != null;
    }

    /** The board CONFIRM was pressed for, or null. */
    public String armedFor() {
        return armedFor;
    }

    /** A round may write to this board: CONFIRM was pressed for this very board. */
    public boolean mayWrite(String boardId) {
        return armedFor != null && armedFor.equals(boardId);
    }

    /**
     * A round read this board: a CONFIRM, or a confirmed state, that belongs to another board no longer holds.
     * @return true if that changed anything (the saved value is then {@link #saved()})
     */
    public boolean seen(String boardId) {
        boolean changed = false;
        if (armedFor != null && !armedFor.equals(boardId)) {
            armedFor = null;
            changed = true;
        }
        if (confirmedFor != null && !confirmedFor.equals(boardId)) {
            confirmedFor = null;
            changed = true;
        }
        return changed;
    }

    /** Everything on the page is in this board. */
    public void done(String boardId) {
        confirmedFor = boardId;
    }

    public boolean confirmed() {
        return confirmedFor != null;
    }

    /** What drone.confirmed holds. */
    public String saved() {
        return confirmedFor == null ? NONE : confirmedFor;
    }

    /**
     * The board's id: its MCU's unique id (MSP_UID, 12 bytes, every Betaflight) as hex; else Betaflight's board info
     * (MSP_BOARD_INFO); null when neither is known - then nothing is written.
     */
    public static String boardId(byte[] uid, byte[] boardInfo) {
        if (uid != null && uid.length >= 12 && !allZero(uid)) return "uid:" + hex(uid, 12);
        if (boardInfo != null && boardInfo.length > 0) return "info:" + hex(boardInfo, boardInfo.length);
        return null;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }
}
