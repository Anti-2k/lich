package org.lwjgl.input;

import dev.lich.compat.lwjgl2.display.Glfw;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LWJGL 2's controllers (it used JInput), on GLFW's joysticks: the ones connected at {@link #create}, with
 * their buttons, axes and first hat as the point-of-view control. {@link #poll} compares each controller with
 * its last state and queues an event per change, as LWJGL 2 did. GLFW can't rumble, so there are no rumblers.
 */
public class Controllers {
    private static final List<GlfwController> CONTROLLERS = new ArrayList<>();
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static boolean created;
    private static Event current;

    private enum Kind { BUTTON, AXIS, POV_X, POV_Y }

    private record Event(GlfwController source, Kind kind, int index, long nanos) {
    }

    public Controllers() {
    }

    public static void create() throws LWJGLException {
        if (created) {
            return;
        }
        Glfw.init();
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid)) {
                CONTROLLERS.add(new GlfwController(jid, CONTROLLERS.size()));
            }
        }
        created = true;
    }

    public static Controller getController(int index) {
        return CONTROLLERS.get(index);
    }

    public static int getControllerCount() {
        return CONTROLLERS.size();
    }

    public static void poll() {
        for (GlfwController controller : CONTROLLERS) {
            controller.poll();
        }
    }

    public static void clearEvents() {
        EVENTS.clear();
    }

    public static boolean next() {
        current = EVENTS.poll();
        return current != null;
    }

    public static boolean isCreated() {
        return created;
    }

    public static void destroy() {
        CONTROLLERS.clear();
        EVENTS.clear();
        created = false;
    }

    public static Controller getEventSource() {
        return current == null ? null : current.source();
    }

    public static int getEventControlIndex() {
        return current == null ? 0 : current.index();
    }

    public static boolean isEventButton() {
        return current != null && current.kind() == Kind.BUTTON;
    }

    public static boolean isEventAxis() {
        return current != null && current.kind() == Kind.AXIS;
    }

    public static boolean isEventXAxis() {
        return isEventAxis() && current.index() == GlfwController.X;
    }

    public static boolean isEventYAxis() {
        return isEventAxis() && current.index() == GlfwController.Y;
    }

    public static boolean isEventPovX() {
        return current != null && current.kind() == Kind.POV_X;
    }

    public static boolean isEventPovY() {
        return current != null && current.kind() == Kind.POV_Y;
    }

    public static long getEventNanoseconds() {
        return current == null ? 0 : current.nanos();
    }

    public static boolean getEventButtonState() {
        return isEventButton() && current.source().isButtonPressed(current.index());
    }

    public static float getEventXAxisValue() {
        return current == null ? 0 : current.source().getXAxisValue();
    }

    public static float getEventYAxisValue() {
        return current == null ? 0 : current.source().getYAxisValue();
    }

    /** One GLFW joystick. Axis order is GLFW's (for XInput pads: left stick, right stick, triggers). */
    private static final class GlfwController implements Controller {
        static final int X = 0;
        static final int Y = 1;
        static final int RX = 2;
        static final int RY = 3;
        static final int Z = 4;
        static final int RZ = 5;
        private static final String[] AXIS_NAMES = {"X Axis", "Y Axis", "X Rotation", "Y Rotation", "Z Axis", "Z Rotation"};

        private final int jid;
        private final int index;
        private final String name;
        private float[] axes = new float[0];
        private float[] deadZones = new float[0];
        private boolean[] buttons = new boolean[0];
        private float povX;
        private float povY;

        GlfwController(int jid, int index) {
            this.jid = jid;
            this.index = index;
            String joystickName = GLFW.glfwGetJoystickName(jid);
            this.name = joystickName == null ? "Joystick " + jid : joystickName;
            poll();
            EVENTS.clear();
        }

        @Override
        public void poll() {
            long now = System.nanoTime();
            FloatBuffer axisValues = GLFW.glfwGetJoystickAxes(jid);
            int axisCount = axisValues == null ? 0 : axisValues.remaining();
            if (axes.length != axisCount) {
                axes = new float[axisCount];
                deadZones = Arrays.copyOf(deadZones, axisCount);
            }
            for (int i = 0; i < axisCount; i++) {
                float value = axisValues.get(axisValues.position() + i);
                if (Math.abs(value) < deadZones[i]) {
                    value = 0;
                }
                if (value != axes[i]) {
                    axes[i] = value;
                    EVENTS.add(new Event(this, Kind.AXIS, i, now));
                }
            }
            ByteBuffer buttonStates = GLFW.glfwGetJoystickButtons(jid);
            int buttonCount = buttonStates == null ? 0 : buttonStates.remaining();
            if (buttons.length != buttonCount) {
                buttons = new boolean[buttonCount];
            }
            for (int i = 0; i < buttonCount; i++) {
                boolean pressed = buttonStates.get(buttonStates.position() + i) == GLFW.GLFW_PRESS;
                if (pressed != buttons[i]) {
                    buttons[i] = pressed;
                    EVENTS.add(new Event(this, Kind.BUTTON, i, now));
                }
            }
            ByteBuffer hats = GLFW.glfwGetJoystickHats(jid);
            int hat = hats == null || !hats.hasRemaining() ? 0 : hats.get(hats.position());
            float newPovX = (hat & GLFW.GLFW_HAT_LEFT) != 0 ? -1 : (hat & GLFW.GLFW_HAT_RIGHT) != 0 ? 1 : 0;
            float newPovY = (hat & GLFW.GLFW_HAT_UP) != 0 ? -1 : (hat & GLFW.GLFW_HAT_DOWN) != 0 ? 1 : 0;
            if (newPovX != povX) {
                povX = newPovX;
                EVENTS.add(new Event(this, Kind.POV_X, 0, now));
            }
            if (newPovY != povY) {
                povY = newPovY;
                EVENTS.add(new Event(this, Kind.POV_Y, 0, now));
            }
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public int getButtonCount() {
            return buttons.length;
        }

        @Override
        public String getButtonName(int button) {
            return "Button " + button;
        }

        @Override
        public boolean isButtonPressed(int button) {
            return button >= 0 && button < buttons.length && buttons[button];
        }

        @Override
        public float getPovX() {
            return povX;
        }

        @Override
        public float getPovY() {
            return povY;
        }

        @Override
        public float getDeadZone(int axis) {
            return axis >= 0 && axis < deadZones.length ? deadZones[axis] : 0;
        }

        @Override
        public void setDeadZone(int axis, float zone) {
            if (axis >= 0 && axis < deadZones.length) {
                deadZones[axis] = zone;
            }
        }

        @Override
        public int getAxisCount() {
            return axes.length;
        }

        @Override
        public String getAxisName(int axis) {
            return axis < AXIS_NAMES.length ? AXIS_NAMES[axis] : "Axis " + axis;
        }

        @Override
        public float getAxisValue(int axis) {
            return axis >= 0 && axis < axes.length ? axes[axis] : 0;
        }

        @Override
        public float getXAxisValue() {
            return getAxisValue(X);
        }

        @Override
        public float getXAxisDeadZone() {
            return getDeadZone(X);
        }

        @Override
        public void setXAxisDeadZone(float zone) {
            setDeadZone(X, zone);
        }

        @Override
        public float getYAxisValue() {
            return getAxisValue(Y);
        }

        @Override
        public float getYAxisDeadZone() {
            return getDeadZone(Y);
        }

        @Override
        public void setYAxisDeadZone(float zone) {
            setDeadZone(Y, zone);
        }

        @Override
        public float getZAxisValue() {
            return getAxisValue(Z);
        }

        @Override
        public float getZAxisDeadZone() {
            return getDeadZone(Z);
        }

        @Override
        public void setZAxisDeadZone(float zone) {
            setDeadZone(Z, zone);
        }

        @Override
        public float getRXAxisValue() {
            return getAxisValue(RX);
        }

        @Override
        public float getRXAxisDeadZone() {
            return getDeadZone(RX);
        }

        @Override
        public void setRXAxisDeadZone(float zone) {
            setDeadZone(RX, zone);
        }

        @Override
        public float getRYAxisValue() {
            return getAxisValue(RY);
        }

        @Override
        public float getRYAxisDeadZone() {
            return getDeadZone(RY);
        }

        @Override
        public void setRYAxisDeadZone(float zone) {
            setDeadZone(RY, zone);
        }

        @Override
        public float getRZAxisValue() {
            return getAxisValue(RZ);
        }

        @Override
        public float getRZAxisDeadZone() {
            return getDeadZone(RZ);
        }

        @Override
        public void setRZAxisDeadZone(float zone) {
            setDeadZone(RZ, zone);
        }

        @Override
        public int getRumblerCount() {
            return 0;
        }

        @Override
        public String getRumblerName(int rumbler) {
            return null;
        }

        @Override
        public void setRumblerStrength(int rumbler, float strength) {
        }
    }
}
