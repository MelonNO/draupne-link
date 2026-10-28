package de.droiddrone.pc;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.Kernel32;
import org.lwjgl.system.windows.WindowsLibrary;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToIntFunction;

import static de.droiddrone.common.Log.log;

/**
 * Process-wide Windows settings for steady timing, applied once at start. Each step is best effort: if one fails it
 * is logged and the app runs with Windows' defaults.
 * <ul>
 * <li>1 ms system timer for the whole run (timeBeginPeriod). HotSpot only raises the timer around each short sleep,
 * so sleep(1) took ~2 ms; with the timer held it takes ~1 ms and the RC loop ticks on time at 250 Hz.</li>
 * <li>Opt out of power throttling (EcoQoS). Windows 11 runs a process slower, and ignores its 1 ms timer, once its
 * window is minimised or covered; that would stretch the RC interval to 15.6 ms.</li>
 * <li>ABOVE_NORMAL priority class, so other programs can't hold up the RC and video threads.</li>
 * </ul>
 */
public final class WindowsTuning {
    private static final int PROCESS_POWER_THROTTLING = 4; // PROCESS_INFORMATION_CLASS.ProcessPowerThrottling
    private static final int THROTTLING_EXECUTION_SPEED = 0x1;
    private static final int THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4;
    private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x8000;

    private WindowsTuning() {
    }

    /** @return what was applied, for the log and the setup wizard */
    public static synchronized List<String> apply() {
        List<String> applied = new ArrayList<>();
        if (!System.getProperty("os.name", "").startsWith("Windows")) return applied;
        try {
            long process = Kernel32.GetCurrentProcess();
            SharedLibrary kernel32 = Kernel32.getLibrary();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // PROCESS_POWER_THROTTLING_STATE { Version = 1, ControlMask, StateMask = 0 (= never throttle) }
                ByteBuffer state = stack.calloc(4, 12);
                state.putInt(0, 1).putInt(4, THROTTLING_EXECUTION_SPEED | THROTTLING_IGNORE_TIMER_RESOLUTION).putInt(8, 0);
                long address = MemoryUtil.memAddress(state);
                if (call(kernel32, "SetProcessInformation", fn -> JNI.callPPI(process, PROCESS_POWER_THROTTLING, address, 12, fn))) {
                    applied.add("no power throttling");
                }
            }
            if (call(kernel32, "SetPriorityClass", fn -> JNI.callPI(process, ABOVE_NORMAL_PRIORITY_CLASS, fn))) {
                applied.add("above-normal priority");
            }
            // never freed: the 1 ms timer has to stay for the life of the process
            SharedLibrary winmm = new WindowsLibrary("winmm.dll");
            long timeBeginPeriod = winmm.getFunctionAddress("timeBeginPeriod");
            if (timeBeginPeriod != 0 && JNI.callI(1, timeBeginPeriod) == 0) applied.add("1 ms timer"); // TIMERR_NOERROR
        } catch (Throwable t) {
            log("Windows tuning: " + t);
        }
        log("Windows tuning: " + (applied.isEmpty() ? "none applied" : String.join(", ", applied)));
        return applied;
    }

    private static boolean call(SharedLibrary library, String function, LongToIntFunction invoke) {
        long fn = library.getFunctionAddress(function);
        if (fn == 0) return false; // older Windows without this function
        return invoke.applyAsInt(fn) != 0;
    }
}
