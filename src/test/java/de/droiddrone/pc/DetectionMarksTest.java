package de.droiddrone.pc;

import de.droiddrone.pc.map.CameraFootprint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The map's marks for motion detections: one per box id, the newest direction, gone after KEEP_MS. */
class DetectionMarksTest {
    private static CameraFootprint.Ray ray(double bearing) {
        return new CameraFootprint.Ray(bearing, new double[]{59.9, 10.7, 120}, 85, false);
    }

    @Test
    void oneMarkPerIdTheNewestDirectionWinsAndOldOnesGo() {
        DetectionMarks marks = new DetectionMarks();
        marks.put(1, false, 59.9, 10.7, ray(10), 1000);
        marks.put(2, true, 59.9, 10.7, ray(200), 2000);
        marks.put(1, false, 59.9, 10.7, ray(30), 3000); // the same box again: it follows, still one mark
        List<DetectionMarks.Mark> now = marks.marks(4000);
        assertEquals(2, now.size());
        assertEquals(30, now.get(0).bearingDeg(), 1e-9, "id 1 turned to its newest direction");
        assertTrue(now.get(1).light());
        assertEquals(85, now.get(0).rangeM(), 1e-9);
        // id 2 seen at 2000: gone at 2000 + KEEP_MS; id 1 seen at 3000 stays a moment longer
        List<DetectionMarks.Mark> later = marks.marks(2000 + DetectionMarks.KEEP_MS);
        assertEquals(1, later.size());
        assertEquals(1, later.get(0).id());
        assertTrue(marks.marks(3000 + DetectionMarks.KEEP_MS).isEmpty());
    }

    @Test
    void aBoxWithoutARayLeavesNoMarkAndClearTakesThemAll() {
        DetectionMarks marks = new DetectionMarks();
        marks.put(1, false, 59.9, 10.7, null, 1000);
        assertTrue(marks.marks(1000).isEmpty(), "no position or attitude: no direction to mark");
        marks.put(1, false, 59.9, 10.7, ray(10), 1000);
        marks.clear();
        assertTrue(marks.marks(1000).isEmpty());
    }

    @Test
    void aMarkFadesToAQuarterThenGoes() {
        DetectionMarks.Mark m = new DetectionMarks.Mark(1, false, 59.9, 10.7, 0, null, Double.NaN, true, 1000);
        assertEquals(1f, m.alpha(1000), 1e-6);
        assertEquals(1f, m.alpha(500), 1e-6, "a clock reading before it (another thread's) is fresh, not over 1");
        assertEquals(0.625f, m.alpha(1000 + DetectionMarks.KEEP_MS / 2), 1e-6);
        assertEquals(0f, m.alpha(1000 + DetectionMarks.KEEP_MS), 1e-6);
    }
}
