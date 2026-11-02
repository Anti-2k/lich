# Events and hooks

Lich's API is a set of typed **events** on a small bus. Each event belongs to a **hook**: a piece of Lich's own
Mixin code that is woven into the game only when a loaded mod asks for it. This page explains the bus, the hook
rule, and lists every event.

Package `dev.lich.api.client` holds the events. Sources are in `api/` (version-neutral events),
`versions/shared/api/` (events whose source is identical on 1.7.10 and 1.8.9, compiled against each) and
`versions/<mc>/api/` (events that differ per version).

## The bus

An event is a `dev.lich.api.event.Event<E>` field on a final class, `E` being the type of the object listeners get.

```java
ClientTickEvents.END.register(tick -> counter++);                       // normal priority
HudEvents.PRE.register(EventPriority.HIGH, render -> { ... });          // earlier than normal
ChatEvents.RECEIVE.registerReceivingCanceled(EventPriority.LOWEST, e -> { ... });
ClientTickEvents.END.unregister(myListener);                           // the same object you registered
```

| Method | Meaning |
|---|---|
| `register(listener)` | Normal priority |
| `register(priority, listener)` | `HIGHEST`, `HIGH`, `NORMAL`, `LOW`, `LOWEST` |
| `registerReceivingCanceled(priority, listener)` | Also called after another listener cancelled the event |
| `unregister(listener)` | Removes by identity (`==`). A lambda must be kept in a variable to be removed |
| `hasListeners()` | Whether anything listens. Game hooks check it before building the event |
| `fire(event)` | Calls the listeners and returns the event. Mods can fire their own events with it |

### Priorities and cancelling

Listeners run **highest priority first**; within a priority, in the order registered (and mods register in
[load order](lich-mod-json.md#resolution-and-load-order)).

An event whose class implements `Cancellable` (most extend `CancellableEvent`) can be stopped with
`event.cancel()` / `setCanceled(true)`. What a cancel *does* is documented per event below; it is never "cancel the
whole game action" unless said. After a cancel, only listeners registered with `registerReceivingCanceled` still see
the event; everyone else is skipped. `isCanceled()` tells them where it stands.

### Rules you can rely on

- **Thread.** Listeners run on the thread that fires the event, which for every event Lich fires is the client
  thread.
- **No reflection scanning.** There are no annotations to process; registering is one array copy. Firing reads an
  array.
- **Cost when unused.** A hook with no listeners costs one field read. A hook that is not woven costs nothing.
- **Exceptions propagate.** The bus does not catch exceptions from listeners; they unwind into the game code that
  fired the event. Catch inside your listener whatever you cannot afford to crash on.
- **Events carry game objects.** A `Render` event gives you a `ScaledResolution`, an entity event an
  `EntityLivingBase`. These are the game's own classes, in MCP names for you and SRG names at run time.
- **Mutable results.** Some events are one-way notifications (fields read-only), some carry setters (`setMessage`,
  `setNewFov`, `set(r, g, b)`) or mutable lists. Read each event below.
- **Your own events.** `Event.create(null, "MyMod.THING")` makes an event that always fires; pass a hook id as the
  first argument to tie it to a hook, as Lich's own do.

## Hooks

A hook has an **id**. Put the ids of the events you listen to in `lich.mod.json`:

```json
"hooks": ["client.tick", "render.hud", "chat.receive"]
```

Lich weaves exactly the union of what loaded mods list. Registering a listener on an event whose hook was not woven
throws at the `register` call:

```
IllegalStateException: hello (lich-hello-1.0.jar) listens to SoundEvents.PLAY, but doesn't list the hook
"sound.play" under "hooks" in its lich.mod.json, so Lich didn't weave it into the game
```

(The mod in the message is found from the calling class's jar.) Constants for the ids are in
`dev.lich.api.hook.Hooks`. `Hooks.isWoven(id)` tells you whether some loaded mod, perhaps another one, listed a hook.

How the check works: `register` fails when **no loaded mod** listed the hook. If some other mod listed it, the
hook is woven and your `register` would succeed even without listing it. Do not rely on that: list every hook you use
so your mod keeps working when the other mod is not installed.

The same events fire **with and without Forge**. Where Forge's own events already cover the same code, Lich's fire
after Forge's mods have had their turn, so listeners see what Forge mods left (noted below). Every hook is tested in
both profiles because Forge's binpatches change some of the same methods.

## Reference

Each section names the hook id to list, then the events. "1.7.10"/"1.8.9" notes call out differences. Everything
else is the same on both.

### Lifecycle (no hook needed)

Always available to every Lich mod.

| Event | Payload | Fires |
|---|---|---|
| `LifecycleEvents.SHUTDOWN` | `Shutdown.INSTANCE` | The game quits normally, before the window and sound system are released. Not on crash/forced exit |

### `client.tick`: `ClientTickEvents`

20 times a second, in and out of a world.

| Event | Fires |
|---|---|
| `START` | Before the tick: input, the screen, the world and the player haven't ticked yet |
| `END` | After the tick |

The payload carries nothing (`Tick.INSTANCE`). This is the game's tick, tied to the 20 TPS timer. See
[Server safety](server-safety.md) before doing anything that affects what the server sees.

### `render.frame`: `FrameEvents`

Every frame the game renders, in and out of a world. Skipped while the game shows nothing (window minimised).

| Event | Payload | Fires |
|---|---|---|
| `START` | `Frame(float partialTicks)` | Before anything of the frame is drawn |
| `END` | `Frame(float partialTicks)` | After the frame is drawn, before it is shown |

`partialTicks` is how far the frame is between the last tick and the next, 0 to 1.

### `render.hud`: `HudEvents`

The in-game HUD, the same with and without Forge.

| Event | Payload | Fires |
|---|---|---|
| `PRE` | `Render` (cancellable) | Before a HUD element is drawn. Cancelling skips that element; cancelling `HudElement.ALL` skips the whole HUD |
| `POST` | `Render` | After an element is drawn. Not after a cancelled one |
| `DEBUG_TEXT` | `DebugText(List<String> left, List<String> right)` | The F3 screen's text, while F3 is open |

`Render` has `element()`, `partialTicks()`, `resolution()` (`ScaledResolution`) and `top()`: the y of the row a bar is
drawn in for health and armour (left) and food, air and mount health (right), `-1` for elements that are not one of
those bars.

`HudElement` values: `ALL`, `HELMET`, `PORTAL`, `CROSSHAIRS`, `BOSSHEALTH`, `ARMOR`, `HEALTH`, `FOOD`, `AIR`,
`HOTBAR`, `EXPERIENCE`, `HEALTHMOUNT`, `JUMPBAR`, `CHAT`, `PLAYER_LIST`, `DEBUG`, `TITLE`, `ACTION_BAR`. These are
Forge's element types plus the title and the action bar.

- `TITLE` (the big title and subtitle): 1.8.9 only. 1.7.10 has no titles.
- `ACTION_BAR` (text above the hotbar): on 1.7.10 only the "Now playing" record text.
- `DEBUG_TEXT`: both lists are mutable, and what is in them after the listeners ran is drawn, one line per entry. Gaps
  are empty entries on 1.8.9 and `null` entries on 1.7.10 (as Forge 1.7.10 does).

```java
HudEvents.PRE.register(r -> {
    if (r.element() == HudElement.CROSSHAIRS) r.cancel();       // hide the crosshair
});
HudEvents.DEBUG_TEXT.register(d -> d.left().add("My line"));
```

### `render.world`, `render.blockOutline`: `WorldRenderEvents`

| Event | Hook | Payload | Fires |
|---|---|---|---|
| `LAST` | `render.world` | `Last(float partialTicks)` | After the world, entities, particles and weather are drawn, before the hand. The camera transform is set: drawing here is in world space relative to the camera |
| `BLOCK_OUTLINE` | `render.blockOutline` | `BlockOutline(player, target, partialTicks)` (cancellable) | The outline of the block the player looks at is about to be drawn. Cancelling skips the vanilla outline, so you can draw your own |

### `render.entity`: `EntityRenderEvents`

Around drawing a living entity (`RendererLivingEntity`). The payload `Living` is cancellable and gives
`entity()`, `renderer()`, and `x()`, `y()`, `z()`: where the entity is drawn, relative to the camera.

| Event | Fires |
|---|---|
| `PRE` | Before a living entity is drawn. Cancelling skips it, its name tag included |
| `POST` | After a living entity was drawn (not after a cancelled one) |
| `NAMETAG_POST` | After the entity's name tag was drawn, or would have been if it had one in view |

On 1.7.10 `renderer()` returns the raw `RendererLivingEntity`; on 1.8.9 it is `RendererLivingEntity<?>`. Code
that uses this event on both versions is built [per version](multi-version.md).

### `render.fog`: `FogEvents`

| Event | Payload | Fires |
|---|---|---|
| `DENSITY` | `Density`: `entity()`, `block()`, `partialTicks()`, `density()`, `setDensity(float)` | The exponential fog density the game is about to set (water, lava, clouds, or Forge's own density hook). Change it to change the fog |
| `DISTANCE` | `Distance(entity, block, partialTicks, fogMode, farPlaneDistance)` | The distance fog at the render distance edge was just set up (not in water, lava or blindness). Set your own start and end with `GlStateManager` |
| `COLOR` | `Color`: `red()`, `green()`, `blue()`, `set(r, g, b)` | The fog and sky colour of this frame, components 0 to 1 |

`fogMode` is `-1` for the sky's fog and `0` for terrain; `farPlaneDistance` is the render distance in blocks. `block()` is
the block at the camera, which decides the kind of fog.

### `render.fov`: `FovEvents`

| Event | Payload | Fires |
|---|---|---|
| `UPDATE` | `Update`: `player()`, `fov()`, `newFov()`, `setNewFov(float)` | The FOV multiplier the player's state asks for (sprinting, flying, Speed, a drawn bow), which the view then eases towards. `1` is the FOV from the options |

`fov()` is what the game worked out (with Forge, after Forge mods). Change `newFov` to change it.

### `render.tooltip`: `TooltipEvents`

Hover tooltips being drawn (items and others), with and without Forge.

| Event | Payload | Fires |
|---|---|---|
| `PRE` | `Pre(List<String> lines, int x, int y)` | A tooltip is about to be drawn at the mouse position |
| `LINE` | `Line` (cancellable): `text()`, `x()`, `y()` | One line is about to be drawn. Cancelling skips the line, so you can draw something else there. `x`, `y` is where the line's text starts |

### `item.tooltip`: `ItemEvents`

| Event | Payload | Fires |
|---|---|---|
| `TOOLTIP` | `Tooltip(ItemStack stack, EntityPlayer player, List<String> lines, boolean advanced)` | An item's tooltip lines were made (with Forge, after Forge mods'). The list may be changed. `advanced` is whether F3+H tooltips are on |

```java
ItemEvents.TOOLTIP.register(t -> t.lines().add("Damage: " + t.stack().getItemDamage()));
```

### `screen`: `ScreenEvents`

Screens being set up, their buttons, and the input they handle. All payloads carry `screen()` (`GuiScreen`).

| Event | Payload | Fires |
|---|---|---|
| `INIT_POST` | `Init(screen, List<GuiButton> buttons)` | After a screen set up its buttons (opened or resized). The list may be changed |
| `ACTION_PRE` | `Action` (cancellable): `button()` | A button was clicked, before the screen acts on it. Cancelling stops the action (the click sound has played) |
| `MOUSE_INPUT_PRE` / `_POST` | `Input` (cancellable on `PRE`) | Before / after the screen handles one mouse event. LWJGL's `Mouse` event is current. Cancelling in `PRE` skips it |
| `KEYBOARD_INPUT_PRE` / `_POST` | `Input` (cancellable on `PRE`) | The same for the keyboard; LWJGL's `Keyboard` event is current |

### `input.mouse`: `InputEvents`

| Event | Payload | Fires |
|---|---|---|
| `MOUSE` | `Mouse` (cancellable): `button()`, `pressed()`, `wheel()`, `dx()`, `dy()` | One mouse event **in game, not in screens**, before the game acts on it (attacking, using, scrolling the hotbar, key bindings). Cancelling makes the game skip it |

`button` is `-1` for movement and scrolling. `wheel` counts 120 per notch, as LWJGL 2 does. LWJGL's `Mouse` event is
current while the listeners run. Think hard before cancelling: see [Server safety](server-safety.md).

### `chat.receive`: `ChatEvents`

| Event | Payload | Fires |
|---|---|---|
| `RECEIVE` | `Receive` (cancellable): `actionBar()`, `message()`, `setMessage(IChatComponent)` | A chat or action bar message from the server, right before it is shown (with Forge, after Forge mods have had it) |

Set another message to show that instead; cancel to show nothing. It only changes what *this client shows*:
the server is unaffected. `actionBar()` is true for text that goes above the hotbar (packet type 2) and is always
`false` on 1.7.10.

### `sound.play`: `SoundEvents`

| Event | Payload | Fires |
|---|---|---|
| `PLAY` | `Play` (cancellable): `name()`, `sound()`, `setSound(ISound)` | A sound is about to play. Set another sound (to change its volume, say) or cancel it |

`name()` is the path of the sound's location, for example `ambient.weather.thunder`.

### `player.attack`: `PlayerEvents`

| Event | Payload | Fires |
|---|---|---|
| `ATTACK` | `Attack(EntityPlayer player, Entity target)` | The player attacks `target`: the attack has been sent to the server and the client is about to apply it locally. Not fired in spectator mode |

**Observe only.** Listeners cannot change or stop the attack. That is deliberate (DOCS.md §6).

## Outside the bus

### Key bindings: `KeyBindings`

```java
public final class MyKeys implements InitEntrypoint {
    public static final KeyBinding OPEN = new KeyBinding("My key", Keyboard.KEY_K, "My mod");

    @Override
    public void onInit() {
        KeyBindings.register(OPEN);
    }
}
```

`KeyBindings.register(KeyBinding)` adds the binding to Options → Controls and saves it in `options.txt` like the game's
own, applying the key the player last chose. Call it from `init` or later (the options must exist). Registering a
binding twice does nothing. Check it each tick with `OPEN.isPressed()` (or `isKeyDown()`) from a
`ClientTickEvents.END` listener. No hook is needed.

### Raw mouse input: `RawMouseInput`

`RawMouseInput.isSupported()`, `isEnabled()`, `setEnabled(boolean)`. Turns on the mouse's own counts instead of the
cursor's movement while the mouse is grabbed (no OS pointer speed or acceleration). Off by default. Only available on
Lich's LWJGL 3 (always, unless the game runs with `-Dlich.lwjgl=2`); otherwise `isSupported()` is false and the rest
does nothing. Client thread only. It changes how far a hand movement turns the camera, never anything the server
sees except the rotation the player picks.

### Facts about the game: `Lich`

See [`lich.mod.json` reference](lich-mod-json.md#reading-it-back-at-run-time).

## When the API doesn't have it

Use a [Mixin](mixins.md). Every time a first-party mod has to fall back to a Mixin for something general, the project
treats that as a sign a hook is missing, so tell the project what you needed.
