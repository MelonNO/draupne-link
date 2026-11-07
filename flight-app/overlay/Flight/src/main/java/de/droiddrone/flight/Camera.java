/*
 *  This file is part of DroidDrone.
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

/**
 * DRAUPNE Link (pc.26): isOpening, so a config change can wait for a camera that is still opening. pc.35: the small
 * analysis picture for the motion detection ({@link MotionWatch}), on only while it asks.
 */
public interface Camera {
    /** openCamera was called and the device hasn't answered yet; false for cameras that open at once. */
    default boolean isOpening() {
        return false;
    }

    /** pc.35: pictures to MotionWatch on or off; nothing happens when it is already so. */
    default void setAnalysis(boolean on) {
    }

    /** pc.35: this camera can give MotionWatch pictures (the phone took the extra stream, a USB camera is open). */
    default boolean analysisAvailable() {
        return false;
    }

    /**
     * pc.36, overwatch sleep: the next session is the analysis picture alone (no encoder, no recorder) - the camera
     * watches for the motion detection and nothing else. False: the normal session. Cameras that can't do it ignore it
     * (a USB camera runs its usual stream; its frames are dropped while nothing is sent).
     */
    default void setWatchOnly(boolean on) {
    }

    /** pc.36: whether a watch-only session can be had (the internal camera with its analysis picture). */
    default boolean canWatchOnly() {
        return false;
    }

    /** pc.36: an overwatch event's recorder surface in the next session beside the encoder's (null: none). */
    default void setExtraTarget(android.view.Surface surface) {
    }

    boolean initialize(StreamEncoder streamEncoder, Mp4Recorder mp4Recorder);
    boolean openCamera();
    int getCurrentFps();
    int getTargetFps();
    boolean isOpened(String cameraId);
    void startCapture();
    void stopCapture();
    void startPreview();
    boolean isStarted();
    int getWidth();
    int getHeight();
    boolean isFrontFacing();
    void close();
}
