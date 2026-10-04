package dev.csl.foliagui.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Everything a click handler needs, with no protocol types leaking through. */
public final class ClickContext {

    private final Menu menu;
    private final Player player;
    private final int slot;
    private final ClickType click;
    private final int button;
    private final ItemStack cursor;
    private ItemStack newCursor;
    private boolean cursorChanged;
    private boolean closeRequested;

    public ClickContext(Menu menu, Player player, int slot, ClickType click, int button,
                        ItemStack cursor) {
        this.menu = menu;
        this.player = player;
        this.slot = slot;
        this.click = click;
        this.button = button;
        this.cursor = cursor;
    }

    public Menu menu() {
        return menu;
    }

    public Player player() {
        return player;
    }

    /** Slot index within the menu, or negative for player-inventory slots. */
    public int slot() {
        return slot;
    }

    public ClickType click() {
        return click;
    }

    /** Raw protocol button, e.g. the hotbar index for {@link ClickType#NUMBER_KEY}. */
    public int button() {
        return button;
    }

    public boolean isLeft() {
        return click.isLeft();
    }

    public boolean isRight() {
        return click.isRight();
    }

    public boolean isShift() {
        return click.isShift();
    }

    /**
     * The item the client says it is holding on the cursor, or {@code null}
     * when the cursor is empty.
     * <p>
     * <b>This is client-supplied data.</b> The window is packet-only, so the
     * server has no container to validate it against — a modified client can
     * claim to be holding anything. Treat it as a <em>request</em>, not a
     * fact. Never hand out items, money, or permissions based on it without
     * checking the player's real inventory first.
     * <p>
     * It is perfectly safe for its intended use: picking up a visual template,
     * such as letting an admin choose which item an entry should display.
     */
    public ItemStack cursor() {
        return cursor;
    }

    public boolean hasCursor() {
        return cursor != null && !isAir(cursor.getType());
    }

    /**
     * Air check that avoids {@code Material#isAir()}, which resolves through
     * Paper's registry and therefore needs a running server.
     */
    private static boolean isAir(org.bukkit.Material material) {
        return material == org.bukkit.Material.AIR
                || material == org.bukkit.Material.CAVE_AIR
                || material == org.bukkit.Material.VOID_AIR;
    }

    /**
     * Sets the cursor the client will be holding once the handler returns.
     * Pass {@code null} to clear it. This is authoritative: whatever is set
     * here overwrites whatever the client believed it had.
     */
    public void setCursor(ItemStack stack) {
        this.newCursor = stack;
        this.cursorChanged = true;
    }

    /** Clears the cursor. Equivalent to {@code setCursor(null)}. */
    public void clearCursor() {
        setCursor(null);
    }

    public ItemStack pendingCursor() {
        return newCursor;
    }

    public boolean isCursorChanged() {
        return cursorChanged;
    }

    /** Ask the library to close this menu once the handler returns. */
    public void close() {
        this.closeRequested = true;
    }

    public boolean isCloseRequested() {
        return closeRequested;
    }
}
