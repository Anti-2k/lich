package dev.lich.api.hook;

import dev.lich.loader.LichLoader;
import dev.lich.loader.LichMod;

import java.security.CodeSource;
import java.util.Optional;

/**
 * The hooks Lich can weave into the game for Lich mods (DOCS.md §5.2). A mod lists the ones it uses under
 * {@code "hooks"} in its {@code lich.mod.json}; Lich weaves exactly the hooks loaded mods list, so the game
 * runs untouched where nobody listens. Each hook fires the events documented on its constant.
 */
public final class Hooks {
    /** {@code ClientTickEvents}: the start and end of every client tick (20 a second). */
    public static final String CLIENT_TICK = "client.tick";
    /** {@code FrameEvents}: the start and end of every rendered frame. */
    public static final String RENDER_FRAME = "render.frame";
    /** {@code HudEvents}: before and after each part of the in-game HUD, and the F3 text. */
    public static final String RENDER_HUD = "render.hud";
    /** {@code WorldRenderEvents.LAST}: after the world is drawn, before the hand. */
    public static final String RENDER_WORLD = "render.world";
    /** {@code EntityRenderEvents}: around drawing a living entity, and after its name tag. */
    public static final String RENDER_ENTITY = "render.entity";
    /** {@code WorldRenderEvents.BLOCK_OUTLINE}: instead of the outline of the block the player looks at. */
    public static final String RENDER_BLOCK_OUTLINE = "render.blockOutline";
    /** {@code FogEvents}: fog density, distance fog and fog colour. */
    public static final String RENDER_FOG = "render.fog";
    /** {@code FovEvents}: the field of view the player's state (sprinting, Speed, ...) asks for. */
    public static final String RENDER_FOV = "render.fov";
    /** {@code TooltipEvents}: drawing hover tooltips, line by line. */
    public static final String RENDER_TOOLTIP = "render.tooltip";
    /** {@code ItemEvents.TOOLTIP}: the lines of an item's tooltip. */
    public static final String ITEM_TOOLTIP = "item.tooltip";
    /** {@code ScreenEvents}: screens being set up, their buttons, and their keyboard and mouse input. */
    public static final String SCREEN = "screen";
    /** {@code InputEvents.MOUSE}: mouse input in game (not in screens). */
    public static final String INPUT_MOUSE = "input.mouse";
    /** {@code ChatEvents.RECEIVE}: chat and action bar messages from the server, before they're shown. */
    public static final String CHAT_RECEIVE = "chat.receive";
    /** {@code SoundEvents.PLAY}: a sound about to play. */
    public static final String SOUND_PLAY = "sound.play";
    /** {@code PlayerEvents.ATTACK}: the player attacking an entity. Observe only: the attack happens regardless. */
    public static final String PLAYER_ATTACK = "player.attack";

    private Hooks() {
    }

    /** Whether some loaded mod listed {@code hook}, so Lich wove it into the game. */
    public static boolean isWoven(String hook) {
        return LichLoader.isHookEnabled(hook);
    }

    /**
     * Fails unless {@code hook} is woven: listening to an event of a hook nobody listed would silently never
     * fire. The message names the mod that asked, found from the caller's class.
     */
    public static void require(String hook, String event) {
        if (isWoven(hook)) {
            return;
        }
        String mod = caller().map(found -> found.id() + " (" + found.source().getFileName() + ")").orElse("a mod");
        throw new IllegalStateException(mod + " listens to " + event + ", but doesn't list the hook \"" + hook
                + "\" under \"hooks\" in its lich.mod.json, so Lich didn't weave it into the game");
    }

    /** The first mod class on the stack, outside Lich's API. */
    private static Optional<LichMod> caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> !type.getName().startsWith("dev.lich.api."))
                .map(type -> {
                    CodeSource source = type.getProtectionDomain().getCodeSource();
                    return source == null ? null : LichLoader.modOf(source.getLocation());
                })
                .filter(mod -> mod != null)
                .findFirst());
    }
}
