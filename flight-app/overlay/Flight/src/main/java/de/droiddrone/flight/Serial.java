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
 *  Modified for DRAUPNE Link: one writer thread owns the USB port; stick packets
 *  (newest only) go first with a short timeout, never behind other requests, which back off while the link is slow;
 *  reads wait asynchronously, so they never hold up a write. The USB permission's answer is listened for (Deny
 *  shows, a replug asks again).
 *  pc.31: the port is opened and closed under one lock, each init thread (and its reader and writer) carries its
 *  generation, so two inits can't open the port at once and close each other's; requests time out after 20 ms (100
 *  before; sticks wait behind one in flight); the status request has a slot of its own that the slow-link backoff
 *  never skips.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.hoho.android.usbserial.driver.SerialTimeoutException;

import android_serialport_api.SerialPortFinder;
import de.droiddrone.common.FcInfo;
import de.droiddrone.common.FcCommon;
import tp.xmaihh.serialport.SerialHelper;
import tp.xmaihh.serialport.bean.ComBean;

public class Serial {
    public static final String ACTION_USB_PERMISSION = "de.droiddrone.flight.USB_PERMISSION";
    public static final int STATUS_NOT_INITIALIZED = 0;
    public static final int STATUS_DEVICE_NOT_CONNECTED = 1;
    public static final int STATUS_DEVICE_FOUND = 2;
    public static final int STATUS_USB_PERMISSION_REQUESTED = 3;
    public static final int STATUS_USB_PERMISSION_DENIED = 4;
    public static final int STATUS_USB_PERMISSION_GRANTED = 5;
    public static final int STATUS_SERIAL_PORT_ERROR = 6;
    public static final int STATUS_SERIAL_PORT_OPENED = 7;
    private static final int serialDataBits = 8;
    private static final int serialPortReadWriteTimeoutMs = 100;
    // DRAUPNE Link: stick writes give up quickly and go again with the newest sticks
    private static final int rcWriteTimeoutMs = 50;
    // pc.31: a request (telemetry, the channel map, the status) gives up after this: sticks wait behind one in
    // flight, and 100 ms behind a stuck request was close to Betaflight's RXLOSS (requests are asked again anyway)
    private static final int requestWriteTimeoutMs = 20;
    // a write slower than this means the flight controller's USB is busy: skip other requests for a moment
    private static final int slowWriteMs = 15;
    private static final int slowBackoffMs = 250;
    private static final int serialMaxBufferSize = 2048;
    // native serial
    private static final int nativeSerialParityNone = 0;
    private static final int nativeSerialParityOdd = 1;
    private static final int nativeSerialParityEven = 2;
    private static final int nativeSerialParitySpace = 3;
    private static final int nativeSerialParityMark = 4;
    private static final int nativeSerialFlowControlNone = 0;
    private static final int nativeSerialFlowControlRtsCts = 1;
    private static final int nativeSerialFlowControlXonXoff = 2;
    private final Context context;
    private final Config config;
    private final UsbManager manager;
    private Msp msp;
    private Mavlink mavlink;
    // pc.31: volatile, and opened / closed only under portLock - an init thread, the reader (a lost device), the
    // service's timer and the power worker all start a new init, and two of them once opened the port at once
    private volatile UsbSerialPort port;
    private volatile UsbDeviceConnection connection;
    private final Object portLock = new Object();
    private UsbSerialDriver driver;
    // DRAUPNE Link: volatile - read by the reader, writer and init threads
    private volatile int threadsId;
    private volatile int status;
    private boolean isMavlink;
    private SerialHelper serialHelper;
    private boolean restart;
    /** DRAUPNE Link: the newest stick packet not written yet (older ones are simply replaced). */
    private final AtomicReference<byte[]> pendingRc = new AtomicReference<>();
    /** DRAUPNE Link: a request that goes after the sticks but before everything else, and never skipped (MSP_RX_MAP). */
    private final AtomicReference<byte[]> pendingUrgent = new AtomicReference<>();
    /** pc.31: the status request (the ARM flag): its own slot after the urgent one, never skipped either. */
    private final AtomicReference<byte[]> pendingStatus = new AtomicReference<>();
    /** DRAUPNE Link: every other MSP request, in order. */
    private final LinkedBlockingQueue<byte[]> pendingMsp = new LinkedBlockingQueue<>(64);
    /** DRAUPNE Link: when the USB permission was asked for; no answer in PERMISSION_WAIT_MS counts as Deny. */
    private volatile long permissionRequestedAt;
    private static final long PERMISSION_WAIT_MS = 10000;
    /** The USB devices seen at the last probe; a change while the permission was denied asks again. */
    private String lastDeviceList = "";
    private boolean receiverRegistered;
    /**
     * DRAUPNE Link: Android's answer to the USB permission pop-up. Upstream never listened: after Deny the status
     * stayed "requested" for ever, and a replug did nothing.
     */
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("USB permission " + (granted ? "granted" : "denied"));
            if (status != STATUS_USB_PERMISSION_REQUESTED) return;
            status = granted ? STATUS_USB_PERMISSION_GRANTED : STATUS_USB_PERMISSION_DENIED;
            if (granted) wakeInit();
        }
    };
    private final Object initSignal = new Object();
    private final Object writeSignal = new Object();
    private volatile long slowUntil;
    /**
     * DRAUPNE Link: until then, look for the flight controller every FAST_RETRY_MS, not every 2 s - right after its USB
     * link went (a loose OTG plug): Betaflight lands or disarms after 1.5 s without sticks.
     */
    private volatile long fastRetryUntil;
    private static final int FAST_RETRY_MS = 150;
    private static final long FAST_RETRY_FOR_MS = 5000;

    public Serial(Context context, Config config){
        this.context = context;
        this.config = config;
        threadsId = 0;
        msp = null;
        mavlink = null;
        manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        status = STATUS_NOT_INITIALIZED;
    }

    public void initialize(Msp msp, Mavlink mavlink) {
        final int id;
        synchronized (portLock) {
            this.msp = msp;
            this.mavlink = mavlink;
            isMavlink = false;
            msp.close();
            mavlink.close();
            id = ++threadsId;
            closePort(); // DRAUPNE Link: the old connection, or each re-init leaked one (and its reader stayed blocked)
        }
        registerPermissionReceiver();
        // pc.31: the init thread, its reader and its writer carry this generation, not threadsId read when they start
        Thread initThread = new Thread(() -> initLoop(id));
        initThread.setDaemon(false);
        initThread.setName("serialInitThread");
        initThread.start();
    }

    public int getStatus(){
        return status;
    }

    private synchronized void registerPermissionReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(permissionReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            log("USB permission receiver error: " + e);
        }
    }

    private synchronized void unregisterPermissionReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            context.unregisterReceiver(permissionReceiver);
        } catch (Exception ignored) {
        }
    }

    /** The init loop's next try now (the permission was granted, or the flight controller plugged in). */
    public void wakeInit() {
        synchronized (initSignal) {
            initSignal.notifyAll();
        }
    }

    /**
     * DRAUPNE Link: the USB devices there now, as one string, so a change (the flight controller plugged in again,
     * or another device) can be told from a probe that found the same.
     */
    private String deviceListNow() {
        StringBuilder sb = new StringBuilder();
        for (String name : manager.getDeviceList().keySet()) sb.append(name).append(' ');
        return sb.toString();
    }

    /**
     * DRAUPNE Link: after Deny (or no answer to the pop-up in 10 s) the permission is asked for again only when the
     * flight controller is plugged in again (or the USB devices change), never every 2 s - a pop-up every 2 s would be
     * worse than none. The device going lets the probe start over.
     */
    private void checkPermissionState() {
        if (status == STATUS_USB_PERMISSION_REQUESTED && android.os.SystemClock.elapsedRealtime() - permissionRequestedAt > PERMISSION_WAIT_MS) {
            log("USB permission: no answer in " + PERMISSION_WAIT_MS / 1000 + " s, counted as denied");
            status = STATUS_USB_PERMISSION_DENIED;
        }
        if (status == STATUS_USB_PERMISSION_DENIED || status == STATUS_USB_PERMISSION_REQUESTED) {
            String devices = deviceListNow();
            if (!deviceAttached() || !devices.equals(lastDeviceList)) {
                log("USB devices changed: looking for the flight controller again");
                driver = null;
                status = STATUS_DEVICE_NOT_CONNECTED;
            }
        }
    }

    private void initLoop(final int id) {
        log("Start serial init thread - OK");
        if (id == threadsId) status = STATUS_DEVICE_NOT_CONNECTED;
        while (id == threadsId) {
            try {
                restart = false;
                if (config.isUseNativeSerialPort()){
                    if (openNativeSerialPort(id)) {
                        log("Native serial port is opened");
                        return;
                    }
                }else {
                    checkPermissionState();
                    if (findDevice()) {
                        if (checkPermission()) {
                            if (openUsbSerialPort(id)) {
                                log("USB Serial port is opened");
                                return;
                            }
                        }
                    }
                }
                synchronized (initSignal) {
                    if (id != threadsId) return;
                    initSignal.wait(android.os.SystemClock.elapsedRealtime() < fastRetryUntil ? FAST_RETRY_MS : 2000);
                }
            } catch (Exception e) {
                log("Serial init thread error: " + e);
                try {
                    Thread.sleep(android.os.SystemClock.elapsedRealtime() < fastRetryUntil ? FAST_RETRY_MS : 2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private boolean findDevice(){
        if (status != STATUS_DEVICE_NOT_CONNECTED && driver != null) return true;
        lastDeviceList = deviceListNow();
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            status = STATUS_DEVICE_NOT_CONNECTED;
            return false;
        }
        int count = availableDrivers.size();
        for (int i = 0; i < count; i++) {
            String manName = availableDrivers.get(i).getDevice().getManufacturerName();
            String prodName = availableDrivers.get(i).getDevice().getProductName();
            if (count == 1 || FcInfo.INAV_ID.equals(manName) || FcInfo.BETAFLIGHT_ID.equals(manName) || FcInfo.BETAFLIGHT_NAME.equals(manName)
                    || FcInfo.ARDUPILOT_ID.equals(manName) || FcInfo.ARDUPILOT_NAME.equals(manName)
                    || prodName != null && prodName.contains(FcInfo.PX4_NAME)) {
                driver = availableDrivers.get(i);
                status = STATUS_DEVICE_FOUND;
                setFcProtocol(manName, prodName);
                log("Serial device product name: " + prodName);
                log("Serial device manufacturer name: " + manName);
                log("Serial device driver version: " + driver.getDevice().getVersion());
                log("Serial device driver vendorId: " + driver.getDevice().getVendorId());
                log("Serial device driver productId: " + driver.getDevice().getProductId());
                return true;
            }
        }
        status = STATUS_DEVICE_NOT_CONNECTED;
        return false;
    }

    private void setFcProtocol(String usbDeviceManufacturerName, String usbDeviceProductName){
        switch (config.getFcProtocol()){
            case FcCommon.FC_PROTOCOL_AUTO:
            default:
                isMavlink = FcInfo.ARDUPILOT_ID.equals(usbDeviceManufacturerName)
                        || FcInfo.ARDUPILOT_NAME.equals(usbDeviceManufacturerName)
                        || usbDeviceProductName != null && usbDeviceProductName.contains(FcInfo.PX4_NAME);
                break;
            case FcCommon.FC_PROTOCOL_MSP:
                isMavlink = false;
                break;
            case FcCommon.FC_PROTOCOL_MAVLINK:
                isMavlink = true;
                break;
        }
    }

    public void restart(){
        status = STATUS_SERIAL_PORT_ERROR;
        restart = true;
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private boolean checkPermission() {
        if (status == STATUS_SERIAL_PORT_OPENED || status == STATUS_USB_PERMISSION_GRANTED) return true;
        if (manager.hasPermission(driver.getDevice())){
            status = STATUS_USB_PERMISSION_GRANTED;
            return true;
        }
        // DRAUPNE Link: denied stays denied until the device is plugged in again (checkPermissionState)
        if (status == STATUS_USB_PERMISSION_REQUESTED || status == STATUS_USB_PERMISSION_DENIED) return false;
        PendingIntent pendingIntent;
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(context.getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        }else{
            pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        status = STATUS_USB_PERMISSION_REQUESTED;
        permissionRequestedAt = android.os.SystemClock.elapsedRealtime();
        manager.requestPermission(driver.getDevice(), pendingIntent);
        return false;
    }

    private boolean openNativeSerialPort(int id){
        synchronized (portLock) {
            if (id != threadsId) return false; // a newer init took over: it opens the port, this one stops
            return openNativeSerialPortLocked(id);
        }
    }

    private boolean openNativeSerialPortLocked(int id){
        if (status == STATUS_SERIAL_PORT_OPENED) return true;
        setFcProtocol(null, null);
        if (serialHelper != null) serialHelper.close();
        try {
            SerialPortFinder serialPortFinder = new SerialPortFinder();
            String[] ports = serialPortFinder.getAllDevicesPath();
            if (ports != null) {
                for (String port : ports) {
                    log("Native serial port found: " + port);
                }
            }
            serialHelper = new SerialHelper(config.getNativeSerialPort(), config.getSerialBaudRate()) {
                @Override
                protected void onDataReceived(ComBean comBean) {
                    try {
                        byte[] buf = comBean.bRec;
                        int size = buf.length;
                        if (restart) return;
                        if (size > 0){
                            status = STATUS_SERIAL_PORT_OPENED;
                            if (isMavlink){
                                mavlink.addData(buf, size);
                            } else {
                                msp.addData(buf, size);
                            }
                        }
                    } catch (Exception e) {
                        log("Native serial reader error: " + e);
                    }
                }
            };
            serialHelper.setStopBits(1);
            serialHelper.setDataBits(serialDataBits);
            serialHelper.setParity(nativeSerialParityNone);
            serialHelper.setFlowCon(nativeSerialFlowControlNone);
            serialHelper.open();
            startWriter(id);
            if (isMavlink){
                if (!mavlink.isInitialized()) mavlink.initialize();
            } else {
                if (!msp.isInitialized()) msp.initialize();
            }
        }catch (Exception e){
            status = STATUS_SERIAL_PORT_ERROR;
            log("Native serial port error: " + e);
            return false;
        }
        status = STATUS_SERIAL_PORT_OPENED;
        return true;
    }

    private boolean openUsbSerialPort(int id){
        synchronized (portLock) {
            if (id != threadsId) return false; // a newer init took over: it opens the port, this one stops
            return openUsbSerialPortLocked(id);
        }
    }

    private boolean openUsbSerialPortLocked(final int id){
        if (status == STATUS_SERIAL_PORT_OPENED) return true;
        if (driver == null){
            status = STATUS_DEVICE_NOT_CONNECTED;
            return false;
        }
        UsbDevice device = driver.getDevice();
        if (device == null){
            status = STATUS_DEVICE_NOT_CONNECTED;
            return false;
        }
        connection = manager.openDevice(device);
        if (connection == null) {
            status = STATUS_USB_PERMISSION_DENIED;
            return false;
        }
        port = driver.getPorts().get(config.getUsbSerialPortIndex());
        try {
            port.open(connection);
            port.setParameters(config.getSerialBaudRate(), serialDataBits, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException | RuntimeException e) {
            status = STATUS_SERIAL_PORT_ERROR;
            log("USB Serial error: " + e);
            e.printStackTrace();
            closePort(); // DRAUPNE Link: the connection too, or every failed open leaked one
            return false;
        }
        final UsbSerialPort opened = port;
        Thread readerThread = new Thread(() -> readerLoop(id, opened));
        readerThread.setDaemon(false);
        readerThread.setName("readerThread");
        readerThread.setPriority(Thread.MAX_PRIORITY);
        readerThread.start();
        startWriter(id);
        if (isMavlink){
            if (!mavlink.isInitialized()) mavlink.initialize();
        } else {
            if (!msp.isInitialized()) msp.initialize();
        }
        return true;
    }

    /** pc.31: the port it reads is the one opened with it (the field may already hold a newer generation's). */
    private void readerLoop(final int id, final UsbSerialPort readPort) {
        int serialErrors = 0;
        byte[] buf = new byte[serialMaxBufferSize];
        if (id == threadsId) status = STATUS_SERIAL_PORT_OPENED;
        log("Start USB serial reader thread - OK");
        while (id == threadsId) {
            try {
                // DRAUPNE Link: timeout 0 = an asynchronous UsbRequest, waiting for data without blocking writes.
                // A read with a timeout is a synchronous bulkTransfer that, on many phones, holds every write on
                // this connection until data comes or the timeout (100 ms) runs out: sticks then reached the
                // flight controller ~100 ms late, enough for Betaflight to flag RXLOSS and block arming.
                int size = readPort.read(buf, 0);
                if (restart || id != threadsId) continue;
                if (size > 0){
                    serialErrors = 0;
                    status = STATUS_SERIAL_PORT_OPENED;
                    if (isMavlink){
                        mavlink.addData(buf, size);
                    } else {
                        msp.addData(buf, size);
                    }
                }
            } catch (Exception e) {
                if (id != threadsId) return; // closed on purpose
                serialErrors++;
                log("Serial reader thread error: " + e);
                // DRAUPNE Link: the device gone (unplugged), or reads keep failing: reconnect now, not after 10
                // errors (~1 s) plus the service's next check (up to 1 s) plus a 2 s retry
                if (!deviceAttached() || serialErrors > 3) {
                    lostDevice(id, e.toString());
                    return;
                }
                try {
                    Thread.sleep(serialPortReadWriteTimeoutMs);
                } catch (InterruptedException ex) {
                    //
                }
            }
        }
    }

    /** DRAUPNE Link: is the flight controller's USB device still there? */
    private boolean deviceAttached() {
        UsbSerialDriver d = driver;
        if (d == null || config.isUseNativeSerialPort()) return true;
        return manager.getDeviceList().containsValue(d.getDevice());
    }

    /** DRAUPNE Link: its USB link went: start again at once, and look for it often for a few seconds. */
    private void lostDevice(int id, String why) {
        if (id != threadsId) return; // pc.31: a newer init is already under way
        log("Flight controller USB link lost (" + why + "): reconnecting");
        fastRetryUntil = android.os.SystemClock.elapsedRealtime() + FAST_RETRY_FOR_MS;
        if (msp != null && mavlink != null) initialize(msp, mavlink);
    }

    public void writeDataMsp(byte[] data, boolean checkMspCompatibility){
        if (status != STATUS_SERIAL_PORT_OPENED || data == null) return;
        if (checkMspCompatibility && !isMspCompatible()) return;
        if (!pendingMsp.offer(data)) PcLinkStats.skipped();
        signalWriter();
    }

    /**
     * DRAUPNE Link: a request that must get through - the channel map, without which no sticks go out: right after
     * the sticks, before the other requests, and not skipped while the link is slow. One at a time (a newer one
     * replaces it).
     */
    public void writeDataMspUrgent(byte[] data){
        if (status != STATUS_SERIAL_PORT_OPENED || data == null) return;
        pendingUrgent.set(data);
        signalWriter();
    }

    /**
     * pc.31: the status request - how the app knows the FC is armed (FcArmedState). Its own slot after the urgent
     * one, and not skipped while the link is slow: the skips once froze the armed state at "disarmed".
     */
    public void writeDataMspStatus(byte[] data){
        if (status != STATUS_SERIAL_PORT_OPENED || data == null || !isMspCompatible()) return;
        pendingStatus.set(data);
        signalWriter();
    }

    /** DRAUPNE Link: a stick packet. Replaces one not written yet, and goes before every other request. */
    public void writeRcMsp(byte[] data){
        if (status != STATUS_SERIAL_PORT_OPENED || data == null || !isMspCompatible()) return;
        pendingRc.set(data);
        signalWriter();
    }

    private boolean isMspCompatible(){
        int level = FcCommon.getFcApiCompatibilityLevel(msp.getFcInfo());
        return level == FcCommon.FC_API_COMPATIBILITY_OK || level == FcCommon.FC_API_COMPATIBILITY_WARNING;
    }

    private void signalWriter(){
        synchronized (writeSignal) {
            writeSignal.notify();
        }
    }

    private void startWriter(final int id){
        pendingRc.set(null);
        pendingUrgent.set(null);
        pendingStatus.set(null);
        pendingMsp.clear();
        Thread writerThread = new Thread(() -> writerLoop(id));
        writerThread.setDaemon(true);
        writerThread.setName("serialWriterThread");
        writerThread.setPriority(Thread.MAX_PRIORITY);
        writerThread.start();
    }

    /**
     * DRAUPNE Link: the only thread that writes MSP to the flight controller, so a slow write can't hold sticks
     * behind other requests: the newest sticks always go first, other requests one at a time in between, and while
     * the USB link is slow those are skipped (the app asks for them again).
     */
    private void writerLoop(final int id) {
        while (id == threadsId) {
            try {
                byte[] rc = pendingRc.getAndSet(null);
                if (rc != null) {
                    write(rc, rcWriteTimeoutMs, true);
                    continue;
                }
                byte[] urgent = pendingUrgent.getAndSet(null);
                if (urgent != null) {
                    write(urgent, requestWriteTimeoutMs, false);
                    continue;
                }
                byte[] statusRequest = pendingStatus.getAndSet(null);
                if (statusRequest != null) {
                    write(statusRequest, requestWriteTimeoutMs, false);
                    continue;
                }
                byte[] other = pendingMsp.poll();
                if (other != null) {
                    if (android.os.SystemClock.elapsedRealtime() < slowUntil) {
                        PcLinkStats.skipped();
                    } else {
                        write(other, requestWriteTimeoutMs, false);
                    }
                    continue;
                }
                synchronized (writeSignal) {
                    if (pendingRc.get() == null && pendingUrgent.get() == null && pendingStatus.get() == null
                            && pendingMsp.isEmpty()) writeSignal.wait(20);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("Serial writer error: " + e);
            }
        }
    }

    private void write(byte[] data, int timeoutMs, boolean isRc){
        long start = System.nanoTime();
        boolean timedOut = false;
        boolean written = false; // DRAUPNE Link: only a write that went out counts as sticks delivered
        try {
            if (config.isUseNativeSerialPort()){
                if (serialHelper != null && serialHelper.isOpen()) {
                    serialHelper.send(data);
                    written = true;
                }
            }else{
                UsbSerialPort p = port; // pc.31: once - the field may be cleared by a close in between
                if (p != null && p.isOpen()) {
                    p.write(data, timeoutMs);
                    written = true;
                }
            }
        } catch (SerialTimeoutException e) {
            timedOut = true;
        } catch (IOException e) {
            log("Serial writeDataMsp error: " + e);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        PcLinkStats.write(ms, timedOut);
        if (ms > slowWriteMs) slowUntil = android.os.SystemClock.elapsedRealtime() + slowBackoffMs;
        if (isRc && written) PcLinkStats.rcOut();
    }

    public void writeDataMavlink(byte[] data){
        if (status != STATUS_SERIAL_PORT_OPENED || data == null) return;
        try {
            if (config.isUseNativeSerialPort()){
                if (serialHelper != null && serialHelper.isOpen()) serialHelper.send(data);
            }else{
                if (port != null && port.isOpen()) port.write(data, serialPortReadWriteTimeoutMs);
            }
        } catch (IOException e) {
            log("Serial writeDataMavlink error: " + e);
        }
    }

    public boolean isMavlink(){
        return isMavlink;
    }

    public void close(){
        synchronized (portLock) {
            threadsId++;
            signalWriter();
            wakeInit();
            unregisterPermissionReceiver();
            if (msp != null) msp.close();
            if (mavlink != null) mavlink.close();
            closePort();
            status = STATUS_NOT_INITIALIZED;
        }
    }

    /** The port and its USB connection (or the native serial port), if open. Under portLock. */
    private void closePort() {
        signalWriter();
        PcLinkStats.rcPortClosed();
        try {
            if (port != null) port.close();
        } catch (Exception e) {
            //
        }
        try {
            if (serialHelper != null) serialHelper.close();
        } catch (Exception e) {
            //
        }
        if (connection != null) connection.close();
        port = null;
        connection = null;
    }
}
