package dev.lich.multimod;

import dev.lich.api.Lich;
import dev.lich.api.entrypoint.ClientEntrypoint;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The shared entrypoint: the same class on both versions, calling a game method whose SRG name
 * ({@code func_71410_x}) is the same in both, which the build checks.
 */
public final class MultiMod implements ClientEntrypoint {
    public static final Logger LOG = LogManager.getLogger("Lich multimod");

    @Override
    public void onClientReady() {
        LOG.info("Lich multimod: shared entrypoint ran on Minecraft {} (game instance: {})", Lich.minecraftVersion(),
                Minecraft.getMinecraft() != null);
    }
}
