package de.droiddrone.pc;

import org.lwjgl.glfw.GLFWErrorCallback;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Joystick access through GLFW (DirectInput/XInput on Windows). All calls must come from the main thread.
 */
public final class Joystick {
    /** guid: GLFW's device identity (vendor and product), the same after a replug; null if unknown. */
    public record Info(int id, String name, String guid) {
        /** The same physical kind of device as other: same GUID and name (ids are reused for other devices). */
        public boolean sameDevice(Info other) {
            return other != null && java.util.Objects.equals(guid, other.guid) && java.util.Objects.equals(name, other.name);
        }
    }

    public record State(float[] axes, boolean[] buttons) {}

    private Joystick() {
    }

    public static boolean init() {
        GLFWErrorCallback.createPrint(System.err).set();
        return glfwInit();
    }

    public static void terminate() {
        glfwTerminate();
    }

    /** Lets GLFW process device connect/disconnect events. */
    public static void poll() {
        glfwPollEvents();
    }

    public static List<Info> list() {
        List<Info> found = new ArrayList<>();
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; jid++) {
            if (glfwJoystickPresent(jid)) found.add(new Info(jid, glfwGetJoystickName(jid), glfwGetJoystickGUID(jid)));
        }
        return found;
    }

    /** @return the first joystick whose name contains filter (case-insensitive), or null */
    public static Info find(String filter) {
        String f = filter == null ? "" : filter.toLowerCase();
        for (Info info : list()) {
            if (info.name() != null && info.name().toLowerCase().contains(f)) return info;
        }
        return null;
    }

    /** @return current axes/buttons, or null if the joystick is gone */
    /**
     * This physical device's state wherever it is now (GLFW reuses ids for other devices); null when it is gone -
     * never another device's.
     */
    public static State readSame(Info device) {
        int jid = device.id();
        if (glfwJoystickPresent(jid) && device.sameDevice(new Info(jid, glfwGetJoystickName(jid), glfwGetJoystickGUID(jid)))) {
            return read(jid);
        }
        for (Info info : list()) if (info.sameDevice(device)) return read(info.id());
        return null;
    }

    public static State read(int jid) {
        FloatBuffer axesBuf = glfwGetJoystickAxes(jid);
        ByteBuffer buttonsBuf = glfwGetJoystickButtons(jid);
        if (axesBuf == null || buttonsBuf == null) return null;
        float[] axes = new float[axesBuf.remaining()];
        axesBuf.get(axes);
        boolean[] buttons = new boolean[buttonsBuf.remaining()];
        for (int i = 0; i < buttons.length; i++) buttons[i] = buttonsBuf.get(i) == GLFW_PRESS;
        return new State(axes, buttons);
    }
}
