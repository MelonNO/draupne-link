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
 *  Modified for DRAUPNE Link (pc.35): the motion detection's pictures (MotionWatch) from the camera library's frame
 *  callback, set only while MotionWatch analyses (setAnalysis) and again after every open; the callback runs on the
 *  library's capture thread, not the one that draws into the encoder.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import com.serenegiant.libuvccamera.LibUVCCameraUSBMonitor;
import com.serenegiant.libuvccamera.UVCCamera;

import java.util.List;

public class UsbCamera implements Camera {
    private final int usbDeviceClass = 239;
    private final int usbDeviceSubClass = 2;
    private final int[] excludeVendorIds = {11388/*Quectel LTE Module*/};
    private final Context context;
    private final Config config;
    private final Object mSync = new Object();
    private Size targetResolution;
    private Range<Integer> targetFrameRange;
    private StreamEncoder streamEncoder;
    private Mp4Recorder mp4Recorder;
    private Surface recorderSurface;
    private LibUVCCameraUSBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private boolean isOpened;
    private int currentFps;
    private long currentFpsTs;
    private UsbDevice usbDevice;
    /** pc.35: MotionWatch wants pictures; what the camera was last told (the callback is set or not). */
    private volatile boolean analysisOn;
    private boolean analysisSet;

    public UsbCamera(Context context, Config config){
        this.context = context;
        this.config = config;
    }

    @Override
    public boolean initialize(StreamEncoder streamEncoder, Mp4Recorder mp4Recorder){
        if (usbMonitor != null || uvcCamera != null) close();
        this.streamEncoder = streamEncoder;
        this.mp4Recorder = mp4Recorder;
        targetResolution = new Size(config.getCameraResolutionWidth(), config.getCameraResolutionHeight());
        targetFrameRange = new Range<>(config.getCameraFpsMin(), config.getCameraFpsMax());
        usbMonitor = new LibUVCCameraUSBMonitor(context, onDeviceConnectListener);
        synchronized (mSync) {
            usbMonitor.register();
        }
        return true;
    }

    private boolean checkUsbDevice(UsbDevice device){
        if (device == null) return false;
        int vendorId = device.getVendorId();
        for (int id : excludeVendorIds){
            if (id == vendorId) return false;
        }
        return device.getDeviceClass() == usbDeviceClass && device.getDeviceSubclass() == usbDeviceSubClass;
    }

    private final LibUVCCameraUSBMonitor.OnDeviceConnectListener onDeviceConnectListener = new LibUVCCameraUSBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (!isOpened && device != null) {
                if (checkUsbDevice(device)) {
                    usbDevice = device;
                    openCamera();
                }
            }
        }

        @Override
        public void onDetach(UsbDevice device) {
            synchronized (mSync) {
                if (uvcCamera != null && device != null) {
                    if (device.equals(uvcCamera.getDevice())) {
                        isOpened = false;
                        uvcCamera.destroy();
                        uvcCamera = null;
                    }
                }
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock) {
            synchronized (mSync) {
                if (uvcCamera != null && device != null) {
                    if (device.equals(uvcCamera.getDevice())){
                        isOpened = false;
                        uvcCamera.close();
                    }
                }
            }
        }

        @Override
        public void onConnect(UsbDevice device, LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {

        }

        @Override
        public void onCancel(final UsbDevice device) {

        }
    };

    @Override
    public boolean isOpened(String cameraId){
        return isOpened;
    }

    @Override
    public void startCapture(){
        if (recorderSurface == null || uvcCamera == null || !isOpened || !uvcCamera.isRunning()) return;
        uvcCamera.startCapture(recorderSurface);
    }

    @Override
    public void stopCapture(){
        if (uvcCamera == null) return;
        uvcCamera.stopCapture();
    }

    private UsbDevice findUsbCameraDevice(){
        List<UsbDevice> usbDevices;
        try {
            usbDevices = usbMonitor.getDeviceList();
        }catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        if (usbDevices.isEmpty()) return null;
        int index = -1;
        for (UsbDevice usbDevice : usbDevices) {
            if (checkUsbDevice(usbDevice)) {
                index++;
                if (index == config.getCurrentUsbCameraIndex()) return usbDevice;
            }
        }
        return null;
    }

    @Override
    public boolean openCamera(){
        synchronized (mSync) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }

            if (usbDevice == null) usbDevice = findUsbCameraDevice();
            if (usbDevice == null) return false;

            if (!usbMonitor.hasPermission(usbDevice)) {
                usbMonitor.requestPermission(usbDevice);
                return false;
            }

            if (uvcCamera != null) {
                uvcCamera.destroy();
                uvcCamera = null;
            }
            uvcCamera = new UVCCamera();
            try {
                LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock = new LibUVCCameraUSBMonitor.UsbControlBlock(usbMonitor, usbDevice);
                uvcCamera.open(ctrlBlock);
            } catch (UnsupportedOperationException e) {
                log("Camera open error: " + e);
            }
            log("openCamera. SupportedSizes:" + uvcCamera.getSupportedSize());

            int frameFormat = config.getUsbCameraFrameFormat();

            try {
                uvcCamera.setPreviewSize(
                        targetResolution.getWidth(),
                        targetResolution.getHeight(),
                        UVCCamera.DEFAULT_CAMERA_ANGLE,
                        targetFrameRange.getLower(),
                        targetFrameRange.getUpper(),
                        frameFormat);
            } catch (final IllegalArgumentException e) {
                uvcCamera.destroy();
                return false;
            }

            Surface streamEncoderSurface = null;
            if (streamEncoder.isVideoEncoderInitialized()){
                streamEncoderSurface = streamEncoder.getSurface();
            }
            if (streamEncoderSurface == null) {
                streamEncoderSurface = streamEncoder.initializeVideo();
            }
            if (streamEncoderSurface == null) {
                close();
                return false;
            }

            recorderSurface = mp4Recorder.initialize();
            uvcCamera.setPreviewDisplay(streamEncoderSurface);
            isOpened = true;
            uvcCamera.startPreview();
            analysisSet = false;
            applyAnalysis(); // a new camera: the callback set again if MotionWatch analyses
            uvcCamera.updateCameraParams();
            if (config.isUsbCameraReset()) {
                uvcCamera.resetBrightness();
                uvcCamera.resetContrast();
                uvcCamera.resetHue();
                uvcCamera.resetWhiteBlance();
                uvcCamera.resetSaturation();
                uvcCamera.resetGamma();
                uvcCamera.resetGain();
                uvcCamera.resetSharpness();
                uvcCamera.resetZoom();
                uvcCamera.resetFocus();
                uvcCamera.setAutoWhiteBlance(true);
            }
            return true;
        }
    }

    @Override
    public int getCurrentFps(){
        long now = System.currentTimeMillis();
        if (now - currentFpsTs < 1000 && currentFps > 0) return currentFps;
        if (uvcCamera != null) {
            currentFps = uvcCamera.getCurrentFps();
            currentFpsTs = now;
            return currentFps;
        }
        return 0;
    }

    @Override
    public int getTargetFps(){
        if (uvcCamera != null && uvcCamera.isRunning()) return uvcCamera.getDefaultCameraFps();
        return targetFrameRange.getUpper();
    }

    @Override
    public int getWidth(){
        if (uvcCamera != null && uvcCamera.isRunning()) return uvcCamera.getFrameWidth();
        return targetResolution.getWidth();
    }

    @Override
    public int getHeight(){
        if (uvcCamera != null && uvcCamera.isRunning()) return uvcCamera.getFrameHeight();
        return targetResolution.getHeight();
    }

    @Override
    public boolean isFrontFacing(){
        return false;
    }

    /** pc.35: MotionWatch's pictures on or off (asked every 0.1 s; the library is told only of a change). */
    @Override
    public void setAnalysis(boolean on){
        analysisOn = on;
        if (on != analysisSet) applyAnalysis();
    }

    private void applyAnalysis(){
        synchronized (mSync) {
            if (uvcCamera == null || !isOpened) return;
            boolean on = analysisOn;
            try {
                if (on) MotionWatch.setFrameRate(0); // pc.50: this camera's rate isn't known here - not the internal camera's last
                uvcCamera.setFrameCallback(on ? MotionWatch.uvcFrames(uvcCamera.getFrameWidth(), uvcCamera.getFrameHeight()) : null,
                        UVCCamera.PIXEL_FORMAT_NV21);
                analysisSet = on;
            } catch (RuntimeException e) {
                log("Motion detection's frames " + (on ? "on" : "off") + ": " + e);
            }
        }
    }

    @Override
    public boolean analysisAvailable(){
        return isOpened && uvcCamera != null;
    }

    @Override
    public void startPreview() {
        if (uvcCamera != null && isOpened && !uvcCamera.isRunning()){
            uvcCamera.startPreview();
        }
    }

    @Override
    public boolean isStarted(){
        return uvcCamera != null && isOpened && uvcCamera.isRunning();
    }

    @Override
    public void close(){
        isOpened = false;
        synchronized (mSync) {
            if (usbMonitor != null) {
                usbMonitor.unregister();
                usbMonitor.destroy();
            }
            if (uvcCamera != null) {
                uvcCamera.destroy();
                uvcCamera = null;
                log("Camera device closed.");
            }
            usbDevice = null;
        }
    }
}
