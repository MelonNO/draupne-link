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
    public record Info(int id, String name) {}

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
            if (glfwJoystickPresent(jid)) found.add(new Info(jid, glfwGetJoystickName(jid)));
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
