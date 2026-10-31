package dev.lich.multimod.v1_7_10;

import dev.lich.api.entrypoint.ClientEntrypoint;
import dev.lich.multimod.MultiMod;
import net.minecraft.client.Minecraft;

/** Minecraft 1.7.10's own entrypoint, from the jar's {@code versions} part; reads a field this version's access widener opened. */
public final class Client implements ClientEntrypoint {
    @Override
    public void onClientReady() {
        MultiMod.LOG.info("Lich multimod: 1.7.10 entrypoint ran (widened debugFPS: {})", Minecraft.debugFPS >= 0);
    }
}
