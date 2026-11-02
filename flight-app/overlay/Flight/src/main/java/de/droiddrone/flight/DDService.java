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
 *  Modified for DRAUPNE Link: stops quietly when Android restarts it without the app's settings; one start only
 *  (a second CONNECT tap is ignored); holds a partial wake lock while running; takes the location foreground type
 *  when the pilot allows location later; re-probes the USB when the flight controller is plugged in.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.Timer;
import java.util.TimerTask;

import de.droiddrone.common.FcInfo;
import de.droiddrone.common.MediaCommon;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.SettingsCommon;

public class DDService extends Service {
    public static final String CHANNEL_ID = "DDServiceChannel";
    public static boolean isRunning = false;
    public static boolean isConnected = false;
    private static int serialPortStatus = Serial.STATUS_NOT_INITIALIZED;
    private static FcInfo fcInfo = null;
    private static int fcApiCompatibilityLevel = FcCommon.FC_API_COMPATIBILITY_UNKNOWN;
    private Timer mainTimer;
    private Udp udp;
    private CameraManager cameraManager;
    private StreamEncoder streamEncoder;
    private Mp4Recorder mp4Recorder;
    private AudioSource audioSource;
    private Serial serial;
    private Msp msp;
    private Mavlink mavlink;
    private PhoneTelemetry phoneTelemetry;
    private MavlinkUdpBridge mavlinkUdpBridge;
    private int connectionMode;
    /** DRAUPNE Link: the pilot allowed location after the service started (MainActivity): take the foreground type now. */
    public static final String ACTION_LOCATION_GRANTED = "de.droiddrone.flight.pc.LOCATION_GRANTED";
    private static volatile DDService running;
    /** DRAUPNE Link: keeps the CPU up while the drone flies on this phone - the screen may be off or locked. */
    private PowerManager.WakeLock wakeLock;
    private Notification notification;
    private boolean locationType;


    public DDService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // DRAUPNE Link: restarted by Android after the app's process was killed, the settings (MainActivity.config)
        // aren't there and the service crashed; stop quietly instead (the pilot starts it from the app)
        if (MainActivity.config == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (isRunning) {
            // DRAUPNE Link: a second start (CONNECT tapped twice, or the location grant below) while running. Upstream
            // started everything again: the second udp.close() could make the first bind fail, and the service
            // stopped itself. The foreground call goes again all the same: Android wants one per startForegroundService
            goForeground(locationType || (intent != null && ACTION_LOCATION_GRANTED.equals(intent.getAction())));
            return START_REDELIVER_INTENT;
        }
        isConnected = false;
        String destIp = MainActivity.config.getIp();
        int port = MainActivity.config.getPort();
        String key = MainActivity.config.getKey();
        connectionMode = MainActivity.config.getConnectionMode();

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText("DRAUPNE Flight is running")
                .setSmallIcon(R.drawable.baseline_rocket_launch_24)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        goForeground(true);
        acquireWakeLock();
        running = this;

        if (cameraManager == null) cameraManager = new CameraManager(this, MainActivity.config);
        if (audioSource == null) audioSource = new AudioSource(this);
        if (MainActivity.config.isRecordAudio()) audioSource.initialize(MediaCommon.mp4AudioConsumerId);
        if (MainActivity.config.isSendAudioStream()) audioSource.initialize(MediaCommon.streamAudioConsumerId);
        if (streamEncoder == null) streamEncoder = new StreamEncoder(cameraManager, audioSource, MainActivity.config);
        if (mp4Recorder == null) mp4Recorder = new Mp4Recorder(cameraManager, this, audioSource, MainActivity.config);
        this.getExternalMediaDirs();
        if (serial != null) serial.close();
        serial = new Serial(this, MainActivity.config);
        msp = new Msp(serial, MainActivity.config);
        mavlink = new Mavlink(serial, MainActivity.config);
        mavlinkUdpBridge = new MavlinkUdpBridge(MainActivity.config, mavlink);
        mavlink.setMavlinkUdpBridge(mavlinkUdpBridge);
        serial.initialize(msp, mavlink);
        phoneTelemetry = new PhoneTelemetry(this, streamEncoder, cameraManager, mp4Recorder);
        phoneTelemetry.initialize();
        if (udp != null) udp.close();
        udp = new Udp(destIp, port, key, connectionMode, streamEncoder, mp4Recorder, cameraManager, msp, mavlink, phoneTelemetry, MainActivity.config, serial, mavlinkUdpBridge);
        mavlinkUdpBridge.setUdp(udp);
        mavlink.setUdp(udp);
        Thread t1 = new Thread(() -> {
            if (!udp.initialize()){
                log("UDP initialize error.");
                stopSelf();
            }
        });
        t1.start();
        isRunning = true;
        startMainTimer();
        return START_REDELIVER_INTENT;
    }

    /**
     * DRAUPNE Link: the foreground types - and location too, when wanted and the pilot allowed it (the phone's own
     * GPS for the PC's map): without the permission the type isn't allowed, and Android refuses the whole service.
     * Called again with the type added when the pilot allows location while the service runs: on Android 14 a
     * service without the type gets no location updates in the background.
     */
    private void goForeground(boolean withLocation) {
        boolean location = withLocation
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (location) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                startForeground(1, notification, types);
            }else{
                startForeground(1, notification);
            }
            locationType = location;
        } catch (Exception e) {
            // the location type refused (the app not in the foreground at that moment): keep what there was
            log("startForeground error: " + e);
            if (location && !locationType) {
                goForeground(false);
                return;
            }
        }
        if (location) PhoneLocation.start(getApplicationContext());
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || wakeLock != null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DRAUPNE:flight");
            wakeLock.acquire();
        } catch (Exception e) {
            log("Wake lock error: " + e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            log("Wake lock release error: " + e);
        }
        wakeLock = null;
    }

    /** The flight controller was plugged in (MainActivity got USB_DEVICE_ATTACHED): look for it now, if not on it. */
    public static void usbDeviceAttached() {
        DDService s = running;
        if (s == null || s.serial == null) return;
        int status = s.serial.getStatus();
        if (status == Serial.STATUS_SERIAL_PORT_OPENED) return;
        log("USB device attached: looking for the flight controller");
        if (status == Serial.STATUS_USB_PERMISSION_DENIED || status == Serial.STATUS_USB_PERMISSION_REQUESTED) {
            s.serial.initialize(s.msp, s.mavlink); // the probe over again: the permission is asked for anew
        } else {
            s.serial.wakeInit();
        }
    }

    @Override
    public void onDestroy() {
        running = null;
        PhoneLocation.stop();
        closeAll();
        releaseWakeLock();
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "DD", NotificationManager.IMPORTANCE_HIGH);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);
    }

    public static int getSerialPortStatus(){
        return serialPortStatus;
    }

    public static FcInfo getFcInfo(){
        return fcInfo;
    }

    public static int getFcApiCompatibilityLevel(){
        return fcApiCompatibilityLevel;
    }

    private void startMainTimer() {
        if (mainTimer != null) {
            mainTimer.cancel();
            mainTimer.purge();
        }
        mainTimer = new Timer();
        TimerTask tt = new TimerTask() {
            @Override
            public void run() {
                if (!isRunning){
                    mainTimer.cancel();
                    return;
                }
                isConnected = udp.isConnected();
                if (isConnected){
                    if (MainActivity.config.getMavlinkUdpBridge() != SettingsCommon.MavlinkUdpBridge.disabled
                            && MainActivity.config.getMavlinkUdpBridge() != SettingsCommon.MavlinkUdpBridge.redirectFromControlDevice) {
                        if (!mavlinkUdpBridge.isInitialized() && mavlink.isInitialized()) {
                            mavlinkUdpBridge.initialize();
                        }
                    }else{
                        if (mavlinkUdpBridge.isInitialized()) mavlinkUdpBridge.close();
                    }
                }else{
                    if (connectionMode == SettingsCommon.ConnectionMode.overServer) udp.sendConnect();
                }
                serialPortStatus = serial.getStatus();
                if (serialPortStatus == Serial.STATUS_SERIAL_PORT_ERROR){
                    if (serial != null && (msp != null && mavlink != null)) {
                        fcInfo = null;
                        serial.initialize(msp, mavlink);
                    }
                }
                if (serial != null && fcInfo == null) {
                    if (serial.isMavlink()){
                        if (mavlink != null) fcInfo = mavlink.getFcInfo();
                    } else {
                        if (msp != null) fcInfo = msp.getFcInfo();
                    }
                    fcApiCompatibilityLevel = FcCommon.getFcApiCompatibilityLevel(fcInfo);
                }
            }
        };
        mainTimer.schedule(tt, 1000, 1000);
    }

    private void closeAll() {
        isRunning = false;
        try {
            if (mainTimer != null) {
                mainTimer.cancel();
                mainTimer.purge();
            }
        }catch (Exception e){
            //
        }
        if (mavlinkUdpBridge != null) mavlinkUdpBridge.close();
        if (phoneTelemetry != null) phoneTelemetry.close();
        if (serial != null) serial.close();
        if (udp != null){
            udp.disconnect();
        }
        if (streamEncoder != null) streamEncoder.close();
        if (mp4Recorder != null) mp4Recorder.close();
        if (audioSource != null) audioSource.close();
        if (cameraManager != null) cameraManager.close();
        isConnected = false;
        fcInfo = null;
        System.gc();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}