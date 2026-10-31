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
 *  Modified for DRAUPNE Link: the Windows app's look (cards, status pill, status colours for a dark
 *  background), removing an earlier DroidDrone Flight, telling about a newer build, a touch lock for flying, and
 *  portrait as well as landscape, and Direct connection as the first-start connection mode.
 *  The connection logic is unchanged.
 */

package de.droiddrone.flight;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.animation.ObjectAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import static de.droiddrone.common.Logcat.log;

import java.util.Timer;
import java.util.TimerTask;

import de.droiddrone.common.FcInfo;
import de.droiddrone.common.FcCommon;
import de.droiddrone.common.SettingsCommon;

public class MainActivity extends Activity {
    public static String versionName;
    private MainActivity activity;
    private Button bStartStopService;
    private LinearLayout main;
    Spinner connectionMode;
    EditText etIp, etPort, etKey;
    private TextView tvNetworkStatus, tvFcStatus, tvConnectionModeHint, tvPill;
    private View llIp;
    private int colorOk, colorWarn, colorBad, colorMuted;
    CheckBox cbConnectOnStartup;
    private Timer uiTimer;
    private boolean isPaused = false;
    public static Config config;
    /** The original app's package (also DRAUPNE Link's builds up to 2.3.0-pc.1): removed so it can't take the FC. */
    private static final String OLD_PACKAGE = "de.droiddrone.flight";
    private TextView tvOldApp;
    private TextView tvUpdate;
    /** Once per app start: is there a newer build on GitHub? */
    private static boolean updateChecked;
    private boolean oldAppPrompted;
    /** The touch lock: the whole screen ignores touches but for its HOLD TO UNLOCK box. Null while unlocked. */
    private View touchLock;
    private ObjectAnimator unlockHold; // only the fill you see
    /**
     * Unlocks after a real 2 s hold. A timer, not the end of the fill's animation: with Android's animations switched
     * off (developer options, some battery savers) an animation ends at once, and a single tap would unlock.
     */
    private final android.os.Handler unlockTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable unlockAfterHold = this::unlockTouches;
    private static final long UNLOCK_HOLD_MS = 2000;
    private static final String LOCKED_STATE = "touchLocked";

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        try {
            PackageInfo packageInfo = this.getPackageManager().getPackageInfo(this.getPackageName(), 0);
            versionName = packageInfo.versionName;
        }catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        activity = this;
        main = findViewById(R.id.llMain);
        main.setKeepScreenOn(true);
        bStartStopService = findViewById(R.id.startStopService);
        bStartStopService.setOnClickListener(v -> {
            if (checkPermissions()) startStopService();
        });
        etIp = findViewById(R.id.editText_ip);
        etPort = findViewById(R.id.editText_port);
        etKey = findViewById(R.id.editText_key);
        tvNetworkStatus = findViewById(R.id.tvNetworkConnectionStatus);
        tvFcStatus = findViewById(R.id.tvFcConnectionStatus);
        tvConnectionModeHint = findViewById(R.id.tvConnectionModeHint);
        tvPill = findViewById(R.id.tvPill);
        tvOldApp = findViewById(R.id.tvOldApp);
        tvOldApp.setOnClickListener(v -> removeOldApp());
        tvUpdate = findViewById(R.id.tvUpdate);
        findViewById(R.id.lockScreen).setOnClickListener(v -> lockTouches());
        if (savedInstanceState != null && savedInstanceState.getBoolean(LOCKED_STATE)) lockTouches();
        if (!updateChecked) {
            updateChecked = true;
            String current = versionName;
            Thread t = new Thread(() -> {
                UpdateCheck.Found found = UpdateCheck.newerThan(current);
                if (found != null) runOnUiThread(() -> showUpdate(found));
            }, "updateCheck");
            t.setDaemon(true);
            t.start();
        }
        llIp = findViewById(R.id.llIp);
        colorOk = getColor(R.color.dd_ok);
        colorWarn = getColor(R.color.dd_warn);
        colorBad = getColor(R.color.dd_bad);
        colorMuted = getColor(R.color.dd_muted);
        float density = getResources().getDisplayMetrics().density;
        for (int id : new int[]{R.id.cardStatus, R.id.cardConnection}) {
            findViewById(id).setBackground(new BracketDrawable(getColor(R.color.dd_panel), getColor(R.color.dd_line),
                    getColor(R.color.dd_accent), density));
        }
        fitOrientation(getResources().getConfiguration());
        connectionMode = findViewById(R.id.connectionMode);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.connection_modes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        connectionMode.setAdapter(adapter);
        connectionMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position){
                    case SettingsCommon.ConnectionMode.overServer:
                        llIp.setVisibility(View.VISIBLE);
                        tvConnectionModeHint.setText(R.string.connection_mode_server);
                        break;
                    case SettingsCommon.ConnectionMode.direct:
                        llIp.setVisibility(View.GONE);
                        tvConnectionModeHint.setText(R.string.connection_mode_vpn);
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
        defaultToDirect();
        config = new Config(activity, SettingsCommon.versionCompatibleCode);
        etIp.setText(config.getIp());
        etPort.setText(String.valueOf(config.getPort()));
        etKey.setText(config.getKey());
        connectionMode.setSelection(config.getConnectionMode());
        cbConnectOnStartup = findViewById(R.id.cbConnectOnStartup);
        cbConnectOnStartup.setChecked(config.isConnectOnStartup());
        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText(getResources().getString(R.string.version, MainActivity.versionName));
        if (config.isConnectOnStartup() && !DDService.isRunning){
            // DRAUPNE Link: on the UI thread (it updates the screen), and only with the permissions the service needs
            // - without them its camera / microphone start fails on Android 14
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !DDService.isRunning && checkPermissions()) startStopService();
            }, 3000);
        }
    }

    private void setPill(int text, int background, int foreground){
        tvPill.setText(text);
        tvPill.setBackgroundColor(background);
        tvPill.setTextColor(foreground);
    }

    private void updateUi(){
        if (DDService.isRunning){
            bStartStopService.setText(getResources().getString(R.string.disconnect));
            bStartStopService.setBackgroundResource(R.drawable.dd_button_stop);
            if (DDService.isConnected){
                tvNetworkStatus.setText(getResources().getString(R.string.status_connected));
                tvNetworkStatus.setTextColor(colorOk);
                setPill(R.string.dd_pill_linked, colorOk, Color.WHITE);
            }else{
                setPill(R.string.dd_pill_waiting, getColor(R.color.dd_panel_2), colorWarn);
                switch (config.getConnectionMode()){
                    case SettingsCommon.ConnectionMode.overServer:
                        tvNetworkStatus.setText(getResources().getString(R.string.status_connecting));
                        break;
                    case SettingsCommon.ConnectionMode.direct:
                        tvNetworkStatus.setText(getResources().getString(R.string.status_awaiting_connection));
                        break;
                }
                tvNetworkStatus.setTextColor(colorWarn);
            }
            int serialPortStatus = DDService.getSerialPortStatus();
            switch (serialPortStatus){
                case Serial.STATUS_NOT_INITIALIZED:
                case Serial.STATUS_DEVICE_NOT_CONNECTED:
                    tvFcStatus.setText(getResources().getString(R.string.status_disconnected));
                    tvFcStatus.setTextColor(colorBad);
                    break;
                case Serial.STATUS_DEVICE_FOUND:
                    tvFcStatus.setText(getResources().getString(R.string.fc_status_device_found));
                    tvFcStatus.setTextColor(colorWarn);
                    break;
                case Serial.STATUS_USB_PERMISSION_REQUESTED:
                    tvFcStatus.setText(getResources().getString(R.string.fc_status_permission_requested));
                    tvFcStatus.setTextColor(colorWarn);
                    break;
                case Serial.STATUS_USB_PERMISSION_DENIED:
                    tvFcStatus.setText(getResources().getString(R.string.fc_status_permission_denied));
                    tvFcStatus.setTextColor(colorBad);
                    break;
                case Serial.STATUS_USB_PERMISSION_GRANTED:
                    tvFcStatus.setText(getResources().getString(R.string.fc_status_permission_granted));
                    tvFcStatus.setTextColor(colorWarn);
                    break;
                case Serial.STATUS_SERIAL_PORT_ERROR:
                    tvFcStatus.setText(getResources().getString(R.string.fc_status_serial_port_error));
                    tvFcStatus.setTextColor(colorBad);
                    break;
                case Serial.STATUS_SERIAL_PORT_OPENED:
                    tvFcStatus.setTextColor(colorWarn);
                    String status = getResources().getString(R.string.fc_status_serial_port_opened);
                    FcInfo fcInfo = DDService.getFcInfo();
                    if (fcInfo == null){
                        status += getResources().getString(R.string.check_fc_version);
                    }else{
                        tvFcStatus.setTextColor(colorOk);
                        status = fcInfo.getFcName() + " " + fcInfo.getFcVersionStr() + " · " + fcInfo.getPlatformTypeName();
                        int fcApiCompatibilityLevel = DDService.getFcApiCompatibilityLevel();
                        switch (fcApiCompatibilityLevel){
                            case FcCommon.FC_API_COMPATIBILITY_UNKNOWN:
                            case FcCommon.FC_API_COMPATIBILITY_ERROR:
                                status += getResources().getString(R.string.fc_api_compatibility_error);
                                tvFcStatus.setTextColor(colorBad);
                                break;
                            case FcCommon.FC_API_COMPATIBILITY_WARNING:
                                status += getResources().getString(R.string.fc_api_compatibility_warning);
                                tvFcStatus.setTextColor(colorWarn);
                                break;
                        }
                    }
                    tvFcStatus.setText(status);
                    break;
            }
            connectionMode.setEnabled(false);
            etIp.setEnabled(false);
            etPort.setEnabled(false);
            etKey.setEnabled(false);
            cbConnectOnStartup.setEnabled(false);
        }else{
            bStartStopService.setText(getResources().getString(R.string.connect));
            bStartStopService.setBackgroundResource(R.drawable.dd_button_primary);
            setPill(R.string.dd_pill_offline, getColor(R.color.dd_panel_2), colorMuted);
            tvNetworkStatus.setText(getResources().getString(R.string.status_disconnected));
            tvNetworkStatus.setTextColor(colorMuted);
            tvFcStatus.setText(getResources().getString(R.string.status_disconnected));
            tvFcStatus.setTextColor(colorMuted);
            connectionMode.setEnabled(true);
            etIp.setEnabled(true);
            etPort.setEnabled(true);
            etKey.setEnabled(true);
            cbConnectOnStartup.setEnabled(true);
        }
    }

    /**
     * An earlier DroidDrone Flight (other package name or signature) would also answer the flight controller's USB
     * and the network: ask Android to remove it - once by itself, then from the red line on the status card.
     */
    private void checkOldApp(){
        boolean installed;
        try {
            getPackageManager().getPackageInfo(OLD_PACKAGE, 0);
            installed = !OLD_PACKAGE.equals(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        tvOldApp.setVisibility(installed ? View.VISIBLE : View.GONE);
        if (installed && !oldAppPrompted) {
            oldAppPrompted = true;
            removeOldApp();
        }
    }

    /** A newer build is on GitHub: say so; a tap downloads it (Android installs it as an update). */
    private void showUpdate(UpdateCheck.Found found){
        tvUpdate.setText(getString(R.string.dd_update, found.version));
        tvUpdate.setVisibility(View.VISIBLE);
        tvUpdate.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(found.url)));
            } catch (Exception e) {
                log("Cannot open the download: " + e);
            }
        });
    }

    private void removeOldApp(){
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + OLD_PACKAGE)));
        } catch (Exception e) {
            log("Cannot open the uninstall prompt: " + e);
        }
    }

    private void startStopService(){
        Intent intent = new Intent(getApplicationContext(), DDService.class);
        if (DDService.isRunning){
            stopService(intent);
        }else{
            if (!config.updateConfig()) return;
            getApplicationContext().startForegroundService(intent);
        }
        updateUi();
    }

    private boolean checkPermissions(){
        boolean cameraPermission = isCameraPermissionGranted();
        boolean storagePermission = isStoragePermissionGranted();
        boolean audioPermission = isAudioPermissionGranted();
        boolean phoneStatePermission = isPhoneStatePermissionGranted();
        return cameraPermission && storagePermission && audioPermission && phoneStatePermission;
    }

    private boolean isCameraPermissionGranted(){
        if (this.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            activity.requestPermission(Manifest.permission.CAMERA, 0);
            return false;
        }
        return true;
    }

    private boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT <= 28) {
            if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, 1);
                return false;
            }
        }
        return true;
    }

    private boolean isAudioPermissionGranted(){
        if (this.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            activity.requestPermission(Manifest.permission.RECORD_AUDIO, 2);
            return false;
        }
        return true;
    }

    private boolean isPhoneStatePermissionGranted(){
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (this.checkSelfPermission(Manifest.permission.READ_BASIC_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermission(Manifest.permission.READ_BASIC_PHONE_STATE, 3);
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onResume(){
        super.onResume();
        checkOldApp();
        isPaused = false;
        main.setKeepScreenOn(true);
        updateUi();
        uiTimer = new Timer();
        TimerTask tt = new TimerTask() {
            @Override
            public void run() {
                if (isPaused){
                    uiTimer.cancel();
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateUi();
                        updateLockStatus();
                    }
                });
            }
        };
        uiTimer.schedule(tt, 10, 1000);
    }

    @Override
    protected void onPause(){
        super.onPause();
        isPaused = true;
        if (uiTimer != null) {
            uiTimer.cancel();
            uiTimer.purge();
        }
    }

    /**
     * Locks the screen for flying: everything is covered and ignores touches, except a small yellow box that unlocks
     * after being held for two seconds. The screen dims (the phone is the drone's radio: its battery matters) and
     * the system bars hide; Android doesn't let an app block the home gesture or the notification shade.
     */
    private void lockTouches() {
        if (touchLock != null) return;
        touchLock = getLayoutInflater().inflate(R.layout.dd_touch_lock, null);
        addContentView(touchLock, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        touchLock.setOnTouchListener((v, e) -> true); // every touch outside the box ends here
        View fill = touchLock.findViewById(R.id.unlockFill);
        touchLock.findViewById(R.id.unlockBox).setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            boolean outside = e.getX() < 0 || e.getY() < 0 || e.getX() > v.getWidth() || e.getY() > v.getHeight();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelUnlockHold(fill); // a hold already running (a second finger) never unlocks on its own
                unlockHold = ObjectAnimator.ofFloat(fill, "scaleX", 0f, 1f).setDuration(UNLOCK_HOLD_MS);
                unlockHold.start();
                unlockTimer.postDelayed(unlockAfterHold, UNLOCK_HOLD_MS);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN || (action == MotionEvent.ACTION_MOVE && outside)) {
                cancelUnlockHold(fill); // let go, another finger, or slid off the box: start again
            }
            return true;
        });
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED); // a banking drone doesn't turn the screen
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.05f;
        getWindow().setAttributes(lp);
        touchLock.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        updateLockStatus();
        log("Screen locked.");
    }

    private void cancelUnlockHold(View fill) {
        unlockTimer.removeCallbacks(unlockAfterHold);
        if (unlockHold != null) unlockHold.cancel();
        unlockHold = null;
        fill.setScaleX(0f);
    }

    private void unlockTouches() {
        unlockTimer.removeCallbacks(unlockAfterHold);
        if (touchLock == null) return;
        ((ViewGroup) touchLock.getParent()).removeView(touchLock);
        touchLock = null;
        unlockHold = null;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        main.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        log("Screen unlocked.");
    }

    /** What the locked screen still shows: the link and the flight controller, as the pill and status do. */
    private void updateLockStatus() {
        if (touchLock == null) return;
        TextView status = touchLock.findViewById(R.id.tvLockStatus);
        status.setText(tvPill.getText() + "   \u00b7   FC: " + tvFcStatus.getText());
        status.setTextColor(tvPill.getCurrentTextColor());
    }

    /**
     * DRAUPNE Link talks to the phone directly (over Tailscale), so that is the first-start choice, not upstream's
     * relay server. Written once, before Config reads it; a choice already saved stays.
     */
    private void defaultToDirect() {
        android.content.SharedPreferences preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.contains("connectionMode")) {
            preferences.edit().putInt("connectionMode", SettingsCommon.ConnectionMode.direct).apply();
        }
    }

    /** The screen turned: the activity stays (configChanges), only the cards are laid out again. */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        fitOrientation(newConfig);
    }

    /** Landscape: the two cards side by side. Portrait: stacked, the status card as tall as it needs, no subtitle. */
    private void fitOrientation(Configuration c) {
        boolean portrait = c.orientation == Configuration.ORIENTATION_PORTRAIT;
        int gap = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout cards = findViewById(R.id.llCards);
        cards.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        View status = findViewById(R.id.cardStatus);
        LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) status.getLayoutParams();
        sp.width = portrait ? LinearLayout.LayoutParams.MATCH_PARENT : 0;
        sp.height = portrait ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT;
        sp.weight = portrait ? 0 : 1;
        sp.setMarginEnd(portrait ? 0 : gap);
        sp.bottomMargin = portrait ? gap : 0;
        status.setLayoutParams(sp);
        // stacked, the status card's spacer would take every bit of height and leave none for the connection card
        findViewById(R.id.statusSpacer).setVisibility(portrait ? View.GONE : View.VISIBLE);
        View connection = findViewById(R.id.cardConnection);
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) connection.getLayoutParams();
        cp.width = portrait ? LinearLayout.LayoutParams.MATCH_PARENT : 0;
        cp.height = portrait ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
        cp.weight = portrait ? 1 : 1.35f;
        connection.setLayoutParams(cp);
        ((TextView) findViewById(R.id.tvSubtitle)).setText(portrait ? "" : getString(R.string.dd_subtitle));
    }

    /** Back does nothing while locked: it would leave the app. */
    @Override
    public void onBackPressed() {
        if (touchLock != null) return;
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(LOCKED_STATE, touchLock != null);
    }

    @Override
    protected void onStart(){
        super.onStart();
    }

    @Override
    protected void onDestroy(){
        unlockTimer.removeCallbacks(unlockAfterHold);
        super.onDestroy();
    }

    void requestPermission(String permissionName, int permissionRequestCode) {
        ActivityCompat.requestPermissions(this, new String[]{permissionName}, permissionRequestCode);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode >= 0 && requestCode <= 3 && checkPermissions()) startStopService();
        } else {
            if (requestCode == 0) log("Camera permission denied!");
            if (requestCode == 1) log("Storage permission denied!");
            if (requestCode == 2) log("Audio permission denied!");
            if (requestCode == 3) log("Phone state permission denied!");
        }
    }
}
