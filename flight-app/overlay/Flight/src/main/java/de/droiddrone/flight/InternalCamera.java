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
 *
 *  Modified for DRAUPNE Link: the frame-rate range asked for is kept (upstream took the fastest when the size
 *  wasn't exact) and matched to one the camera lists (supportedRange); sizes keep the picture's shape; a session can
 *  be stopped without closing the camera (stopSession).
 *  pc.31: one camera thread, kept across opens (each open started one and none was quit); a device's callbacks for
 *  an earlier open (or one after close) only close that device, never the current camera's state.
 *  pc.35: a second small YUV stream for the motion detection (MotionWatch) beside the encoder's surface - in the
 *  session, but in the repeating request only while MotionWatch analyses (disarmed); a phone that refuses a session
 *  with it gets one without, and isn't asked again.
 */

package de.droiddrone.flight;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static de.droiddrone.common.Logcat.log;

public class InternalCamera implements Camera{
    private Size targetResolution;
    private Range<Integer> targetFrameRange;
    private CameraDevice camera;
    private CameraManager cameraManager;
    public Size cameraResolution;
    public boolean highFps = false;
    public boolean wideAngle = false;
    public boolean logical = false;
    public boolean frontFacing = false;
    public Range<Integer> frameRate;
    private int cameraFrameCounter;
    private int lastFps;
    private long cameraFrameTimestamp;
    private Surface streamEncoderSurface, recorderSurface;
    private final Context context;
    private final Config config;
    private StreamEncoder streamEncoder;
    private Mp4Recorder mp4Recorder;
    private boolean isOpened;
    private HandlerThread handlerThread;
    private CaptureRequest.Builder mCaptureRequest;
    private CameraCaptureSession mCameraCaptureSession;
    private volatile boolean isStarted; // pc.50: read by the motion tick on other threads
    /** openCamera was called and the device hasn't answered yet (pc.26: a config change waits for it). */
    private volatile boolean isOpening;
    /** pc.35: the motion detection's picture: its size (the video's shape, about 320 wide; null: none), reader, thread. */
    private Size analysisSize;
    private ImageReader analysisReader;
    /**
     * pc.50, overwatch's picture alone: its own small size (320x240 cut to 16:9 where the camera has no small 16:9) and
     * the lowest frame-rate range (15) - it ran 1280x720 at 30 fps for 8 pictures a second of 320x180. Null: as video.
     */
    private Size watchSize;
    private Range<Integer> watchRange;
    /** The other size's reader, kept: a reader is never closed while a session switching away may still fill it. */
    private ImageReader spareReader;
    private HandlerThread analysisThread;
    /** The analysis surface of the session being made or running; null when it has none. */
    private volatile Surface sessionAnalysis;
    /** MotionWatch wants pictures: the analysis surface is in the repeating request. */
    private volatile boolean analysisOn;
    /** A camera whose session failed with the analysis stream: never asked for it again while the app runs. */
    private static volatile String analysisRefusedFor;
    /** The encoder's (and recorder's) surfaces of the last session, for its second try without the analysis stream. */
    private volatile List<Surface> sessionTargets;
    /** The repeating request's targets change on three threads (recording, analysis, the session): one at a time. */
    private final Object requestLock = new Object();
    /** pc.36: overwatch's session - the analysis picture alone. */
    private volatile boolean watchOnly;
    /** pc.36: an overwatch event's recorder, in the session and the repeating request beside the encoder. */
    private volatile Surface extraTarget;

    @Override
    public void setWatchOnly(boolean on) {
        watchOnly = on;
    }

    /**
     * pc.49: the picture alone is asked for even when the camera once refused it beside a video (analysisRefusedFor):
     * that says nothing of a session with it alone, and overwatch then watched with no picture until the app restarted.
     */
    @Override
    public boolean canWatchOnly() {
        return analysisSize != null || watchSize != null;
    }

    /** pc.49: what took the pictures away (OverwatchProtocol BLIND_); 0 since the last open. */
    private volatile int trouble;

    @Override
    public int blindReason() {
        if (trouble != 0) return trouble;
        if (!isOpened) return de.droiddrone.common.OverwatchProtocol.BLIND_CLOSED;
        return de.droiddrone.common.OverwatchProtocol.BLIND_NO_FRAMES;
    }

    @Override
    public void setExtraTarget(Surface surface) {
        extraTarget = surface;
    }

    /** pc.36 (the PHONE_POWER caps): this camera refused the analysis picture, so it can't overwatch. */
    static boolean analysisRefused(String cameraId) {
        return cameraId != null && cameraId.equals(analysisRefusedFor);
    }

    public InternalCamera(Context context, Config config) {
        this.context = context;
        this.config = config;
    }

    @Override
    public boolean initialize(StreamEncoder streamEncoder, Mp4Recorder mp4Recorder){
        this.streamEncoder = streamEncoder;
        this.mp4Recorder = mp4Recorder;
        isStarted = false;
        targetResolution = new Size(config.getCameraResolutionWidth(), config.getCameraResolutionHeight());
        targetFrameRange = new Range<>(config.getCameraFpsMin(), config.getCameraFpsMax());
        frameRate = targetFrameRange;

        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String[] cameraIdList;
        try{
            cameraIdList = cameraManager.getCameraIdList();
        }catch (CameraAccessException e){
            log("getCameraIdList CameraAccessException: "+ e);
            return false;
        }
        if (cameraIdList.length == 0){
            log("No camera found.");
            return false;
        }
        for (String cameraId : cameraIdList) {
            log("Camera device found. ID: " + cameraId);
        }
        return getCameraCharacteristics(config.getCameraId());
    }

    /**
     * DRAUPNE Link: stops the capture session but leaves the camera open, for a change of picture size: the video then
     * restarts on the open camera (a new encoder and session, a few hundred ms) instead of closing and reopening it
     * (1.5-2.6 s without video, measured).
     */
    public void stopSession(){
        CameraCaptureSession session = mCameraCaptureSession;
        mCameraCaptureSession = null;
        isStarted = false;
        if (session == null) return;
        try {
            session.stopRepeating();
        } catch (Exception ignored) {
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    /** pc.38: the session stopSessionAndWait waits on, and its latch (onClosed counts it down). */
    private volatile CameraCaptureSession closingSession;
    private volatile java.util.concurrent.CountDownLatch closingLatch;

    /**
     * pc.38 (the review's H10): stops the capture session and waits until the camera has let go of its surfaces
     * (onClosed: its last capture done), at most timeoutMs. An overwatch recorder's surface may be released only
     * then - released while the session still used it, some phones raise a camera error that stops the watch.
     * @return true when the session is gone (or there was none)
     */
    public boolean stopSessionAndWait(long timeoutMs){
        CameraCaptureSession session = mCameraCaptureSession;
        if (session == null) {
            stopSession();
            return true;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        closingLatch = latch;
        closingSession = session;
        stopSession();
        try {
            return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            closingLatch = null;
            closingSession = null;
        }
    }

    @Override
    public boolean isOpened(String cameraId){
        return isOpened && camera != null && camera.getId().equals(cameraId);
    }

    @Override
    public boolean openCamera(){
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            return false;
        }
        try{
            if (camera != null) camera.close();
        }catch (Exception e){
            //
        }
        try{
            // pc.31: one camera thread, kept - every open started a new one and none was ever quit
            if (handlerThread == null || !handlerThread.isAlive()) {
                handlerThread = new HandlerThread("CameraThread");
                handlerThread.start();
            }
            final Handler handler = new Handler(handlerThread.getLooper());
            isOpening = true;
            cameraManager.openCamera(config.getCameraId(), stateCallback(++openGeneration), handler);
        }catch (Exception e){
            isOpening = false;
            log("openCamera error: "+ e);
            return false;
        }
        return true;
    }

    @Override
    public boolean isOpening(){
        return isOpening;
    }

    /**
     * pc.31: which openCamera (or close) is the newest: a device's callbacks for an earlier one only close that
     * device - before, a late onDisconnected or onError of an evicted device cleared the state of the camera that
     * had just been opened, and a late onOpened after close() started a preview on a camera nobody wanted.
     */
    private volatile int openGeneration;

    private CameraDevice.StateCallback stateCallback(final int generation) {
        return new CameraDevice.StateCallback(){
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice){
            if (generation != openGeneration) {
                log("Camera device opened for an earlier start: closed");
                closeQuietly(cameraDevice);
                return;
            }
            camera = cameraDevice;
            isOpened = true;
            isOpening = false;
            trouble = 0;
            log("Camera device opened.");
            startPreview();
        }

        // DRAUPNE Link: a camera taken away (another app, a system error) is marked closed, so the next StartVideo
        // opens it again. Upstream only logged, isOpened stayed true, and startPreview went to a dead device for ever
        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice){
            log("Camera device disconnected.");
            lost(cameraDevice);
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i){
            log("Camera device error: " + i);
            lost(cameraDevice);
        }

        private void lost(CameraDevice cameraDevice) {
            if (generation != openGeneration) { // an earlier open's device: the current state isn't its
                closeQuietly(cameraDevice);
                return;
            }
            isOpened = false;
            isOpening = false;
            isStarted = false;
            mCameraCaptureSession = null;
            trouble = de.droiddrone.common.OverwatchProtocol.BLIND_LOST;
            if (camera == cameraDevice) camera = null;
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
        }
        };
    }

    private static void closeQuietly(CameraDevice cameraDevice) {
        try {
            cameraDevice.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void startPreview() {
        if (watchOnly) { // pc.36: overwatch - the motion detection's picture and nothing else
            Surface analysis = analysisSurface(true);
            if (analysis == null) {
                log("Overwatch: this camera gives no analysis picture - nothing to watch with");
                return;
            }
            streamEncoderSurface = null;
            recorderSurface = null;
            analysisOn = true; // the session's only output: always in the request
            sessionTargets = new ArrayList<>();
            createSession(sessionTargets, analysis);
            return;
        }
        if (streamEncoder == null) return;
        Surface streamEncoderSurface = streamEncoder.initializeVideo();
        if (streamEncoderSurface == null) return;
        this.streamEncoderSurface = streamEncoderSurface;
        ArrayList<Surface> targets = new ArrayList<>();
        targets.add(streamEncoderSurface);
        Surface extra = extraTarget;
        if (extra != null) { // pc.36: an overwatch event records itself (upstream's recorder stays out of it)
            targets.add(extra);
            this.recorderSurface = null;
        } else {
            Surface recorderSurface = mp4Recorder.initialize();
            if (recorderSurface != null){
                this.recorderSurface = recorderSurface;
                targets.add(recorderSurface);
            }
        }
        sessionTargets = targets;
        createSession(targets, analysisSurface(false));
    }

    /**
     * pc.35: the motion detection's reader surface for a new session, or null: no small picture of the video's shape
     * on this camera, or the camera refused it before. A reader of the same size is kept from session to session.
     */
    private Surface analysisSurface(boolean alone) {
        Size size = alone && watchSize != null ? watchSize : analysisSize;
        if (size == null || !alone && config.getCameraId().equals(analysisRefusedFor)) return null; // pc.49: alone, always tried
        try {
            if (analysisReader != null && !fits(analysisReader, size) && spareReader != null && fits(spareReader, size)) {
                ImageReader r = analysisReader; // pc.50: overwatch's and the video's sizes by turns - the other one kept
                analysisReader = spareReader;
                spareReader = r;
            }
            if (analysisReader == null || !fits(analysisReader, size)) {
                if (spareReader != null) spareReader.close(); // two sessions back: long gone
                spareReader = analysisReader;
                if (analysisThread == null || !analysisThread.isAlive()) {
                    analysisThread = new HandlerThread("motionFrames", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    analysisThread.start();
                }
                // 3 buffers: the newest is taken and closed at once (copied smaller), the camera never waits for one
                analysisReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
                analysisReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) MotionWatch.offer(image);
                    } catch (RuntimeException e) {
                        // a reader closed under us
                    } finally {
                        if (image != null) image.close();
                    }
                }, new Handler(analysisThread.getLooper()));
            }
            return analysisReader.getSurface();
        } catch (RuntimeException e) {
            log("Motion detection's picture not made: " + e);
            return null;
        }
    }

    private static boolean fits(ImageReader r, Size size) {
        return r.getWidth() == size.getWidth() && r.getHeight() == size.getHeight();
    }

    /** The capture session: the encoder's (and recorder's) surfaces shared, and the analysis surface on its own. */
    private void createSession(List<Surface> targets, Surface analysis) {
        sessionAnalysis = analysis;
        try {
            List<OutputConfiguration> outputConfigurations = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 28) {
                OutputConfiguration outputConfig = null;
                for (Surface target : targets) {
                    if (outputConfig == null){
                        outputConfig = new OutputConfiguration(target);
                    }else{
                        outputConfig.enableSurfaceSharing();
                        outputConfig.addSurface(target);
                    }
                }
                if (outputConfig != null) outputConfigurations.add(outputConfig); // pc.36: none in a watch-only session
                if (analysis != null) outputConfigurations.add(new OutputConfiguration(analysis));

                final Handler handler = new Handler(handlerThread.getLooper());
                Executor executor = new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        handler.post(command);
                    }
                };
                SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurations, executor, sessionStateCallback);
                camera.createCaptureSession(config);
            }else{
                for (Surface target : targets) {
                    OutputConfiguration outputConfig = new OutputConfiguration(target);
                    outputConfigurations.add(outputConfig);
                }
                if (analysis != null) outputConfigurations.add(new OutputConfiguration(analysis));

                final Handler handler = new Handler(handlerThread.getLooper());
                camera.createCaptureSessionByOutputConfigurations(outputConfigurations, sessionStateCallback, handler);
            }
        } catch (Exception e) {
            log("createCaptureSession error: " + e);
        }
    }

    @Override
    public boolean isStarted(){
        return isStarted;
    }

    @Override
    public void startCapture(){
        synchronized (requestLock) {
            if (recorderSurface != null){
                mCaptureRequest.addTarget(recorderSurface);
                try{
                    mCameraCaptureSession.setRepeatingRequest(mCaptureRequest.build(), captureCallback, new Handler(handlerThread.getLooper()));
                }catch (CameraAccessException e){
                    log("addRecorderSurface CameraAccessException: "+ e);
                }
            }
        }
    }

    @Override
    public void stopCapture(){
        synchronized (requestLock) {
            if (recorderSurface != null) mCaptureRequest.removeTarget(recorderSurface);
            try{
                mCameraCaptureSession.setRepeatingRequest(mCaptureRequest.build(), captureCallback, new Handler(handlerThread.getLooper()));
            }catch (Exception e){
                log("removeRecorderSurface CameraAccessException: "+ e);
            }
        }
    }

    /**
     * pc.35: MotionWatch's pictures on or off (it asks every 0.1 s; nothing happens when it is already so). The
     * analysis surface goes into the repeating request or out of it on the camera's thread, like the recorder's.
     */
    @Override
    public void setAnalysis(boolean on){
        if (on == analysisOn) return;
        analysisOn = on;
        HandlerThread thread = handlerThread;
        if (thread == null) return;
        try {
            new Handler(thread.getLooper()).post(this::applyAnalysis);
        } catch (RuntimeException e) {
            // the camera thread is ending: the next session starts with analysisOn as it is
        }
    }

    private void applyAnalysis(){
        synchronized (requestLock) {
            CaptureRequest.Builder request = mCaptureRequest;
            CameraCaptureSession session = mCameraCaptureSession;
            Surface analysis = sessionAnalysis;
            HandlerThread thread = handlerThread;
            if (request == null || session == null || analysis == null || thread == null) return;
            if (analysisOn) request.addTarget(analysis);
            else if (watchOnly) return; // pc.36: the watch-only session's only output stays (a request needs one)
            else request.removeTarget(analysis);
            try{
                session.setRepeatingRequest(request.build(), captureCallback, new Handler(thread.getLooper()));
            }catch (Exception e){
                log("Motion detection's picture " + (analysisOn ? "on" : "off") + ": " + e);
            }
        }
    }

    @Override
    public boolean analysisAvailable(){
        return sessionAnalysis != null && mCameraCaptureSession != null;
    }

    private final CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback(){
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession){
            log("CameraCaptureSession - onConfigured");
            CaptureRequest.Builder captureRequest;
            try{
                captureRequest = cameraCaptureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }catch (CameraAccessException e){
                log("createCaptureRequest CameraAccessException: "+ e);
                return;
            }
            synchronized (requestLock) {
                mCaptureRequest = captureRequest;
                mCameraCaptureSession = cameraCaptureSession;
                if (trouble == de.droiddrone.common.OverwatchProtocol.BLIND_SESSION) trouble = 0;
                configureCamera(captureRequest);
                if (streamEncoderSurface != null && !watchOnly) captureRequest.addTarget(streamEncoderSurface);
                Surface extra = extraTarget;
                if (extra != null && !watchOnly && sessionTargets != null && sessionTargets.contains(extra)) captureRequest.addTarget(extra); // pc.36
                Surface analysis = sessionAnalysis;
                // pc.35: MotionWatch analyses. pc.49: a watch-only session's picture always - MotionWatch turns the picture
                // off while the camera isn't started, and a watch-only camera starts only with it: when its 0.1 s tick
                // came between startPreview and here (Foxtrot 2026-11-11: 143 ms), the request had no target, the
                // session never started and overwatch saw nothing - the pilot's "it warns sometimes"
                if (analysis != null && (analysisOn || watchOnly)) captureRequest.addTarget(analysis);
                try{
                    cameraCaptureSession.setRepeatingRequest(captureRequest.build(), captureCallback, new Handler(handlerThread.getLooper()));
                    cameraFrameCounter = 0;
                    lastFps = 0;
                    cameraFrameTimestamp = System.currentTimeMillis();
                }catch (Exception e){
                    log("Camera - setRepeatingRequest error: "+ e);
                }
            }
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession cameraCaptureSession){
            java.util.concurrent.CountDownLatch latch = closingLatch;
            if (latch != null && cameraCaptureSession == closingSession) latch.countDown(); // pc.38: stopSessionAndWait
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession){
            log("CameraCaptureSession - onConfigureFailed");
            if (watchOnly) { // pc.49: overwatch's session (the picture alone) refused - not marked for good; its watchdog tries again
                trouble = de.droiddrone.common.OverwatchProtocol.BLIND_SESSION;
                log("Overwatch: the camera refused its session");
                return;
            }
            // pc.35: the phone won't take the motion detection's stream beside the video: the video without it, now
            // and from here on (a USB camera or another phone may still do)
            List<Surface> targets = sessionTargets;
            Surface extra = extraTarget;
            if (sessionAnalysis != null && targets != null && camera != null && extra != null && targets.contains(extra)) {
                // pc.47: an overwatch event's session (video, recording and the detection's picture) refused: this event
                // records without the picture, but it is not refused for good - the watching after it needs it, and its
                // own session (the picture alone) worked (was: never again, so a phone saw its first event only)
                log("Overwatch: the camera refused the detection's picture beside the recording: this event records without it");
                createSession(targets, null);
                return;
            }
            if (sessionAnalysis != null && targets != null && camera != null) {
                log("The camera refused the motion detection's picture: the video goes on without it");
                analysisRefusedFor = config.getCameraId();
                createSession(targets, null);
            }
        }
    };

    private boolean getCameraCharacteristics(String cameraId){
        int width = 0, height = 0;
        CameraCharacteristics characteristics;
        try{
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
        }catch (Exception e){
            log("getCameraCharacteristics CameraAccessException: "+ e);
            return false;
        }
        highFps = false;
        logical = false;
        wideAngle = false;
        frontFacing = false;
        try {
            int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);//0 - front, 1 - back, 2 - external
            if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) frontFacing = true;
        }catch (NullPointerException e){
            log("Get camera lens facing error: " + e);
        }
        int[] cameraCapabilities=characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (cameraCapabilities != null) {
            for (int cap : cameraCapabilities) {
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
                    highFps = true;
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                    logical = true;
            }
        }

        float[] fLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float minLength = 999;
        if (fLengths != null) {
            for (float length : fLengths) {
                if (length < minLength) minLength = length;
            }
        }
        if (minLength < 2) wideAngle = true;

        StreamConfigurationMap configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (configurationMap != null) {
            if (highFps && frameRate.getUpper() > 30) {
                Size[] sizes = configurationMap.getHighSpeedVideoSizes();
                int minDiff = -1;
                if (sizes != null) {
                    for (Size size : sizes) {
                        int diff = Math.abs(size.getWidth() - targetResolution.getWidth()) + Math.abs(size.getHeight() - targetResolution.getHeight());
                        if (diff < minDiff || minDiff == -1) {
                            minDiff = diff;
                            width = size.getWidth();
                            height = size.getHeight();
                        }
                    }
                    if (((targetResolution.getWidth() - width) + (targetResolution.getHeight() - height)) > 1000)
                        highFps = false;
                }
                Range<Integer>[] fpsRanges = configurationMap.getHighSpeedVideoFpsRanges();
                int maxFps = 0;
                if (fpsRanges != null) {
                    for (Range<Integer> fpsRange : fpsRanges) {
                        if (fpsRange.getUpper() > maxFps) maxFps = fpsRange.getUpper();
                    }
                    if (frameRate.getUpper() > maxFps && maxFps != 0)
                        frameRate = new Range<>(frameRate.getLower(), maxFps);
                }
            }
        }
        if (width != targetResolution.getWidth() || height != targetResolution.getHeight()){
            int[] supportedFormats = null;
            if (configurationMap != null) {
                supportedFormats = configurationMap.getOutputFormats();
            }
            Size[] sizes = null;
            if (supportedFormats != null) {
                for (int format : supportedFormats) {
                    Size[] s = configurationMap.getOutputSizes(format);
                    if (s != null && (sizes == null || s.length > sizes.length)) sizes = s;
                }
            }
            // DRAUPNE Link: the size for the one asked for (pickSize); upstream took the nearest of any shape
            Size picked = pickSize(sizes, targetResolution);
            if (picked != null) {
                width = picked.getWidth();
                height = picked.getHeight();
            }
            // DRAUPNE Link: upstream took the camera's fastest range here, whatever was asked for - and this branch runs
            // for every camera that isn't high-speed, so the camera ran at 60 when the PC asked for 30. The range asked
            // for stays; supportedRange below matches it to one the camera lists.
        }
        if (frameRate.getLower() <= 0 || frameRate.getLower() > 60 || frameRate.getUpper() <= 0 || frameRate.getUpper() > 240){
            frameRate = targetFrameRange;
        }
        Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        frameRate = supportedRange(fpsRanges, frameRate);
        log("Min FPS: "+frameRate.getLower()+", Max FPS: "+frameRate.getUpper());
        watchRange = null; // pc.50: overwatch's own, the lowest listed from 10 up
        if (fpsRanges != null) {
            int[][] all = new int[fpsRanges.length][];
            for (int i = 0; i < fpsRanges.length; i++) all[i] = new int[]{fpsRanges[i].getLower(), fpsRanges[i].getUpper()};
            int[] w = de.droiddrone.common.AnalysisSize.watchFps(all);
            if (w != null) watchRange = new Range<>(w[0], w[1]);
        }
        cameraResolution = new Size(width, height);
        log("cameraResolution - Width: " + cameraResolution.getWidth() + ", Height: " + cameraResolution.getHeight());
        // pc.35: the motion detection's small picture, the video's shape (so a box on it is a box on the video), and
        // the angle the picture spans across (the boxes' angles off the camera's axis)
        Size[] yuv = configurationMap == null ? null : configurationMap.getOutputSizes(ImageFormat.YUV_420_888);
        analysisSize = yuv == null ? null : pickAnalysisSize(yuv, cameraResolution);
        watchSize = yuv == null ? null : pickWatchSize(yuv, cameraResolution);
        MotionWatch.setVideoShape(width, height);
        if (watchSize != null) log("Overwatch picture: " + watchSize.getWidth() + "x" + watchSize.getHeight()
                + (watchRange == null ? "" : " at " + watchRange.getLower() + "-" + watchRange.getUpper() + " fps"));
        SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float focal = fLengths != null && fLengths.length > 0 ? fLengths[0] : 0;
        MotionWatch.setFieldOfView(fieldOfView(sensor == null ? 0 : sensor.getWidth(), sensor == null ? 0 : sensor.getHeight(), focal,
                (float) width / Math.max(1, height)));
        return true;
    }

    /**
     * pc.35: the smallest YUV size of the video's shape at least 240 wide (Common AnalysisSize; pc.43: no upper limit -
     * a phone with 16:9 only at 1280x720 and up had none, and no motion detection); null when the camera lists none of
     * that shape - a box on a picture of another shape would be off on the video.
     */
    static Size pickAnalysisSize(Size[] sizes, Size video) {
        if (sizes == null || video == null) return null;
        int[][] all = new int[sizes.length][];
        for (int i = 0; i < sizes.length; i++) all[i] = new int[]{sizes[i].getWidth(), sizes[i].getHeight()};
        int[] p = de.droiddrone.common.AnalysisSize.pick(all, video.getWidth(), video.getHeight());
        if (p == null) {
            log("Motion detection: the camera lists no YUV size of the video's shape (" + video.getWidth() + "x" + video.getHeight() + ")");
            return null;
        }
        return new Size(p[0], p[1]);
    }

    /** pc.50: overwatch's picture alone (Common AnalysisSize.pickWatch); null: none. */
    static Size pickWatchSize(Size[] sizes, Size video) {
        if (sizes == null || video == null) return null;
        int[][] all = new int[sizes.length][];
        for (int i = 0; i < sizes.length; i++) all[i] = new int[]{sizes[i].getWidth(), sizes[i].getHeight()};
        int[] p = de.droiddrone.common.AnalysisSize.pickWatch(all, video.getWidth(), video.getHeight());
        return p == null ? null : new Size(p[0], p[1]);
    }

    /**
     * pc.35: the angle across a picture of this shape, degrees, from the sensor's size (mm) and the lens's focal length
     * (mm); 0 when unknown. A picture wider in shape than the sensor uses its whole width; a narrower one is cut at
     * the sides.
     */
    static float fieldOfView(float sensorWidth, float sensorHeight, float focal, float aspect) {
        if (sensorWidth <= 0 || sensorHeight <= 0 || focal <= 0) return 0;
        float used = Math.min(sensorWidth, sensorHeight * aspect);
        return (float) Math.toDegrees(2 * Math.atan(used / (2 * focal)));
    }

    /**
     * DRAUPNE Link: the size to open the camera at for the one asked for. The nearest of the same shape (16:9 for
     * 16:9) when it is at most 1.5 times as wide as asked; else the smallest size of any shape with at least the
     * asked pixels; else the nearest of any. A phone that lists 16:9 only at 1280x720 and 1920x1080 took 1280x720
     * for the PC's 640x360 and 480x270 (pc.26: the nearest 16:9), so bad coverage mode and the survival tier never
     * shrank the picture - 1280x720 at 250 kbit/s. Now they get 640x480 (pc.27); the PC letterboxes it. Upstream
     * took the nearest of any shape: 1024x768 for 960x540, a squashed picture with hardly fewer pixels.
     */
    static Size pickSize(Size[] sizes, Size target) {
        if (sizes == null || sizes.length == 0) return null;
        double aspect = (double) target.getWidth() / Math.max(1, target.getHeight());
        Size sameShape = null, any = null;
        int sameDiff = -1, anyDiff = -1;
        for (Size size : sizes) {
            int diff = Math.abs(size.getWidth() - target.getWidth()) + Math.abs(size.getHeight() - target.getHeight());
            if (anyDiff == -1 || diff < anyDiff) {
                anyDiff = diff;
                any = size;
            }
            boolean shape = Math.abs((double) size.getWidth() / Math.max(1, size.getHeight()) - aspect) < 0.03;
            if (shape && (sameDiff == -1 || diff < sameDiff)) {
                sameDiff = diff;
                sameShape = size;
            }
        }
        if (sameShape != null && sameShape.getWidth() <= target.getWidth() * 3 / 2) return sameShape;
        long wanted = (long) target.getWidth() * target.getHeight();
        Size smallest = null;
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels < wanted) continue;
            if (smallest == null || pixels < (long) smallest.getWidth() * smallest.getHeight()) smallest = size;
        }
        return smallest != null ? smallest : any;
    }

    /**
     * DRAUPNE Link: the camera takes only a frame-rate range it lists; one it doesn't (DRAUPNE Link asks for a steady
     * 30-30) can stop the picture. The listed range nearest the wanted one: the same, else the same top
     * with the nearest bottom (as steady as it goes), else the nearest top.
     */
    static Range<Integer> supportedRange(Range<Integer>[] listed, Range<Integer> wanted) {
        if (listed == null || listed.length == 0) return wanted;
        Range<Integer> best = null;
        long bestScore = Long.MAX_VALUE;
        for (Range<Integer> r : listed) {
            if (r.equals(wanted)) return r;
            long score = Math.abs(r.getUpper() - wanted.getUpper()) * 1000L + Math.abs(r.getLower() - wanted.getLower());
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private void configureCamera(CaptureRequest.Builder captureRequest){
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        // pc.50: overwatch's picture alone at the camera's lowest range (15 here, not the video's 30): half the frames for
        // the same 8 analysed a second, and up to 66 ms exposure at night; no stabiliser, no edge work for a 320 px picture
        boolean alone = watchOnly;
        captureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, alone && watchRange != null ? watchRange : frameRate);
        captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
        captureRequest.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        captureRequest.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED);
        captureRequest.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        captureRequest.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF);
        captureRequest.set(CaptureRequest.JPEG_QUALITY, (byte)95);
        captureRequest.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, alone ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                : CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            captureRequest.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CameraMetadata.STATISTICS_OIS_DATA_MODE_OFF);
        }
        captureRequest.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
        captureRequest.set(CaptureRequest.EDGE_MODE, alone ? CameraMetadata.EDGE_MODE_OFF : CameraMetadata.EDGE_MODE_FAST);
        captureRequest.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF);
        captureRequest.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_FAST);
        captureRequest.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
        captureRequest.set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, false);
    }

    @Override
    public int getCurrentFps(){
        long current = System.currentTimeMillis();
        float timeSec = (current - cameraFrameTimestamp) / 1000f;
        if (timeSec < 0.5f && lastFps > 0) return lastFps;
        int fps = Math.round(cameraFrameCounter / timeSec);
        cameraFrameCounter = 0;
        cameraFrameTimestamp = current;
        lastFps = fps;
        return fps;
    }

    @Override
    public int getTargetFps(){
        return frameRate.getUpper();
    }

    @Override
    public int getWidth(){
        return cameraResolution.getWidth();
    }

    @Override
    public int getHeight(){
        return cameraResolution.getHeight();
    }

    @Override
    public boolean isFrontFacing(){
        return frontFacing;
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback(){
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber){
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            isStarted = true;
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult){
            super.onCaptureProgressed(session, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result){
            super.onCaptureCompleted(session, request, result);
            cameraFrameCounter++;
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure){
            super.onCaptureFailed(session, request, failure);
            log("onCaptureFailed. Reason: "+failure.getReason());
        }

        @Override
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber){
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
        }

        @Override
        public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId){
            super.onCaptureSequenceAborted(session, sequenceId);
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber){
            super.onCaptureBufferLost(session, request, target, frameNumber);
        }
    };

    @Override
    public void close(){
        openGeneration++; // pc.31: an open still under way closes its device when it comes (stateCallback)
        isOpened = false;
        isOpening = false;
        isStarted = false;
        if (camera != null){
            camera.close();
            log("Camera device closed.");
        }
        if (handlerThread != null){
            handlerThread.quitSafely();
            handlerThread = null; // the next open starts a new one
        }
        // pc.35: the motion detection's reader and its thread, after the camera (no session left to feed them)
        sessionAnalysis = null;
        if (analysisReader != null) {
            analysisReader.close();
            analysisReader = null;
        }
        if (spareReader != null) {
            spareReader.close();
            spareReader = null;
        }
        if (analysisThread != null) {
            analysisThread.quitSafely();
            analysisThread = null;
        }
    }
}
