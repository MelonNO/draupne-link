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
 *  when the pilot allows location later; re-probes the USB when the flight controller is plugged in. pc.21: the
 *  power modes' hands (PowerMode decides, this does): standby slows the GPS and the FC polling and stops the video;
 *  deep sleep lets go of the port, the GPS, the socket and the wake lock and locks the screen; a check opens the
 *  socket; waking brings it all back and lights the screen with a full-screen intent.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
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
    static final String QUIET_CHANNEL_ID = "DD_quiet";
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
    /** pc.21: the channel of the wake notification, whose full-screen intent lights the screen after a sleep check. */
    public static final String WAKE_CHANNEL_ID = "DDWakeChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int WAKE_NOTIFICATION_ID = 2;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());


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
        notification = buildNotification(getString(R.string.dd_notification_running));
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
        PowerMode.start(this);
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
        PowerMode.stop(); // no alarm left behind; the mode is normal for the next start
        PhoneLocation.stop();
        closeAll();
        releaseWakeLock();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "DD", NotificationManager.IMPORTANCE_HIGH);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);
        // pc.22: the running notification on a quiet channel (a channel's importance can't be lowered once made, so
        // a new one): deep sleep changes its text at every check, and a high channel would sound, buzz or light
        // the screen for each - power and annoyance for nothing
        NotificationChannel quiet = new NotificationChannel(QUIET_CHANNEL_ID, getString(R.string.dd_quiet_channel), NotificationManager.IMPORTANCE_LOW);
        quiet.setDescription(getString(R.string.dd_quiet_channel_why));
        quiet.setSound(null, null);
        quiet.enableVibration(false);
        quiet.enableLights(false);
        manager.createNotificationChannel(quiet);
        // pc.21: a high-importance channel of its own for the wake notification, or its full-screen intent is ignored
        NotificationChannel wakeChannel = new NotificationChannel(WAKE_CHANNEL_ID, getString(R.string.dd_wake_channel), NotificationManager.IMPORTANCE_HIGH);
        wakeChannel.setDescription(getString(R.string.dd_wake_channel_why));
        manager.createNotificationChannel(wakeChannel);
    }

    /** The running notification with the given text; a tap opens the app (which wakes a sleeping phone). */
    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, QUIET_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.baseline_rocket_launch_24)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        try {
            notification = buildNotification(text);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            log("Notification update error: " + e);
        }
    }

    // ---- pc.21: what PowerMode asks of the service (on its worker thread) ----

    /** Whether the flight controller reports armed (MSP; a MAVLink FC isn't read for this). */
    boolean isFcArmed() {
        Msp m = msp;
        return m != null && m.isArmed();
    }

    /** Whether a PC has connected (a keyed Connect taken), as the check window asks. */
    boolean isPcConnected() {
        Udp u = udp;
        return u != null && u.isConnected();
    }

    /** Standby on: no video, the GPS every 30 s, the FC once a second. Off: the rates back (the PC restarts video). */
    void powerStandby(boolean on) {
        if (on) udp.stopVideo();
        msp.setSlowPoll(on);
        PhoneLocation.setInterval(on ? PhoneLocation.STANDBY_INTERVAL_MS : PhoneLocation.NORMAL_INTERVAL_MS);
        updateNotification(on ? getString(R.string.dd_notification_standby) : getString(R.string.dd_notification_running));
    }

    /**
     * Deep sleep: everything let go of, in this order - the video, the flight controller's port, the GPS, the
     * socket (the link drops), the wake lock; then the screen, when the app may lock it. The service stays in the
     * foreground with its types: Android would end it otherwise, and the wake needs them back anyway.
     */
    void powerSleep(long nextCheckAt) {
        udp.stopVideo();
        fcInfo = null;
        serial.close();
        PhoneLocation.stop();
        udp.close();
        // pc.23: nothing left ticking - the phone telemetry thread (camera fps, battery, network state once a
        // second), the microphone's thread and the service's own timer all stop; the alarm is all that wakes it
        phoneTelemetry.close();
        audioSource.close();
        stopMainTimer();
        isConnected = false;
        powerSleepOn(nextCheckAt);
        releaseWakeLock();
        lockScreen();
    }

    /** Between checks: the notification says when the next one is. */
    void powerSleepOn(long nextCheckAt) {
        updateNotification(getString(R.string.dd_notification_sleep, PowerMode.time(this, nextCheckAt)));
    }

    /** A check window: the socket open and listening (new receive, buffer and stats threads). */
    void powerListen() {
        if (!udp.initialize()) log("Sleep check: the socket could not be opened");
        startMainTimer(); // tends the socket for the window
        updateNotification(getString(R.string.dd_notification_checking));
    }

    /** The sleep check's "still here" to the PC: the battery as Android reports it. */
    void powerHello(int nextCheckMin) {
        int percent = 255;
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) percent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception ignored) {
            // unknown then
        }
        boolean charging = false;
        int mV = 0;
        try {
            android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                charging = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                mV = b.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0);
            }
        } catch (Exception ignored) {
            // unknown then
        }
        if (!udp.sendSleepHello(percent < 0 ? 255 : percent, charging, nextCheckMin, mV)) log("Sleep check: no PC address to say hello to");
    }

    /** The check window closed without the PC: the socket shut again. */
    void powerListenEnd() {
        udp.close();
        stopMainTimer();
        isConnected = false;
    }

    /**
     * Awake: the wake lock, the flight controller's port (the USB permission was given with "always allow"; a
     * replug prompt goes the usual way, the status card says what to do), the GPS, the notification; after a real
     * sleep also the screen, lit over the lock screen.
     */
    void powerWake(boolean fromSleep) {
        acquireWakeLock();
        if (fromSleep) { // what deep sleep stopped, back: the telemetry thread, the microphone, the timer
            phoneTelemetry.initialize();
            if (MainActivity.config.isRecordAudio()) audioSource.initialize(MediaCommon.mp4AudioConsumerId);
            if (MainActivity.config.isSendAudioStream()) audioSource.initialize(MediaCommon.streamAudioConsumerId);
            startMainTimer();
        }
        msp.setSlowPoll(false);
        PhoneLocation.setInterval(PhoneLocation.NORMAL_INTERVAL_MS);
        if (serial.getStatus() == Serial.STATUS_NOT_INITIALIZED) {
            fcInfo = null;
            serial.initialize(msp, mavlink);
        }
        if (locationType) PhoneLocation.start(getApplicationContext()); // the foreground type was kept all along
        updateNotification(getString(R.string.dd_notification_running));
        if (fromSleep) wakeScreen();
    }

    /** The screen off now, when the pilot made the app a device admin; else it times out by itself. */
    private void lockScreen() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isAdminActive(PowerMode.adminComponent(this))) {
                log("Deep sleep: not a device admin, the screen times out on its own");
                return;
            }
            dpm.lockNow();
            log("Deep sleep: screen locked");
        } catch (Exception e) {
            log("Deep sleep: lockNow error: " + e);
        }
    }

    /**
     * The screen on and the app in front of the lock screen: a notification with a full-screen intent to
     * MainActivity (the sanctioned way to start an activity from the background), and a bright wake lock as a
     * second way to light the screen on phones that hold the full-screen intent back. The notification goes
     * after 15 s; the activity stays.
     */
    private void wakeScreen() {
        try {
            Intent intent = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_WAKE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent full = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification wake = new NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.dd_wake_text))
                    .setSmallIcon(R.drawable.baseline_rocket_launch_24)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(full)
                    .setFullScreenIntent(full, true)
                    .setAutoCancel(true)
                    .build();
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(WAKE_NOTIFICATION_ID, wake);
                mainHandler.postDelayed(() -> manager.cancel(WAKE_NOTIFICATION_ID), 15000);
            }
        } catch (Exception e) {
            log("Wake notification error: " + e);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock bright = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "DRAUPNE:wakeScreen");
                bright.acquire(5000);
            }
        } catch (Exception e) {
            log("Screen wake lock error: " + e);
        }
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

    /** Deep sleep between checks: the timer itself stops; startMainTimer brings it back for a check or the wake. */
    private void stopMainTimer() {
        if (mainTimer != null) {
            mainTimer.cancel();
            mainTimer.purge();
            mainTimer = null;
        }
    }

    private void startMainTimer() {
        if (!isRunning && running == null) return;
        stopMainTimer();
        mainTimer = new Timer();
        TimerTask tt = new TimerTask() {
            @Override
            public void run() {
                if (!isRunning){
                    mainTimer.cancel();
                    return;
                }
                if (PowerMode.isAsleep()) { // pc.21: the socket and the port are closed on purpose; nothing to tend
                    isConnected = false;    // (pc.23: the timer is stopped in sleep; this is the odd tick in between)
                    return;
                }
                isConnected = udp.isConnected();
                if (isFcArmed()) PowerMode.fcArmed(); // standby ends by itself
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