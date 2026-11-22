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
 *  socket; waking brings it all back and lights the screen with a full-screen intent. pc.36: overwatch sleep - as
 *  deep sleep, but the wake lock stays and the camera watches (Overwatch); the battery's readings for its floor.
 *  pc.58 (Q-006, Tailscale only): upstream's MAVLink UDP bridge never opens.
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

import de.droiddrone.common.BeeperBack;
import de.droiddrone.common.BeeperQuiet;
import de.droiddrone.common.BeeperRule;
import de.droiddrone.common.FcInfo;
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

    /** The service while it runs, else null (pc.32: the remote update's context). */
    static DDService instance() {
        return running;
    }
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
        CrashGuard.install(this); // pc.55
        Overwatch.clips(this); // pc.38: overwatch clips a restart left are taken back (scanned on a thread of its own)
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // DRAUPNE Link: restarted by Android after the app's process was killed, the settings (MainActivity.config)
        // aren't there and the service crashed; stop quietly instead (the pilot starts it from the app)
        if (MainActivity.config == null) {
            // pc.55: ... and the app is asked to open itself, as after an error (CrashGuard): opened, it has its
            // settings and starts this service as it always does. Android starts a service again whose process it
            // (or a battery saver) ended - without the window, where the window was not in front
            CrashGuard.serviceWithoutApp(getApplicationContext());
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
        PhoneTailscale.with(this); // Q-006: Android's networks, for the link's Tailscale address
        String destIp = MainActivity.config.getIp();
        int port = MainActivity.config.getPort();
        String key = MainActivity.config.getKey();
        connectionMode = MainActivity.config.getConnectionMode();

        createNotificationChannel();
        notification = buildNotification(getString(R.string.dd_notification_running));
        goForeground(true);
        acquireWakeLock();
        running = this;
        Silent.on(this); // pc.47: no rings or buzzing on the drone's phone while it works

        if (cameraManager == null) cameraManager = new CameraManager(this, MainActivity.config);
        if (audioSource == null) audioSource = new AudioSource(this);
        // pc.50: the microphone isn't opened here any more - the live audio, the recorders and the listening open it when
        // they need it; opened here it recorded for nobody (3.6 h through a standby in the pilot's phone's log)
        if (streamEncoder == null) streamEncoder = new StreamEncoder(cameraManager, audioSource, MainActivity.config);
        if (mp4Recorder == null) mp4Recorder = new Mp4Recorder(cameraManager, this, audioSource, MainActivity.config);
        java.io.File[] media = this.getExternalMediaDirs();
        // the phone's recordings (upstream's Mp4Recorder writes them there) stay out of the phone's gallery (NoMedia)
        if (media != null && media.length > 0 && media[0] != null) markNoMedia(new java.io.File(media[0], "Video"));
        if (serial != null) serial.close();
        serial = new Serial(this, MainActivity.config);
        msp = new Msp(serial, MainActivity.config);
        mavlink = new Mavlink(serial, MainActivity.config);
        mavlinkUdpBridge = new MavlinkUdpBridge(MainActivity.config, mavlink) {
            private boolean said;

            /**
             * Q-006 (Tailscale only): upstream's MAVLink UDP bridge stays shut. Its socket listens on every address of
             * the phone and passes what its peer sends to the flight controller; the phone answers over Tailscale and
             * nothing else. It was off already (DRAUPNE Link sends it off, the phone keeps its own value: PhoneOwnConfig).
             */
            @Override
            public void initialize() {
                if (said) return;
                said = true;
                log("MAVLink UDP bridge: stays off - this phone answers over Tailscale only");
            }
        };
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
            udp.rememberPilot(getApplicationContext()); // who flew it before the app started anew (KeptPilot), before anything is heard
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

    /** A media folder out of the phone's gallery (NoMedia); marked now: what the media index has of it goes. */
    private void markNoMedia(java.io.File dir) {
        if (!de.droiddrone.common.NoMedia.mark(dir)) return;
        android.media.MediaScannerConnection.scanFile(getApplicationContext(), new String[]{dir.getPath()}, null, null);
        log("Recordings: out of the phone's gallery");
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
        // pc.50: not asleep - a second start (the location grant) switched the GPS back on in deep sleep and overwatch
        if (location && !PowerMode.isSleepMode()) PhoneLocation.start(getApplicationContext());
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

    /**
     * pc.50: the processor stays up this long after the service's wake lock goes for a sleep. The lock went at once,
     * while the camera's close was only queued on another thread - and overwatch's "left for deep sleep" to the PCs
     * (five, a second apart) had sent one: with the processor asleep the camera stayed open and the PC showed OVERWATCH
     * until the next check. Timed: it lets go by itself.
     */
    private static final long CLOSING_AWAKE_MS = 6000;
    private PowerManager.WakeLock closingLock;

    private synchronized void stayAwakeToClose() {
        try {
            if (closingLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                closingLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DRAUPNE:closing");
                closingLock.setReferenceCounted(false); // asked again, the time starts over; one timed release ends it
            }
            closingLock.acquire(CLOSING_AWAKE_MS);
        } catch (Exception e) {
            log("Closing wake lock error: " + e);
        }
    }

    /** The flight controller was plugged in (MainActivity got USB_DEVICE_ATTACHED): look for it now, if not on it. */
    public static void usbDeviceAttached() {
        DDService s = running;
        if (s == null || s.serial == null) return;
        if (PowerMode.isSleepMode()) return; // pc.50: asleep the FC's port is closed on purpose; the wake opens it
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
        Overwatch.shutdown(); // pc.36: the camera, the event's recorder, the overwatch socket
        PowerMode.stop(); // no alarm left behind; the mode is normal for the next start
        PhoneLocation.stop();
        Silent.off(this); // pc.47: the phone's own ringer mode back
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

    /**
     * Whether the flight controller may be armed, as a guard asks (pc.31): armed, or not known to be disarmed - a
     * stale status, an FC coming back after a USB glitch, a MAVLink FC without a fresh heartbeat (FcArmedState).
     */
    boolean isFcArmed() {
        return fcArmedState() != FcArmedState.DISARMED;
    }

    /** Whether the flight controller reports armed now (standby ends by itself on it; unknown doesn't end it). */
    boolean isFcArmedNow() {
        return fcArmedState() == FcArmedState.ARMED;
    }

    int fcArmedState() {
        return FcArmedState.of(msp, mavlink);
    }

    /** Q-004: a flight controller is on the port, answering or not - for the screen's armed rule (ArmedTouch). */
    boolean isFcBoardOnPort() {
        return FcArmedState.boardOnPort(msp);
    }

    /** Whether a PC has connected (a keyed Connect taken), as the check window asks. */
    boolean isPcConnected() {
        Udp u = udp;
        return u != null && u.isConnected();
    }

    /** Standby on: no video, the GPS every 30 s, the FC once a second. Off: the rates back (the PC restarts video). */
    void powerStandby(boolean on) {
        if (on) udp.pilotToStandby(); // no pilot kept for a restart in standby (KeptPilot)
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
        PhoneHeading.stop(this); // pc.50: the link's thread that stops it goes with the socket
        closeMavlinkBridge();
        udp.stopVideo();
        fcInfo = null;
        quietTheDrone("deep sleep");
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
        stayAwakeToClose(); // pc.50: the video's stop above is only queued - the camera closes on its own thread
        releaseWakeLock();
        lockScreen();
    }

    /**
     * pc.50: the MAVLink UDP bridge (a MAVLink FC with the bridge on) went on through sleep - its buffer thread polls
     * every 1 ms; the main timer opens it again after the wake, as at the start.
     */
    private void closeMavlinkBridge() {
        try {
            if (mavlinkUdpBridge != null && mavlinkUdpBridge.isInitialized()) mavlinkUdpBridge.close();
        } catch (RuntimeException e) {
            log("MAVLink bridge close: " + e);
        }
    }

    /** The settings kept from before a sleep, and (F-069) the id of the board they were read from. */
    private static final String BEEPER_PREFS = "beeper", BEEPER_AS_READ = "asRead", BEEPER_BOARD = "board";

    /**
     * pc.54 (the pilot, 2026-11-18: "the esc beeper still goes off when the drone is not controlled"): a sleep closes
     * the flight controller's port, and Betaflight beeps for its lost receiver - the buzzer and the motors. Before the
     * port closes those two sounds are switched off in the flight controller's memory (BeeperQuiet; not saved there: a
     * power cycle brings them back), and what it had before is kept in the phone's settings until it is written back
     * at the wake (beeperConfigRead). Not for a drone marked OUT OF ACTION: that one is to be found by its beeping.
     * F-069: kept with the board's id, and only a board that has one is switched off (the settings could not be put
     * back surely otherwise); the "off" write is read back and made again, three times at the most (BeeperBack), and
     * the log says when the flight controller never answered that it is off - the drone may then beep through the
     * sleep. (No record tells the PC of the beeper: the log is where it is said.)
     */
    private void quietTheDrone(String why) {
        try {
            if (!BeeperRule.muteForSleep(PowerMode.outOfAction())) {
                log("Out of action: the drone's beeper stays on through the " + why);
                return;
            }
            Msp m = msp;
            byte[] read = m == null ? null : m.beeperConfig();
            if (m == null || !m.isInitialized() || !BeeperQuiet.understood(read)) {
                log("The drone's beeper is not switched off for the " + why + ": its settings were not read - it may beep");
                return;
            }
            String board = m.boardId();
            if (board == null) {
                log("The drone's beeper is not switched off for the " + why + ": its board was not identified - it may beep");
                return;
            }
            android.content.SharedPreferences prefs = getSharedPreferences(BEEPER_PREFS, MODE_PRIVATE);
            byte[] kept = BeeperQuiet.fromHex(prefs.getString(BEEPER_AS_READ, ""));
            // what the flight controller had before the first quiet is the one to put back - not a quiet one read later
            byte[] keep = BeeperBack.keepWhatIsRead(read, board, kept, prefs.getString(BEEPER_BOARD, "")) ? BeeperQuiet.asRead(read) : kept;
            prefs.edit().putString(BEEPER_AS_READ, BeeperQuiet.hex(keep)).putString(BEEPER_BOARD, board).commit();
            byte[] quiet = BeeperQuiet.quiet(read);
            boolean off = BeeperBack.holds(quiet, read); // its own settings are quiet already: nothing to write
            for (int i = 0; i < BeeperBack.QUIET_TRIES && !off; i++) {
                m.writeBeeperConfig(quiet); // and asks what it has now: the answer comes to Msp on its own thread
                long until = android.os.SystemClock.elapsedRealtime() + BeeperBack.QUIET_WAIT_MS;
                while (!(off = BeeperBack.holds(quiet, m.beeperConfig())) && android.os.SystemClock.elapsedRealtime() < until) {
                    android.os.SystemClock.sleep(5);
                }
            }
            log(off ? "The drone's lost-signal beeper is off for the " + why + " (read back; back at the wake)"
                    : "The drone's lost-signal beeper may NOT be off for the " + why + ": written " + BeeperBack.QUIET_TRIES
                    + " times, never read back as off - it may beep");
        } catch (RuntimeException e) {
            log("Beeper quiet: " + e);
        }
    }

    /** F-051: the drone's beeper was switched off for a sleep and not yet put back (its settings kept here). */
    boolean beeperQuieted() {
        try {
            return !getSharedPreferences(BEEPER_PREFS, MODE_PRIVATE).getString(BEEPER_AS_READ, "").isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * pc.54, on the MSP thread at each read of the beeper settings (once a second, and right after each write of
     * them): awake again with settings kept from before a sleep - they are written back, so the drone beeps again when
     * its receiver is really lost. F-069: written until the flight controller reads them back (BeeperBack: five writes
     * a connection at the most, then said), forgotten only then, and never written to another board than theirs.
     * OUT OF ACTION's wake for the beeper (F-051, PowerMode.beeperBackThenSleep) waits for this same read-back.
     */
    void beeperConfigRead(Msp from, byte[] read) {
        try {
            int mode = PowerMode.mode();
            if (mode == PowerMode.DEEP_SLEEP || mode == PowerMode.OVERWATCH) return; // going to sleep: quietTheDrone's turn
            android.content.SharedPreferences prefs = getSharedPreferences(BEEPER_PREFS, MODE_PRIVATE);
            String kept = prefs.getString(BEEPER_AS_READ, "");
            if (kept.isEmpty()) return;
            byte[] before = BeeperQuiet.fromHex(kept);
            BeeperBack back = from.beeperBack();
            String board = from.boardId();
            int todo = before == null ? BeeperBack.FORGET // not settings at all
                    : back.read(before, prefs.getString(BEEPER_BOARD, ""), board, read, android.os.SystemClock.elapsedRealtime());
            switch (todo) {
                case BeeperBack.DONE: // the flight controller has them: only now are they forgotten
                    prefs.edit().remove(BEEPER_AS_READ).remove(BEEPER_BOARD).commit();
                    log("Awake: the drone's lost-signal beeper is back as it was (read back, " + back.writes() + " written)");
                    break;
                case BeeperBack.ADOPT:
                case BeeperBack.WRITE:
                    // kept by an app before F-069, and this board is as that quiet left it: it is theirs from here on
                    if (todo == BeeperBack.ADOPT) prefs.edit().putString(BEEPER_BOARD, board).commit();
                    from.writeBeeperConfig(before); // and asks again: the answer comes back here
                    if (back.writes() > 1) log("The drone's lost-signal beeper did not come back: written again (" + back.writes() + ")");
                    break;
                case BeeperBack.GAVE_UP:
                    if (back.sayOnce(todo)) {
                        log("The drone's lost-signal beeper is NOT back: written " + BeeperBack.MAX_WRITES + " times, never read back."
                                + " It stays off until the flight controller loses power; tried again when its port opens next");
                    }
                    break;
                case BeeperBack.OTHER_BOARD:
                    if (back.sayOnce(todo)) log("Beeper settings kept for another flight controller: this one is left as it is");
                    break;
                case BeeperBack.FORGET:
                    prefs.edit().remove(BEEPER_AS_READ).remove(BEEPER_BOARD).commit();
                    log("Beeper settings kept from before a sleep fit no flight controller here: forgotten");
                    break;
                default: // WAIT: its board is not identified yet, or the last write's read-back is still to come
                    if (board == null && back.sayOnce(todo)) log("Beeper settings wait to be put back: this flight controller is not identified yet");
                    break;
            }
        } catch (RuntimeException e) {
            log("Beeper back: " + e);
        }
    }

    /** Between checks: the notification says when the next one is (pc.39: or that it always listens). */
    void powerSleepOn(long nextCheckAt) {
        boolean overwatch = PowerMode.mode() == PowerMode.OVERWATCH;
        if (PowerMode.isAlwaysListening()) {
            updateNotification(getString(overwatch ? R.string.dd_notification_overwatch_listening : R.string.dd_notification_listening));
            return;
        }
        updateNotification(getString(overwatch ? R.string.dd_notification_overwatch : R.string.dd_notification_sleep,
                PowerMode.time(this, nextCheckAt)));
    }

    /**
     * pc.36, overwatch: as deep sleep - the video, the flight controller's port, the GPS, the socket, the telemetry, the
     * microphone and the timer go, the screen locks - but the partial wake lock stays (the camera and the motion
     * detection run with the screen off, in this camera-type foreground service) and Overwatch watches. Where the
     * drone is, is taken first: the GPS is gone after.
     */
    void powerOverwatch(long nextCheckAt) {
        int[] position = udp.positionNow();
        PhoneHeading.stop(this); // pc.50: the link's thread that stops it goes with the socket
        closeMavlinkBridge();
        udp.stopVideo();
        fcInfo = null;
        quietTheDrone("overwatch");
        serial.close();
        PhoneLocation.stop();
        udp.close();
        phoneTelemetry.close();
        audioSource.close();
        stopMainTimer();
        isConnected = false;
        acquireWakeLock(); // held all along; again in case it was let go of
        Overwatch.begin(this, udp, audioSource, position);
        powerSleepOn(nextCheckAt);
        lockScreen();
    }

    /** pc.36: overwatch becomes deep sleep (the floor, or the PC's DEEP SLEEP): the camera closes, the wake lock goes. */
    void overwatchToDeepSleep(long nextCheckAt) {
        Overwatch.end("deep sleep instead");
        // pc.50: Overwatch.end only queues the camera's close, and the floor's word to the PCs goes on for 5 s
        stayAwakeToClose();
        releaseWakeLock();
        if (nextCheckAt > 0 || PowerMode.isAlwaysListening()) powerSleepOn(nextCheckAt);
    }

    /** pc.36: the camera in use (Overwatch's watch and event sessions). */
    Camera camera() {
        CameraManager c = cameraManager;
        return c == null ? null : c.getCamera();
    }

    StreamEncoder streamEncoder() {
        return streamEncoder;
    }

    /**
     * pc.36, overwatch's floor (PowerFloor): {battery %, charging 1/0, thermal status (API 29 on), battery temperature
     * in tenths of a degree}; PowerFloor.UNKNOWN where Android doesn't say.
     */
    int[] batteryReadings() {
        int percent = de.droiddrone.common.PowerFloor.UNKNOWN, charging = 0, thermal = de.droiddrone.common.PowerFloor.UNKNOWN,
                temp = de.droiddrone.common.PowerFloor.UNKNOWN;
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                int p = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (p >= 0 && p <= 100) percent = p;
            }
            android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                charging = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0 ? 1 : 0;
                int t = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (t != Integer.MIN_VALUE) temp = t;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) thermal = pm.getCurrentThermalStatus();
            }
        } catch (Exception ignored) {
            // what is known goes
        }
        return new int[]{percent, charging, thermal, temp};
    }

    /** pc.40: the mobile signal, 0-4 bars as Android counts them (API 28 on), or FleetProtocol.UNKNOWN. */
    int signalLevel() {
        if (Build.VERSION.SDK_INT < 28) return de.droiddrone.common.FleetProtocol.UNKNOWN;
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            android.telephony.SignalStrength ss = tm == null ? null : tm.getSignalStrength();
            return ss == null ? de.droiddrone.common.FleetProtocol.UNKNOWN : ss.getLevel();
        } catch (Exception e) {
            return de.droiddrone.common.FleetProtocol.UNKNOWN;
        }
    }

    /** A check window: the socket open and listening (new receive, buffer and stats threads). */
    void powerListen() {
        if (!udp.initialize()) log("Sleep check: the socket could not be opened");
        startMainTimer(); // tends the socket for the window
        if (PowerMode.isAlwaysListening()) powerSleepOn(0); // pc.39: this window stays open
        else updateNotification(getString(R.string.dd_notification_checking));
    }

    /** The sleep check's "still here" to the PC: the battery as Android reports it. */
    /**
     * pc.50: the sleep notice in the countdown to a power-saving mode - battery, mode (in the status block), name and
     * position, heading, the next check (Udp.sendSleepNotice).
     */
    boolean powerNotice(int mode, int intervalMin, int nextCheckInS) {
        int percent = 255;
        boolean charging = false;
        int mV = 0;
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) percent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                charging = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                mV = b.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0);
            }
        } catch (Exception ignored) {
            // unknown then
        }
        int flags = mode == PowerMode.OVERWATCH ? de.droiddrone.common.OverwatchProtocol.HELLO_OVERWATCH : 0;
        if (!udp.sendSleepNotice(percent < 0 ? 255 : percent, charging, intervalMin, mV, flags, nextCheckInS)) {
            log("Sleep notice: no PC to tell");
            return false;
        }
        return true;
    }

    /** F-056: the link's socket, for a last sleep notice on it opened anew; null when it is not open. */
    de.droiddrone.common.LinkSocket linkSocket() {
        Udp u = udp;
        return u == null ? null : u.linkSocket();
    }

    /** pc.50: the heading sensor off after the notice, unless a pilot or observers use it. */
    void headingAfterNotice() {
        if (!udp.headingInUse()) PhoneHeading.stop(this);
    }

    boolean powerHello(int nextCheckMin) {
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
        // pc.36: overwatching (and an event running), and why overwatch gave way to deep sleep by itself
        if (!udp.sendSleepHello(percent < 0 ? 255 : percent, charging, nextCheckMin, mV, Overwatch.helloFlags(), PowerMode.floorReason())) {
            log("Sleep check: no hello to the PC of the last link (no address, or it didn't go)");
            return false;
        }
        return true;
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
            phoneTelemetry.initialize(); // pc.50: the microphone as at the start - when something needs it
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
     * MainActivity (the sanctioned way to start an activity from the background). F-083: posted with the screen as
     * it is - a bright wake lock beside it lit the screen before Android looked, and with no lock screen that made it
     * a banner; the screen is lit only when the app is not up 2 s later (WakeNotice), for phones that hold the
     * full-screen intent back. The notification goes after 15 s; the activity stays.
     */
    private void wakeScreen() {
        try {
            Intent intent = MainActivity.wakeIntent(this); // the app's own WAKE: with its token
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
            WakeNotice.post(this, WAKE_NOTIFICATION_ID, wake, "woken", null);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) mainHandler.postDelayed(() -> manager.cancel(WAKE_NOTIFICATION_ID), 15000);
        } catch (Exception e) {
            log("Wake notification error: " + e);
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
                // pc.31: one uncaught exception ended java.util.Timer's thread for good - and with it the FC port's
                // reopening after an error and standby's end on arming
                try {
                    tick();
                } catch (Throwable e) {
                    log("Main timer error: " + e);
                }
            }

            private void tick() {
                if (!isRunning){
                    mainTimer.cancel();
                    return;
                }
                if (PowerMode.isAsleep()) { // pc.21: the socket and the port are closed on purpose; nothing to tend
                    isConnected = false;    // (pc.23: the timer is stopped in sleep; this is the odd tick in between)
                    return;
                }
                isConnected = udp.isConnected();
                int armed = fcArmedState(); // pc.31: every tick - FcArmedState remembers an FC seen armed
                if (armed == FcArmedState.ARMED) PowerMode.fcArmed(); // standby (or a deep sleep countdown) ends by itself
                if (armed == FcArmedState.DISARMED) udp.applyDeferredFcConfig(); // pc.31: held back while not disarmed
                // F-070b: an update's install that waits for a tap is taken back when the FC is not known disarmed
                RemoteUpdate.watch(getApplicationContext(), armed == FcArmedState.DISARMED);
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