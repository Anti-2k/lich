package dev.lich.compat.lwjgl2.display;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Mouse;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LWJGL 2's private position fields in {@code Mouse}, which Essential's {@code GlobalMouseOverride} sets by reflection
 * to move the position the game sees while it draws an overlay (found by the modpack harness, SkyBlock Enhanced).
 */
class MouseFieldsTest {
    @Test
    void essentialsReflectiveWritesReachTheGetters() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        int[] values = {11, 22, 33, 44};
        String[] names = {"x", "y", "event_x", "event_y"};
        for (int i = 0; i < names.length; i++) {
            Field field = Mouse.class.getDeclaredField(names[i]);
            assertEquals(int.class, field.getType(), names[i]);
            assertTrue(Modifier.isStatic(field.getModifiers()), names[i]);
            field.setAccessible(true);
            MethodHandle setter = lookup.unreflectSetter(field);
            setter.invokeExact(values[i]);
        }
        assertEquals(11, Mouse.getX());
        assertEquals(22, Mouse.getY());
        assertEquals(33, Mouse.getEventX());
        assertEquals(44, Mouse.getEventY());
    }

    /** Witching Gadgets takes {@code Mouse.class.getDeclaredFields()[17]} for {@code readBuffer}, as in LWJGL 2.9. */
    @Test
    void fieldsAreInLwjgl2sOrder() {
        Field[] fields = Mouse.class.getDeclaredFields();
        assertEquals("EVENT_SIZE", fields[0].getName());
        assertEquals("readBuffer", fields[17].getName());
        assertEquals("clipMouseCoordinatesToWindow", fields[34].getName());
    }

    /** TC4Tweak checks {@code readBuffer.hasRemaining()} for mouse events the game hasn't read yet. */
    @Test
    void readBufferHoldsThePendingEventsInLwjgl2sLayout() throws Exception {
        WindowState.update(0L, 200, 100, 200, 100);
        MouseInput.create();
        try {
            MouseInput.button(0, true);
            MouseInput.scroll(1);
            Field field = Mouse.class.getDeclaredField("readBuffer");
            field.setAccessible(true);
            java.nio.ByteBuffer buffer = (java.nio.ByteBuffer) field.get(null);
            MouseInput.writePending(buffer);
            assertEquals(2 * Mouse.EVENT_SIZE, buffer.remaining());
            assertEquals(0, buffer.get(0), "the first event's button");
            assertEquals(1, buffer.get(1), "pressed");
            assertEquals(120, buffer.getInt(Mouse.EVENT_SIZE + 10), "the second event's wheel");

            MouseInput.next();
            MouseInput.next();
            MouseInput.writePending(buffer);
            assertTrue(!buffer.hasRemaining(), "nothing left once the game read both");
        } finally {
            MouseInput.destroy();
        }
    }
}
