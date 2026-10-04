# FoliaGUI

A packet-driven inventory GUI library built directly on **PacketEvents** — no
Bukkit `Inventory`, no `InventoryView`, no `InventoryClickEvent`.

---

## Why packets instead of Bukkit inventories

| | Bukkit GUI libs | FoliaGUI |
|---|---|---|
| Server-side container | Real `Inventory` allocated per menu | None — window exists only on the client |
| Click handling | `InventoryClickEvent`, cancellable by others | Raw packet, intercepted before the server sees it |
| Item safety | Items are real; a missed cancel = duplication | Display-only; the client cannot take what isn't there |
| `InventoryView` break | class → interface change causes `IncompatibleClassChangeError` | Type is never referenced |
| Hopper/plugin scraping | Possible | Impossible — nothing to scrape |

The `InventoryView` point is the practical one. That type changed from an
abstract **class** to an **interface**, so any GUI code compiled against an
older API dies at runtime with:

```
java.lang.IncompatibleClassChangeError:
  Found interface org.bukkit.inventory.InventoryView, but class was expected
```

FoliaGUI can't hit that, structurally — the compiled jar contains **zero**
references to `InventoryView`, `Inventory`, or `InventoryClickEvent`. Verified
by bytecode scan, and the jar links cleanly against **both** paper-api 1.20.6
and 26.2.

## Setup

PacketEvents must be initialised first — it needs `onLoad`.

```java
private FoliaGUI gui;

@Override
public void onLoad() {
    PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
    PacketEvents.getAPI().load();
}

@Override
public void onEnable() {
    PacketEvents.getAPI().init();
    gui = FoliaGUI.create(this);
}

@Override
public void onDisable() {
    gui.shutdown();
}
```

### Declare the protocol plugins as dependencies

PacketEvents' injector has to run *after* any plugin that manipulates the
network pipeline, and it detects several of them by calling `loadClass()` on
**your** plugin's classloader. Both halves are required. In a
`paper-plugin.yml` (here via the `plugin-yml` Gradle plugin):

```kotlin
listOf(
    "ProtocolLib",
    "ProtocolSupport",
    "ViaVersion",
    "ViaBackwards",
    "ViaRewind",
    "Geyser-Spigot",
).forEach { protocolPlugin ->
    register(protocolPlugin) {
        required = false
        load = PaperPluginDescription.RelativeLoadOrder.BEFORE
        joinClasspath = true
    }
}
```

On a legacy `plugin.yml` the equivalent is a `softdepend` list of the same six
names — there, load order and classpath access are one and the same setting.

`joinClasspath = true` is **not optional**, and Paper defaults it to true
anyway. If you bundle PacketEvents as a runtime library rather than installing
it as its own plugin, `PacketEvents.getAPI().getPlugin()` is *your* plugin, so
those `loadClass()` probes run against your classloader. Deny them and
`ViaVersionUtil` ends up taking availability from the Bukkit plugin manager
while building its accessor from the classloader; the two disagree the moment
ViaVersion is installed, and every client handshake throws an NPE inside
PacketEvents' own listener. ProtocolSupport and Geyser are probed the same way
and fail quietly instead — wrong client versions, Bedrock players unseen.

Build every menu from that instance:

```java
Menu menu = gui.menu(3, Component.text("My Menu"));
MerchantMenu trades = gui.merchant(Component.text("Trader"));
PaginatedMenu<Warp> warps = gui.paginated(5, Component.text("Warps"), () -> allWarps());
```

### No global lookup, by design

There is deliberately no `FoliaGUI.get()` or `FoliaGUI.of(plugin)`. Hold the
reference `create()` gives you and pass it around.

A static registry works fine until two plugins use the library. Each normally
**bundles and relocates** its own copy, so `com.a.libs.FoliaGUI` and
`com.b.libs.FoliaGUI` are unrelated classes with unrelated static state. A
global lookup would then resolve differently depending on which copy the caller
linked against, and a plugin reaching for another plugin's menus would get an
empty registry, a foreign instance, or a cross-classloader `ClassCastException`
— none with a useful message. Requiring an explicit reference makes that
category of bug impossible.

To expose your menus to other plugins, publish your own accessor
(`MyPlugin#gui()`); then the dependency is explicit and the classloader
question is yours to answer.

## Usage

```java
Menu menu = Menu.chest(3, Component.text("My Shop"));

menu.set(13, GuiItem.of(Material.DIAMOND,
        Component.text("Click me"),
        Component.text("Costs 10 coins"))
    .onClick(ctx -> {
        ctx.player().sendMessage("You clicked slot " + ctx.slot());
        if (ctx.isShift()) buyBulk(ctx.player());
        else buyOne(ctx.player());
        ctx.close();
    }));

menu.border(Material.GRAY_STAINED_GLASS_PANE);
menu.onClose(player -> save(player));
menu.open(player);
```

### Coordinates

`set(slot, item)` or `set(column, row, item)` — the latter is `row * columns + column`.

### Live updates

```java
menu.set(4, newItem);   // re-sends just that slot
menu.refresh();         // re-sends all slots, one packet
menu.title(Component.text("New title"));
```

Retitling re-opens the window with the same id (the protocol has no rename
packet) and re-sends contents immediately.

### The cursor

`ClickContext` exposes what the client is carrying, and lets you set it:

```java
menu.set(13, GuiItem.of(Material.PAPER).onClick(ctx -> {
    if (ctx.hasCursor()) {
        template = ctx.cursor().clone();   // read what they picked up
    } else {
        ctx.setCursor(template.clone());   // hand a copy back out
    }
}));
```

The server re-asserts the cursor after every click: if a handler calls
`setCursor()` that stack is sent, otherwise the cursor is cleared. Nothing is
consumed — the player's real inventory never moves.

> **The cursor is emulated.** A packet-only window has no server-side
> container, so the server ignores every click in it — including clicks in the
> player's own inventory, which means the *real* cursor never changes. FoliaGUI
> tracks its own: clicking an inventory slot picks that item up, clicking again
> puts it down, and the client is corrected to match. No items actually move,
> and the player's inventory is re-sent after each click.
>
> **`cursor()` is still not authoritative.** There is no server-side container to
> validate it against, so a modified client can claim to hold anything. It is
> safe for visual templates (picking which item an offer displays); never grant
> items, money or permissions based on it without checking the player's real
> inventory first.

### Delivering goods to the cursor

`addToCursor(stack)` hands a player something they've earned — a completed
purchase, say — by putting it in hand rather than into their bag:

```java
ItemStack leftover = menu.addToCursor(goods);   // null if it all fit
menu.refreshCursor();
```

It merges with a compatible held stack and returns whatever won't fit, so the
caller can fall back to the inventory. Such a cursor is flagged **owed**
(`isCursorOwed()`), which distinguishes it from the pick-up illusion: owed
goods are stowed for real when put down, and handed over if the menu closes or
the player quits. A mirrored item is just dropped, because it never left the
inventory.

### Input slots

Ordinary slots are display-only, but an editor often needs a real "drop the
item here" slot. Marking one makes it behave like a container slot — click with
a full cursor to deposit, click empty-handed to take it back:

```java
menu.inputSlots(20, 24);
menu.onInputChange(slot -> recompute());
ItemStack dropped = menu.inputItem(20);   // null when empty
```

`fill()` deliberately skips empty input slots — a filler pane there would read
as occupied and block the drop.

### Click types

`ClickContext` flattens the protocol's `(mode, button)` pair:

`LEFT` · `RIGHT` · `SHIFT_LEFT` · `SHIFT_RIGHT` · `MIDDLE` · `DROP` ·
`CONTROL_DROP` · `NUMBER_KEY` · `OFFHAND_SWAP` · `DOUBLE_CLICK` · `OTHER`

with `isLeft()`, `isRight()`, `isShift()`, `isDrop()` helpers.

### Player inventory

The bottom inventory is the player's, and stays **fully usable** while a menu is
open — those clicks reach the server normally. Only clicks inside the menu are
cancelled, because that window doesn't exist server-side.

Observe them without interfering:

```java
menu.onPlayerInventoryClick(ctx -> { /* ctx.slot() is the raw slot */ });
```

Or block them, for pickers where a stray move would confuse:

```java
menu.lockPlayerInventory(true);
```

## Folia correctness

Packet callbacks arrive on Netty threads, which own no region. Every handler is
dispatched onto the **player's entity scheduler** before it runs, so handlers
may safely touch the player, their inventory and nearby blocks:

```java
player.getScheduler().run(plugin, task -> { /* handler */ }, null);
```

## Merchant menus

The real villager trade screen, with rows supplied by the Merchant Offers
packet rather than by slot contents:

```java
MerchantMenu menu = new MerchantMenu(Component.text("Bob's Shop"));

menu.addOffer(new TradeOffer(
        new ItemStack(Material.EMERALD, 5),   // cost
        new ItemStack(Material.DIAMOND)));    // result

menu.addOffer(new TradeOffer(cost1, cost2, result));   // two-input trade
menu.addOffer(new TradeOffer(cost, result).disabled(true)); // greyed, red X

menu.villager(5, 120, true, true);   // level badge + xp bar
menu.onTrade((player, index) -> handleTrade(player, index));
menu.open(player);
```

### How the trade flow works

The window behaves like a real villager rather than a list of buttons:

1. **Pick a row** — the player's own cost items are moved out of their
   inventory into the two input slots, and the result appears in the output
   slot. Nothing has been bought yet.
2. **Or fill the inputs by hand** — drag items straight into the input slots.
   The output is recomputed from whatever is there, so a matching combination
   shows a result and a conflicting one leaves the slot empty.
3. **Click the result** — only now does the trade complete.

Like vanilla, the input slots accept **more** than a trade costs but never
less. A row costing one emerald happily takes a stack of sixty-four: the output
shows one lot, clicking the result consumes one emerald, and the remaining
sixty-three stay in the slot still showing a result. Picking a row tops the
slot up in whole lots rather than laying in exactly one, so the result can be
clicked repeatedly without re-picking.

`MerchantMenu.stagedLots()` reports how many whole executions the slots
currently pay for, and `consumeCost(lots)` takes exactly that many lots' worth
and leaves the surplus. Use it rather than `takeInputs()` when completing a
trade — `takeInputs()` empties the slots outright and is for closing or
cancelling.

`onSelect` fires at step 1 and `onTrade` at step 3. Both the Select Trade and
Click Window packets are intercepted and cancelled, so the server never runs a
real trade; your handler decides what actually happens.

### Shift-clicking the result (bulk trades)

Shift-clicking the output buys as many as the player can pay for and carry, in
one settlement — as vanilla does — and puts the goods straight into the
inventory instead of onto the cursor.

```java
menu.onBulkTrade((player, index, count) -> handleTrade(player, index, count));
```

The count is bounded by three things the client can already see, so a batch
never promises more than the window advertises:

| Bound | Source |
| --- | --- |
| Advertised stock | `TradeOffer.remainingUses()`, i.e. `maxUses - uses` |
| What the player can pay | the cost items carried, **plus any surplus already in the slots** |
| Room for the goods | simulated *after* the cost leaves the inventory |

That last point matters: paying frees slots the result then occupies, so
counting free space up front would refuse batches that in fact fit. The sizing
is plain arithmetic over an inventory snapshot in `TradeBatch.size(...)`, which
is public and unit-tested.

FoliaGUI collects the cost for the repeats up front, alongside the lot already
in the input slots. `takeInputs()` returns both, so a failed trade refunds the
whole batch and closing the window mid-trade loses nothing.

If no bulk handler is registered, a shift-click falls back to a single
`onTrade` — an existing menu keeps working rather than silently multiplying a
trade its handler never agreed to. Inside a bulk handler, `menu.isBulk()` is
true; use it to decide between cursor and inventory delivery.

A price that isn't an item (a Vault balance, say) can't be bounded by
`TradeBatch`, which only sees items. Clamp it yourself in the handler.

Because the input slots hold **real items**, they are returned to the player if
the window closes or they disconnect mid-trade.

`updateOffers()` replaces the trade list **in place**. Unlike Bukkit-backed
merchant GUIs, the list can shrink as well as grow with no re-open and no
flicker.

`TradeOffer` supports `disabled(boolean)`, `uses(used, max)`, `xp(int)`,
`priceMultiplier(float)`, `demand(int)` and `specialPrice(int)`. A disabled
offer is sent as fully used up, which is exactly how vanilla renders the red X.

## Paginated menus

Paging is the most-repeated boilerplate in inventory GUIs, so the library owns
it. The backing list is read on every draw, so the menu follows a live
collection:

```java
PaginatedMenu<Warp> menu = gui.paginated(5, Component.text("Warps"), () -> warps);
menu.renderer(warp -> GuiItem.of(Material.ENDER_PEARL, Component.text(warp.name()))
        .onClick(ctx -> ctx.player().teleportAsync(warp.location())));
menu.filler(Material.GRAY_STAINED_GLASS_PANE);
menu.emptyText(Component.text("No warps yet"));
menu.open(player);
```

Chest shapes default to a bordered interior with arrows on the bottom row;
override with `contentSlots(...)`, `navigationSlots(prev, next)` and
`navigationButtons(prev, next)`. Button names support `<page>` and `<pages>`
placeholders. `reload()` re-reads the list and repaints in place.

## Container types

All 25 vanilla menus: the six `GENERIC_9X1`…`GENERIC_9X6` chest shapes plus
`GENERIC_3X3`, `CRAFTER_3X3`, `ANVIL`, `BEACON`, `BLAST_FURNACE`,
`BREWING_STAND`, `CRAFTING`, `ENCHANTMENT`, `FURNACE`, `GRINDSTONE`, `HOPPER`,
`LECTERN`, `LOOM`, `MERCHANT`, `SHULKER_BOX`, `SMITHING`, `SMOKER`,
`CARTOGRAPHY_TABLE`, `STONECUTTER`.

`MenuType.chestRows(n)` picks a chest shape by row count (1–6).

### Why the ids are hardcoded, and why that's fine

The Open Screen packet needs a **numeric** menu type, and nothing exposes one at
runtime: PacketEvents ships ~80 versioned registries but no `menu` registry, and
Bukkit's `Registry.MENU` only exists on 1.21+ where it still yields keys rather
than protocol ids. So a table is unavoidable.

Ids come from the *ordered* `minecraft:menu` registry, so an insertion shifts
everything after it. Exactly one insertion has ever happened — `crafter_3x3` at
index 7, in **1.20.3**. Since this library targets 1.20.6+, every supported
server is already past it, so the table is simply correct with **no version
branching**. The test suite pins all 25 ids and asserts they stay contiguous
0–24, which catches a stale value before it reaches a client.

### Interactive menus

Some menus send extra packets, which FoliaGUI surfaces as handlers:

```java
Menu anvil = gui.menu(MenuType.ANVIL, Component.text("Rename"));
anvil.onTextInput((player, text) -> {
    anvil.set(2, GuiItem.of(Material.PAPER, Component.text(text)));
    anvil.refresh();
});

Menu cutter = gui.menu(MenuType.STONECUTTER, Component.text("Pick one"));
cutter.onButton((player, buttonId) -> choose(player, buttonId));
```

- `onTextInput` — the anvil rename field. The client sends **every keystroke**,
  so debounce anything expensive.
- `onButton` — stonecutter recipes, loom patterns, lectern pages, beacon
  effects, crafter slot toggles.

`MenuType#hasTextInput()` and `#hasButtons()` report which apply.

> **Furnaces, smokers, blast furnaces and brewing stands** render burn and brew
> gauges from container properties FoliaGUI doesn't send, so those bars sit
> empty. Fine as a themed backdrop, misleading as a simulation.

## Safety notes

- **Window ids** cycle 1–99; id 0 is the player's own inventory and is never used.
- **Cursor** is cleared after every click and on close, so a click the client
  optimistically rendered as "picked up" never lingers.
- **Player inventory is resynced** on close, so any speculative client-side
  move is corrected from the server's authoritative state.
- **Quit** removes the menu from the registry.

## Testing

`src/test/manual/MenuLogicTest.java` exercises slot maths, click decoding and
handler routing headlessly (no server, no client):

```bash
javac -cp "build/libs/FoliaGUI-1.0.0.jar:paper-api.jar:packetevents-api.jar:packetevents-spigot.jar:guava.jar:bungeecord-chat.jar:adventure-*.jar" \
      -d /tmp/tout src/test/manual/MenuLogicTest.java
java  -cp "...:/tmp/tout" MenuLogicTest
```

Current result: **PASS=114 FAIL=0**.

## Building

```bash
./gradlew build      # JDK 21; output in build/libs/
```

Dependencies are `compileOnly` — the consuming plugin supplies paper-api and
PacketEvents at runtime.

## License

[MIT](LICENSE) © 2026 Chest Solutions
