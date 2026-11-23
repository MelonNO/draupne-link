package de.droiddrone.pc.setup.dfu;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fleet PRD H1 / H4: the STM32's USB DFU bootloader through Windows' own WinUSB driver (winusb.dll, by JNA). The
 * driver must be bound to the DFU device (0483:DF11) - Betaflight Configurator's driver tools, Zadig or STM32CubeProgrammer
 * install it; without it the device is listed but can't be opened, and the pilot is told so. Listing reads Windows'
 * device list only (SetupAPI): no device is opened. {@link #open} opens exactly the one id it is given.
 * <p>
 * Windows x64 only (as the app): WinUsb_ControlTransfer's 8-byte setup packet is passed by value, which the x64
 * calling convention passes as one 64-bit integer.
 */
public final class WinUsbDfu implements DfuDevice {
    public static final int VID_ST = 0x0483, PID_DFU = 0xDF11;
    static final String MATCH = String.format(Locale.ROOT, "VID_%04X&PID_%04X", VID_ST, PID_DFU);

    private static final int DIGCF_PRESENT = 0x2, DIGCF_ALLCLASSES = 0x4, DIGCF_DEVICEINTERFACE = 0x10;
    private static final int DICS_FLAG_GLOBAL = 1, DIREG_DEV = 1, KEY_READ = 0x20019;
    private static final int GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000, FILE_SHARE_RW = 3, OPEN_EXISTING = 3;
    private static final int FILE_FLAG_OVERLAPPED = 0x40000000, FILE_ATTRIBUTE_NORMAL = 0x80;
    private static final int PIPE_TRANSFER_TIMEOUT = 3;
    private static final long INVALID = -1;

    interface SetupApi extends Library {
        Pointer SetupDiGetClassDevsW(Pointer guid, WString enumerator, Pointer hwnd, int flags);

        boolean SetupDiEnumDeviceInfo(Pointer set, int index, Pointer devInfo);

        boolean SetupDiGetDeviceInstanceIdW(Pointer set, Pointer devInfo, char[] id, int size, IntByReference required);

        Pointer SetupDiOpenDevRegKey(Pointer set, Pointer devInfo, int scope, int profile, int keyType, int sam);

        boolean SetupDiEnumDeviceInterfaces(Pointer set, Pointer devInfo, Pointer guid, int index, Pointer ifData);

        boolean SetupDiGetDeviceInterfaceDetailW(Pointer set, Pointer ifData, Pointer detail, int size, IntByReference required, Pointer devInfo);

        boolean SetupDiDestroyDeviceInfoList(Pointer set);
    }

    interface Advapi extends Library {
        int RegQueryValueExW(Pointer key, WString name, Pointer reserved, IntByReference type, byte[] data, IntByReference size);

        int RegCloseKey(Pointer key);
    }

    interface Kernel extends Library {
        Pointer CreateFileW(WString name, int access, int share, Pointer security, int disposition, int flags, Pointer template);

        boolean CloseHandle(Pointer h);

        int GetLastError();
    }

    interface WinUsb extends Library {
        boolean WinUsb_Initialize(Pointer file, PointerByReference out);

        boolean WinUsb_Free(Pointer h);

        boolean WinUsb_ControlTransfer(Pointer h, long setup, byte[] buf, int len, IntByReference transferred, Pointer overlapped);

        boolean WinUsb_GetDescriptor(Pointer h, byte type, byte index, short lang, byte[] buf, int len, IntByReference transferred);

        boolean WinUsb_SetPipePolicy(Pointer h, byte pipe, int policy, int len, IntByReference value);
    }

    private static final class Api {
        static final SetupApi SETUP = Native.load("setupapi", SetupApi.class);
        static final Advapi ADVAPI = Native.load("advapi32", Advapi.class);
        static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
        static final WinUsb WINUSB = Native.load("winusb", WinUsb.class);
    }

    /** The real bus: Windows' device list. */
    public static final DfuDevice.Bus BUS = new DfuDevice.Bus() {
        @Override
        public List<String> present() throws IOException {
            return list();
        }

        @Override
        public DfuDevice open(String id) throws IOException {
            return WinUsbDfu.open(id);
        }
    };

    private final String id;
    private final Pointer file, usb;
    private final int transferSize;
    private String layout;

    private WinUsbDfu(String id, Pointer file, Pointer usb, int transferSize) {
        this.id = id;
        this.file = file;
        this.usb = usb;
        this.transferSize = transferSize;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static boolean invalid(Pointer p) {
        return p == null || Pointer.nativeValue(p) == INVALID;
    }

    /** The STM32 DFU devices present (their instance ids, "USB\VID_0483&PID_DF11\..."). Opens none. */
    public static List<String> list() throws IOException {
        List<String> out = new ArrayList<>();
        if (!windows()) return out;
        Pointer set = Api.SETUP.SetupDiGetClassDevsW(null, new WString("USB"), null, DIGCF_PRESENT | DIGCF_ALLCLASSES);
        if (invalid(set)) throw new IOException("Windows' device list can't be read");
        try {
            Memory info = devInfo();
            for (int i = 0; Api.SETUP.SetupDiEnumDeviceInfo(set, i, info); i++) {
                String id = instanceId(set, info);
                if (id != null && id.toUpperCase(Locale.ROOT).contains(MATCH)) out.add(id);
            }
        } finally {
            Api.SETUP.SetupDiDestroyDeviceInfoList(set);
        }
        return out;
    }

    private static Memory devInfo() {
        Memory m = new Memory(32); // SP_DEVINFO_DATA on x64: cbSize, ClassGuid, DevInst, Reserved
        m.clear();
        m.setInt(0, 32);
        return m;
    }

    private static String instanceId(Pointer set, Pointer info) {
        char[] buf = new char[512];
        if (!Api.SETUP.SetupDiGetDeviceInstanceIdW(set, info, buf, buf.length, null)) return null;
        return Native.toString(buf);
    }

    /** This device (by its instance id) and no other: its WinUSB interface opened, its layout and transfer size read. */
    public static WinUsbDfu open(String id) throws IOException {
        if (!windows()) throw new IOException("USB DFU is built for Windows only");
        String path = interfacePath(id);
        Pointer file = Api.KERNEL.CreateFileW(new WString(path), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_RW, null, OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED, null);
        if (invalid(file)) throw new IOException("the DFU device can't be opened (error " + Api.KERNEL.GetLastError() + ")");
        PointerByReference h = new PointerByReference();
        if (!Api.WINUSB.WinUsb_Initialize(file, h)) {
            Api.KERNEL.CloseHandle(file);
            throw new IOException("WinUSB won't take the DFU device: install its driver (Betaflight's driver tool or Zadig)");
        }
        Pointer usb = h.getValue();
        IntByReference timeout = new IntByReference(5000);
        Api.WINUSB.WinUsb_SetPipePolicy(usb, (byte) 0, PIPE_TRANSFER_TIMEOUT, 4, timeout);
        WinUsbDfu d = new WinUsbDfu(id, file, usb, transferSizeOf(usb));
        try {
            d.layout = d.readLayout();
        } catch (IOException e) {
            d.close();
            throw e;
        }
        return d;
    }

    /** The device's WinUSB interface path: its DeviceInterfaceGUIDs from the registry, then that interface of this device. */
    private static String interfacePath(String id) throws IOException {
        Pointer set = Api.SETUP.SetupDiGetClassDevsW(null, new WString("USB"), null, DIGCF_PRESENT | DIGCF_ALLCLASSES);
        if (invalid(set)) throw new IOException("Windows' device list can't be read");
        String guid = null;
        try {
            Memory info = devInfo();
            for (int i = 0; Api.SETUP.SetupDiEnumDeviceInfo(set, i, info); i++) {
                if (!id.equalsIgnoreCase(instanceId(set, info))) continue;
                Pointer key = Api.SETUP.SetupDiOpenDevRegKey(set, info, DICS_FLAG_GLOBAL, 0, DIREG_DEV, KEY_READ);
                if (invalid(key)) break;
                try {
                    guid = firstGuid(key, "DeviceInterfaceGUIDs");
                    if (guid == null) guid = firstGuid(key, "DeviceInterfaceGUID");
                } finally {
                    Api.ADVAPI.RegCloseKey(key);
                }
                break;
            }
        } finally {
            Api.SETUP.SetupDiDestroyDeviceInfoList(set);
        }
        if (guid == null) throw new IOException("the DFU device has no WinUSB driver: install it (Betaflight's driver tool or Zadig)");
        Memory g = guid(guid);
        Pointer ifs = Api.SETUP.SetupDiGetClassDevsW(g, null, null, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
        if (invalid(ifs)) throw new IOException("the DFU device's interface isn't listed");
        try {
            Memory ifData = new Memory(32); // SP_DEVICE_INTERFACE_DATA on x64
            for (int i = 0; ; i++) {
                ifData.clear();
                ifData.setInt(0, 32);
                if (!Api.SETUP.SetupDiEnumDeviceInterfaces(ifs, null, g, i, ifData)) break;
                IntByReference need = new IntByReference();
                Memory info = devInfo();
                Api.SETUP.SetupDiGetDeviceInterfaceDetailW(ifs, ifData, null, 0, need, null);
                if (need.getValue() <= 0) continue;
                Memory detail = new Memory(need.getValue());
                detail.clear();
                detail.setInt(0, 8); // SP_DEVICE_INTERFACE_DETAIL_DATA_W's cbSize on x64
                if (!Api.SETUP.SetupDiGetDeviceInterfaceDetailW(ifs, ifData, detail, need.getValue(), null, info)) continue;
                if (id.equalsIgnoreCase(instanceId(ifs, info))) return detail.getWideString(4);
            }
        } finally {
            Api.SETUP.SetupDiDestroyDeviceInfoList(ifs);
        }
        throw new IOException("the DFU device's WinUSB interface isn't there");
    }

    private static String firstGuid(Pointer key, String name) {
        IntByReference size = new IntByReference(0);
        IntByReference type = new IntByReference();
        if (Api.ADVAPI.RegQueryValueExW(key, new WString(name), null, type, null, size) != 0 || size.getValue() <= 0) return null;
        byte[] data = new byte[size.getValue()];
        if (Api.ADVAPI.RegQueryValueExW(key, new WString(name), null, type, data, size) != 0) return null;
        String s = new String(data, StandardCharsets.UTF_16LE);
        for (String part : s.split("\u0000")) if (part.trim().startsWith("{")) return part.trim();
        return null;
    }

    /** "{a5dcbf10-6530-11d2-901f-00c04fb951ed}" as Windows' 16-byte GUID. */
    static Memory guid(String s) throws IOException {
        String h = s.replaceAll("[{}\\-\\s]", "");
        if (h.length() != 32) throw new IOException("not a GUID: " + s);
        Memory m = new Memory(16);
        m.setInt(0, (int) Long.parseLong(h.substring(0, 8), 16));
        m.setShort(4, (short) Integer.parseInt(h.substring(8, 12), 16));
        m.setShort(6, (short) Integer.parseInt(h.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) m.setByte(8 + i, (byte) Integer.parseInt(h.substring(16 + 2 * i, 18 + 2 * i), 16));
        return m;
    }

    /** The DFU functional descriptor's wTransferSize in the configuration descriptor; ST's ROM's 2048 if not found. */
    private static int transferSizeOf(Pointer usb) {
        byte[] cfg = new byte[512];
        IntByReference n = new IntByReference();
        if (!Api.WINUSB.WinUsb_GetDescriptor(usb, (byte) 2, (byte) 0, (short) 0, cfg, cfg.length, n)) return 2048;
        return DfuDescriptors.transferSize(cfg, n.getValue(), 2048);
    }

    private String readLayout() throws IOException {
        byte[] cfg = new byte[512];
        IntByReference n = new IntByReference();
        if (!Api.WINUSB.WinUsb_GetDescriptor(usb, (byte) 2, (byte) 0, (short) 0, cfg, cfg.length, n)) {
            throw new IOException("the DFU device's descriptor can't be read");
        }
        int index = DfuDescriptors.altName(cfg, n.getValue(), 0);
        if (index <= 0) throw new IOException("the DFU device names no flash layout");
        byte[] str = new byte[256];
        IntByReference m = new IntByReference();
        if (!Api.WINUSB.WinUsb_GetDescriptor(usb, (byte) 3, (byte) index, (short) 0x0409, str, str.length, m)) {
            throw new IOException("the DFU device's flash layout can't be read");
        }
        return DfuDescriptors.string(str, m.getValue());
    }

    private static long setup(int requestType, int request, int value, int index, int length) {
        return (requestType & 0xffL) | (request & 0xffL) << 8 | (value & 0xffffL) << 16 | (index & 0xffffL) << 32 | (length & 0xffffL) << 48;
    }

    @Override
    public void out(int request, int value, byte[] data) throws IOException {
        IntByReference done = new IntByReference();
        byte[] buf = data.length == 0 ? null : data;
        if (!Api.WINUSB.WinUsb_ControlTransfer(usb, setup(0x21, request, value, 0, data.length), buf, data.length, done, null)) {
            throw new IOException("USB request " + request + " failed (error " + Api.KERNEL.GetLastError() + ")");
        }
    }

    @Override
    public byte[] in(int request, int value, int length) throws IOException {
        byte[] buf = new byte[length];
        IntByReference done = new IntByReference();
        if (!Api.WINUSB.WinUsb_ControlTransfer(usb, setup(0xA1, request, value, 0, length), buf, length, done, null)) {
            throw new IOException("USB request " + request + " failed (error " + Api.KERNEL.GetLastError() + ")");
        }
        return java.util.Arrays.copyOf(buf, done.getValue());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String layout() {
        return layout;
    }

    @Override
    public int transferSize() {
        return transferSize;
    }

    @Override
    public void close() {
        Api.WINUSB.WinUsb_Free(usb);
        Api.KERNEL.CloseHandle(file);
    }
}
