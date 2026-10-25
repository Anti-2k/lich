package dev.lich.api.client;

import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.util.List;

/** Items (hook {@code item.tooltip}). */
public final class ItemEvents {
    /** An item's tooltip lines were made (with Forge, after Forge mods'); the list may be changed. */
    public static final Event<Tooltip> TOOLTIP = Event.create(Hooks.ITEM_TOOLTIP, "ItemEvents.TOOLTIP");

    private ItemEvents() {
    }

    /** {@code advanced}: whether advanced tooltips (F3+H) are on. */
    public record Tooltip(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced) {
    }
}
