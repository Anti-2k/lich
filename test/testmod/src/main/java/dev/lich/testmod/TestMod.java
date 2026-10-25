package dev.lich.testmod;

import dev.lich.api.Lich;
import dev.lich.api.client.ChatEvents;
import dev.lich.api.client.ClientTickEvents;
import dev.lich.api.client.HudElement;
import dev.lich.api.client.HudEvents;
import dev.lich.api.client.KeyBindings;
import dev.lich.api.client.LifecycleEvents;
import dev.lich.api.client.ScreenEvents;
import dev.lich.api.entrypoint.ClientEntrypoint;
import dev.lich.api.entrypoint.InitEntrypoint;
import dev.lich.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.util.HashSet;
import java.util.Set;

/** Every entrypoint, and a listener per hook it asks for; each logs its first call. */
public final class TestMod implements PreLaunchEntrypoint, InitEntrypoint, ClientEntrypoint {
    private static final Logger LOG = LogManager.getLogger("Lich testmod");
    private static final Set<String> SEEN = new HashSet<>();

    private int ticks;

    static void once(String what) {
        if (SEEN.add(what)) {
            LOG.info("Lich testmod: {}", what);
        }
    }

    @Override
    public void onPreLaunch() {
        once("preLaunch entrypoint ran, Forge present: " + Lich.isForgePresent());
    }

    @Override
    public void onInit() {
        once("init entrypoint ran on Minecraft " + Lich.minecraftVersion() + ", mods " + Lich.mods());
        ClientTickEvents.END.register(tick -> {
            if (++ticks == 100) {
                once("100 client ticks");
            }
        });
        ScreenEvents.INIT_POST.register(init -> once("screen opened: " + init.screen().getClass().getSimpleName()
                + " with " + init.buttons().size() + " buttons"));
        ChatEvents.RECEIVE.register(receive -> once("chat received: " + receive.message().getUnformattedText()));
        HudEvents.POST.register(render -> {
            if (render.element() == HudElement.ALL) {
                Minecraft mc = Minecraft.getMinecraft();
                // The access widener made Minecraft.timer public.
                float partialTicks = mc.timer.renderPartialTicks;
                mc.fontRendererObj.drawStringWithShadow("Lich test mod", 2, 2, 0xFF55FF55);
                once("HUD drawn (partial ticks from the widened timer: " + (partialTicks >= 0) + ")");
            }
        });
        LifecycleEvents.SHUTDOWN.register(shutdown -> once("shutdown"));
    }

    @Override
    public void onClientReady() {
        KeyBindings.register(new KeyBinding("Lich test key", Keyboard.KEY_K, "Lich test mod"));
        once("client entrypoint ran; key binding registered");
        try {
            HudEvents.PRE.register(render -> {
            });
            once("registered for a declared hook");
            dev.lich.api.client.SoundEvents.PLAY.register(play -> {
            });
            once("ERROR: registered for an undeclared hook without an error");
        } catch (IllegalStateException expected) {
            once("undeclared hook refused: " + expected.getMessage());
        }
    }
}
