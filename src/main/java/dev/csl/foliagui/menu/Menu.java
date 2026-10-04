package dev.csl.foliagui.menu;

import dev.csl.foliagui.item.GuiItem;
import dev.csl.foliagui.packet.MenuPackets;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A packet-driven inventory menu.
 * <p>
 * No Bukkit {@code Inventory} is ever created: the window exists only on the
 * client, driven by Open Window / Window Items / Set Slot packets. That has
 * three consequences worth knowing:
 * <ul>
 *   <li>The server never allocates a container, so menus cost nothing to keep
 *       open and can't be scraped by hoppers or other plugins.</li>
 *   <li>{@code InventoryClickEvent} never fires, so nothing can steal or cancel
 *       clicks — and the {@code InventoryView} class/interface break that
 *       plagues Bukkit GUI code simply doesn't apply.</li>
 *   <li>Items are display-only by default. The client is told what to render;
 *       it can never actually take an item unless the handler moves it.</li>
 * </ul>
 * Instances are per-viewer. Create one, populate it, {@link #open(Player)}.
 */
public class Menu {

    private final MenuType type;
    private Component title;
    private final GuiItem[] items;
    private final Map<Integer, Consumer<ClickContext>> slotHandlers = new HashMap<>();

    private Consumer<ClickContext> globalHandler;
    private Consumer<Player> closeHandler;
    private java.util.function.BiConsumer<Player, String> textHandler;
    private java.util.function.BiConsumer<Player, Integer> buttonHandler;
    /** Called for clicks in the player's own inventory; cancelled by default. */
    private Consumer<ClickContext> playerInventoryHandler;
    private boolean lockPlayerInventory;

    private final dev.csl.foliagui.FoliaGUI owner;
    private java.util.function.Consumer<Player> openHandler;
    private volatile Player viewer;
    private volatile int windowId = -1;
    private volatile boolean open;
    /** Bumped on every server-driven change; echoed back by the client. */
    private volatile int stateId;
    /**
     * The cursor stack, tracked by us.
     * <p>
     * A packet-only window has no server-side container, so the server ignores
     * every click in it — including clicks in the player's own inventory. The
     * real cursor therefore never changes, and reading it from the click packet
     * always yields empty. FoliaGUI maintains its own view instead: a click on
     * a player-inventory slot picks that item up, a click while holding puts it
     * back, and the client is corrected to match.
     */
    private volatile ItemStack virtualCursor;
    /** Set when the cursor holds bought goods, not a mirrored inventory item. */
    private volatile boolean owedCursor;
    /**
     * Slots the player may freely place items into and take them back out of.
     * <p>
     * Ordinary menu slots are display-only, but an editor often needs a real
     * "drop the item here" slot -- picking a currency, configuring a trade.
     * Marking a slot as an input makes it behave like a container slot: click
     * with a full cursor to deposit, click empty-handed to take it back.
     */
    private final java.util.Set<Integer> inputSlots = new java.util.HashSet<>();
    /**
     * A client-side override for one of the player's own inventory slots.
     * <p>
     * When a stack is "picked up" the real inventory is deliberately left
     * alone, so the source slot must be blanked in the client's view instead.
     * This records which slot, and what to draw there (null = empty).
     */
    private volatile int ghostSlot = -1;
    private volatile ItemStack ghostItem;
    /** Snapshot of the player's inventory, taken when the menu opens. */
    private volatile ItemStack[] playerView;
    private Consumer<Integer> inputChangeHandler;

    /**
     * @param gui the instance that owns this menu; required, because there is
     *            no global registry to fall back on
     */
    public Menu(dev.csl.foliagui.FoliaGUI gui, MenuType type, Component title) {
        if (gui == null) {
            throw new IllegalArgumentException("menu needs an owning FoliaGUI instance");
        }
        this.owner = gui;
        this.type = type;
        this.title = title;
        this.items = new GuiItem[type.size()];
    }

    /** The instance that owns this menu. */
    public dev.csl.foliagui.FoliaGUI gui() {
        return owner;
    }

    // ------------------------------------------------------------- structure

    public MenuType type() {
        return type;
    }

    public int size() {
        return items.length;
    }

    public Component title() {
        return title;
    }

    public Player viewer() {
        return viewer;
    }

    public int windowId() {
        return windowId;
    }

    public boolean isOpen() {
        return open;
    }

    public int nextStateId() {
        return ++stateId;
    }

    public int stateId() {
        return stateId;
    }

    // ----------------------------------------------------------------- items

    public Menu set(int slot, GuiItem item) {
        if (slot < 0 || slot >= items.length) return this;
        items[slot] = item;
        if (item != null && item.handler() != null) {
            slotHandlers.put(slot, item.handler());
        } else {
            slotHandlers.remove(slot);
        }
        if (open) sendSlot(slot);
        return this;
    }

    public Menu set(int column, int row, GuiItem item) {
        return set(row * type.columns() + column, item);
    }

    public GuiItem get(int slot) {
        return slot < 0 || slot >= items.length ? null : items[slot];
    }

    /**
     * Clears the menu, <b>preserving input slots</b>.
     * <p>
     * Input slots hold items the player put there. A redraw is a repaint of the
     * plugin's own chrome, not a reason to destroy the player's contribution --
     * wiping them here meant any control that repainted (say a +1 button) threw
     * away the item being edited.
     */
    public Menu clear() {
        for (int i = 0; i < items.length; i++) {
            if (inputSlots.contains(i)) continue;
            items[i] = null;
            slotHandlers.remove(i);
        }
        if (open) refresh();
        return this;
    }

    /** Clears everything, including input slots. */
    public Menu clearAll() {
        Arrays.fill(items, null);
        slotHandlers.clear();
        inputSlots.clear();
        if (open) refresh();
        return this;
    }

    /** Fills every empty slot with a decorative pane. */
    public Menu fill(Material material) {
        GuiItem filler = GuiItem.filler(material);
        for (int i = 0; i < items.length; i++) {
            // Input slots stay genuinely empty -- a filler pane there would
            // read as occupied and the player could not drop anything in.
            if (items[i] == null && !inputSlots.contains(i)) items[i] = filler;
        }
        if (open) refresh();
        return this;
    }

    /** Draws a one-slot-thick border of {@code material} around the menu. */
    public Menu border(Material material) {
        // (border deliberately still draws over input slots only if the caller
        // put one on the edge, which would be a layout mistake)
        GuiItem pane = GuiItem.filler(material);
        int cols = type.columns();
        int rows = type.rows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean edge = r == 0 || r == rows - 1 || c == 0 || c == cols - 1;
                if (edge) {
                    int slot = r * cols + c;
                    if (items[slot] == null) items[slot] = pane;
                }
            }
        }
        if (open) refresh();
        return this;
    }

    // -------------------------------------------------------------- handlers

    public Menu onClick(Consumer<ClickContext> handler) {
        this.globalHandler = handler;
        return this;
    }

    public Menu onClose(Consumer<Player> handler) {
        this.closeHandler = handler;
        return this;
    }

    /**
     * Called when the player types in a menu's text field.
     * <p>
     * Only {@link MenuType#ANVIL} has one. The client sends every keystroke, so
     * expect a callback per character — debounce if the work is expensive.
     * Because no real anvil exists, the result slot stays whatever you put
     * there; a common pattern is to update slot 2 to echo the typed text.
     */
    public Menu onTextInput(java.util.function.BiConsumer<Player, String> handler) {
        this.textHandler = handler;
        return this;
    }

    /**
     * Called when the player presses one of a menu's built-in buttons: a
     * stonecutter recipe, loom pattern, lectern page, beacon effect or crafter
     * slot toggle. The id identifies which one, per the vanilla protocol.
     */
    public Menu onButton(java.util.function.BiConsumer<Player, Integer> handler) {
        this.buttonHandler = handler;
        return this;
    }

    public void fireTextInputInternal(Player player, String text) {
        if (textHandler != null) textHandler.accept(player, text);
    }

    public void fireButtonInternal(Player player, int buttonId) {
        if (buttonHandler != null) buttonHandler.accept(player, buttonId);
    }

    /**
     * Marks slots as accepting player items. Contents are read back with
     * {@link #get(int)}; the stack lives in the menu, nothing is consumed
     * from the player until you take it.
     */
    public Menu inputSlots(int... slots) {
        inputSlots.clear();
        for (int slot : slots) inputSlots.add(slot);
        return this;
    }

    public boolean isInputSlot(int slot) {
        return inputSlots.contains(slot);
    }

    public java.util.Set<Integer> inputSlotSet() {
        return java.util.Collections.unmodifiableSet(inputSlots);
    }

    /** Notified with the slot index whenever an input slot's contents change. */
    public Menu onInputChange(Consumer<Integer> handler) {
        this.inputChangeHandler = handler;
        return this;
    }

    public void fireInputChangeInternal(int slot) {
        if (inputChangeHandler != null) inputChangeHandler.accept(slot);
    }

    /** The stack currently sitting in an input slot, or null. */
    public ItemStack inputItem(int slot) {
        GuiItem item = get(slot);
        return item == null || item.stack() == null
                || item.stack().getType() == Material.AIR ? null : item.stack();
    }

    /** Called once the window has been shown to the player. */
    public Menu onOpen(Consumer<Player> handler) {
        this.openHandler = handler;
        return this;
    }

    public void fireOpenInternal(Player player) {
        if (openHandler != null) openHandler.accept(player);
    }

    /**
     * Observes clicks in the player's own inventory.
     * <p>
     * The click still goes through normally — this is a notification, not a
     * veto. Use {@link #lockPlayerInventory(boolean)} to actually block them.
     */
    public Menu onPlayerInventoryClick(Consumer<ClickContext> handler) {
        this.playerInventoryHandler = handler;
        return this;
    }

    /**
     * Blocks interaction with the player's own inventory while this menu is
     * open. Off by default: the bottom inventory is the player's, and a menu
     * has no business freezing it unless it is doing something like an item
     * picker where a stray move would be confusing.
     */
    public Menu lockPlayerInventory(boolean lock) {
        this.lockPlayerInventory = lock;
        return this;
    }

    public boolean isPlayerInventoryLocked() {
        return lockPlayerInventory;
    }

    // ------------------------------------------------------------ lifecycle

    /** Opens the menu for {@code player}, replacing any menu already open. */
    public void open(Player player) {
        gui().open(this, player);
    }

    /** Closes the menu and tells the client to dismiss the window. */
    public void close() {
        gui().close(this, true);
    }

    /** Re-sends every slot. Cheap: one packet regardless of menu size. */
    public void refresh() {
        if (!open || viewer == null) return;
        MenuPackets.sendContents(this, viewer, items);
    }

    /** Re-sends one slot. */
    public void sendSlot(int slot) {
        if (!open || viewer == null) return;
        GuiItem item = get(slot);
        MenuPackets.sendSlot(this, viewer, slot, item == null ? null : item.stack());
    }

    /**
     * Retitles a live menu. The protocol has no "rename" packet, so this
     * re-opens the window with the same id — the client keeps the slots it
     * already has, and contents are re-sent immediately afterwards.
     */
    public void title(Component newTitle) {
        this.title = newTitle;
        if (open && viewer != null) {
            MenuPackets.sendOpen(this, viewer);
            refresh();
        }
    }

    // ------------------------------------------- internals used by FoliaGUI

    public void markOpened(Player player, int windowId) {
        this.viewer = player;
        this.windowId = windowId;
        this.open = true;
    }

    public void markClosed() {
        this.open = false;
        this.windowId = -1;
        this.virtualCursor = null;
        clearGhostSlot();
    }

    /**
     * Puts {@code stack} on the cursor, merging with whatever is held when the
     * two are compatible.
     * <p>
     * Used to hand a player goods they have just bought: the item appears in
     * hand and they choose where it goes, rather than it teleporting into a
     * bag. Unlike the pick-up illusion, this stack is genuinely owed to them,
     * so {@link dev.csl.foliagui.FoliaGUI} returns it if the menu closes.
     *
     * @return the part that would not fit, or null if all of it landed
     */
    public ItemStack addToCursor(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        ItemStack held = virtualCursor;
        if (held == null) {
            virtualCursor(stack.clone());
            owedCursor = true;
            return null;
        }
        if (!held.isSimilar(stack)) return stack.clone();

        int room = held.getMaxStackSize() - held.getAmount();
        if (room <= 0) return stack.clone();

        int moved = Math.min(room, stack.getAmount());
        ItemStack merged = held.clone();
        merged.setAmount(held.getAmount() + moved);
        virtualCursor(merged);
        owedCursor = true;

        int leftover = stack.getAmount() - moved;
        if (leftover <= 0) return null;
        ItemStack rest = stack.clone();
        rest.setAmount(leftover);
        return rest;
    }

    /**
     * True when the cursor holds real goods rather than a pick-up illusion,
     * and so must be handed over if the menu closes.
     */
    public boolean isCursorOwed() {
        return owedCursor && virtualCursor != null;
    }

    /** Pushes the current cursor to the client. */
    public void refreshCursor() {
        if (!open || viewer == null) return;
        dev.csl.foliagui.packet.MenuPackets.setCursor(viewer, virtualCursor);
    }

    /** Records a client-only override of a player inventory slot. */
    public void ghostSlot(int bukkitSlot, ItemStack shown) {
        this.ghostSlot = bukkitSlot;
        this.ghostItem = shown;
    }

    public void clearGhostSlot() {
        this.ghostSlot = -1;
        this.ghostItem = null;
    }

    public int ghostSlotIndex() {
        return ghostSlot;
    }

    public ItemStack ghostSlotItem() {
        return ghostItem;
    }

    /** Caches the player's inventory so slot reads need no platform call. */
    public void capturePlayerView(ItemStack[] contents) {
        this.playerView = contents;
    }

    /** The player's inventory as this menu last saw it. */
    public ItemStack playerSlotView(int bukkitSlot) {
        ItemStack[] view = playerView;
        if (view == null || bukkitSlot < 0 || bukkitSlot >= view.length) return null;
        return view[bukkitSlot];
    }

    public ItemStack[] playerView() {
        return playerView;
    }

    /** The stack FoliaGUI believes is on the cursor, or null. */
    public ItemStack virtualCursor() {
        return virtualCursor;
    }

    public void virtualCursor(ItemStack stack) {
        this.virtualCursor = stack == null || stack.getType() == Material.AIR
                ? null : stack;
        if (this.virtualCursor == null) owedCursor = false;
    }

    /** Invoked by the manager once the close packets have been flushed. */
    public void fireCloseInternal(Player player) {
        if (closeHandler != null) closeHandler.accept(player);
    }

    /**
     * Routes an incoming click. Returns the context so the caller can honour a
     * {@link ClickContext#close()} request.
     */
    public ClickContext handleClick(Player player, int rawSlot, ClickType click, int button) {
        return handleClick(player, rawSlot, click, button, null);
    }

    public ClickContext handleClick(Player player, int rawSlot, ClickType click, int button,
                                    ItemStack cursor) {
        boolean inMenu = rawSlot >= 0 && rawSlot < items.length;
        int slot = inMenu ? rawSlot : -1;
        ClickContext ctx = new ClickContext(this, player,
                inMenu ? slot : rawSlot, click, button, cursor);

        if (!inMenu) {
            if (playerInventoryHandler != null) {
                playerInventoryHandler.accept(ctx);
            }
            return ctx;
        }
        if (globalHandler != null) globalHandler.accept(ctx);
        Consumer<ClickContext> slotHandler = slotHandlers.get(slot);
        if (slotHandler != null) slotHandler.accept(ctx);
        return ctx;
    }

    ItemStack[] rawContents() {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] == null ? null : items[i].stack();
        }
        return out;
    }

    GuiItem[] itemArray() {
        return items;
    }
}
