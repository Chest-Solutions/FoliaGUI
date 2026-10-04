package dev.csl.foliagui.packet;

import dev.csl.foliagui.FoliaGUI;
import dev.csl.foliagui.menu.ClickContext;
import dev.csl.foliagui.menu.ClickType;
import dev.csl.foliagui.menu.Menu;
import dev.csl.foliagui.menu.MerchantMenu;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindowButton;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientNameItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSelectTrade;
import org.bukkit.entity.Player;

/**
 * Intercepts the client's window packets and drives the open {@link Menu}.
 * <p>
 * Both handled packets are <b>cancelled</b>. The server has no container for
 * these windows, so letting them through would either be a no-op or make the
 * server complain about an unknown window id.
 */
public final class MenuListener implements PacketListener {

    private final FoliaGUI gui;

    public MenuListener(FoliaGUI gui) {
        this.gui = gui;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            handleClick(event, player);
        } else if (event.getPacketType() == PacketType.Play.Client.SELECT_TRADE) {
            handleSelectTrade(event, player);
        } else if (event.getPacketType() == PacketType.Play.Client.NAME_ITEM) {
            handleTextInput(event, player);
        } else if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW_BUTTON) {
            handleButton(event, player);
        } else if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            handleClose(event, player);
        }
    }

    /**
     * Deposits the cursor into an input slot, or lifts its contents back out.
     * <p>
     * This is what makes a slot feel like a real container slot: the item
     * visibly moves into the menu and stays there, instead of the click merely
     * being observed. Handlers read it back with {@code menu.inputItem(slot)}.
     */
    private void emulateInputSlot(Menu menu, int slot) {
        org.bukkit.inventory.ItemStack held = menu.virtualCursor();
        org.bukkit.inventory.ItemStack inSlot = menu.inputItem(slot);

        if (held != null) {
            // Place the cursor down; swap if something was already there.
            menu.set(slot, new dev.csl.foliagui.item.GuiItem(held.clone()));
            menu.virtualCursor(inSlot);
            // The stack now lives in the menu, so the player's slot goes back
            // to showing its real contents.
            menu.clearGhostSlot();
        } else if (inSlot != null) {
            menu.set(slot, null);
            menu.virtualCursor(inSlot.clone());
        } else {
            return;
        }
        menu.fireInputChangeInternal(slot);
    }

    /**
     * Click handling for a merchant window.
     * <p>
     * Vanilla delivers a completed trade to the cursor and leaves it there;
     * the player then clicks a slot to stow it. That needs real click handling,
     * because otherwise the cursor could never be put down.
     */
    private void handleMerchantClick(PacketReceiveEvent event, Player player,
                                     MerchantMenu menu) {
        WrapperPlayClientClickWindow packet = new WrapperPlayClientClickWindow(event);
        if (packet.getWindowId() != menu.windowId()) return;
        event.setCancelled(true);

        int slot = packet.getSlot();
        ClickType click = ClickType.from(packet.getWindowClickType(), packet.getButton());
        // The merchant's own three slots are ours; anything past them is the
        // player's inventory.
        boolean inMerchant = slot >= 0 && slot < 3;
        boolean resultSlot = slot == 2;

        player.getScheduler().run(gui.plugin(), task -> {
            if (!menu.isOpen()) return;

            // Clicking the result completes the trade the inputs earn.
            if (resultSlot) {
                if (menu.hasStage()) {
                    int index = menu.stagedIndex();
                    if (click.isShift()) {
                        // Vanilla shift-click buys as many as the player can
                        // afford and carry, in one settlement -- not one per
                        // click. Anything less makes bulk buying unusable.
                        menu.fireBulkTradeInternal(player, index,
                                stageBatch(player, menu, menu.offers().get(index)));
                    } else {
                        // One lot per plain click. Any surplus stays in the
                        // slot and still shows a result, so the player can
                        // click again -- which is how vanilla behaves.
                        menu.fireTradeInternal(player, index);
                    }
                    menu.refreshSlots();
                    menu.refreshCursor();
                    MenuPackets.resyncPlayerInventory(player);
                }
                return;
            }

            // Placing into or taking from an input slot, so the player can
            // build a trade by hand rather than picking a row.
            if (slot == 0 || slot == 1) {
                swapInputSlot(player, menu, slot);
                menu.refreshSlots();
                menu.refreshCursor();
                return;
            }

            // A click in the player's own inventory. Unlike a chest menu --
            // where the cursor only ever mirrors an item -- a merchant window
            // moves real items, because the input slots have to be filled with
            // goods the player actually owns.
            if (!inMerchant && slot >= 0) {
                int bukkitSlot = merchantBukkitSlot(slot);
                if (bukkitSlot < 0) return;

                org.bukkit.inventory.ItemStack held = menu.cursor();
                org.bukkit.inventory.ItemStack atSlot =
                        player.getInventory().getItem(bukkitSlot);
                boolean slotEmpty = atSlot == null || isAir(atSlot.getType());

                if (held == null) {
                    // Pick up. Without this the cursor could never be filled,
                    // so nothing could be dragged into the input slots.
                    if (slotEmpty) return;
                    org.bukkit.inventory.ItemStack picked = atSlot.clone();
                    if (click.isRight()) {
                        int half = (picked.getAmount() + 1) / 2;
                        picked.setAmount(half);
                        org.bukkit.inventory.ItemStack rest = atSlot.clone();
                        rest.setAmount(atSlot.getAmount() - half);
                        player.getInventory().setItem(bukkitSlot,
                                rest.getAmount() <= 0 ? null : rest);
                    } else {
                        player.getInventory().setItem(bukkitSlot, null);
                    }
                    menu.cursor(picked);
                } else if (slotEmpty) {
                    player.getInventory().setItem(bukkitSlot, held.clone());
                    menu.cursor(null);
                } else if (atSlot.isSimilar(held)) {
                    int room = atSlot.getMaxStackSize() - atSlot.getAmount();
                    int moved = Math.min(room, held.getAmount());
                    if (moved > 0) {
                        org.bukkit.inventory.ItemStack merged = atSlot.clone();
                        merged.setAmount(atSlot.getAmount() + moved);
                        player.getInventory().setItem(bukkitSlot, merged);
                        int remaining = held.getAmount() - moved;
                        org.bukkit.inventory.ItemStack rest = held.clone();
                        rest.setAmount(Math.max(0, remaining));
                        menu.cursor(remaining <= 0 ? null : rest);
                    }
                } else {
                    player.getInventory().setItem(bukkitSlot, held.clone());
                    menu.cursor(atSlot.clone());
                }
                MenuPackets.resyncPlayerInventory(player);
            }
            menu.refreshCursor();
            menu.refreshSlots();
        }, null);
    }

    /**
     * Works out how many times a shift-clicked trade should run, and collects
     * the cost for the repeats beyond the one already in the input slots.
     * <p>
     * Vanilla's shift-click is not "trade once, faster" — it settles as many
     * as the player can pay for and carry. The count is bounded by three
     * things, all of which the client can already see, so the batch never
     * promises more than the window advertises:
     * <ul>
     *   <li>the row's remaining uses, which is how stock is shown;</li>
     *   <li>how much of the cost the player actually holds;</li>
     *   <li>how much of the result fits in the inventory.</li>
     * </ul>
     * The surplus cost is taken out of the inventory here rather than at
     * delivery time, so it is collected under the same all-or-nothing rule as
     * the staged lot and can be handed straight back if the trade fails.
     *
     * @return the number of executions, never less than one
     */
    private int stageBatch(Player player, MerchantMenu menu,
                           dev.csl.foliagui.menu.TradeOffer offer) {
        int cap = offer.remainingUses();
        if (cap <= 0) return 1;

        org.bukkit.inventory.ItemStack[] costs = {
                offer.requiredFirst(), offer.requiredSecond()
        };
        int[] staged = {stagedAmount(menu, costs[0]), stagedAmount(menu, costs[1])};
        // The slots may already hold a surplus, which pays for several lots.
        int stagedLots = Math.max(1, menu.stagedLots());

        int count = dev.csl.foliagui.menu.TradeBatch.size(
                player.getInventory().getStorageContents(), staged, stagedLots, costs,
                offer.output(), cap, offer.isVirtualCost());
        if (count <= stagedLots) return Math.min(count, stagedLots);

        // Fund the lots the slots don't already cover.
        if (!offer.isVirtualCost()) {
            for (org.bukkit.inventory.ItemStack cost : costs) {
                if (cost == null || isAir(cost.getType()) || cost.getAmount() <= 0) continue;
                org.bukkit.inventory.ItemStack extra = cost.clone();
                extra.setAmount(cost.getAmount() * (count - stagedLots));
                org.bukkit.inventory.ItemStack taken = takeFromInventory(player, extra);
                if (taken == null) {
                    // Should not happen after the affordability check, but if
                    // the inventory moved underneath us, fall back to what the
                    // slots definitely cover rather than trading on credit.
                    returnBulk(player, menu);
                    return stagedLots;
                }
                menu.addBulkCost(taken);
            }
        }
        return count;
    }

    /** Hands back any surplus collected for a batch that isn't going ahead. */
    private void returnBulk(Player player, MerchantMenu menu) {
        for (org.bukkit.inventory.ItemStack stack : menu.takeBulkCost()) {
            player.getInventory().addItem(stack).values().forEach(over ->
                    player.getWorld().dropItemNaturally(player.getLocation(), over));
        }
    }

    /** How much of a cost item is already staged in the input slots. */
    private static int stagedAmount(MerchantMenu menu, org.bukkit.inventory.ItemStack cost) {
        if (cost == null || isAir(cost.getType())) return 0;
        int total = 0;
        for (int i = 0; i < 2; i++) {
            if (menu.isPhantom(i)) continue;
            org.bukkit.inventory.ItemStack stack = menu.input(i);
            if (stack != null && stack.getType() == cost.getType()) total += stack.getAmount();
        }
        return total;
    }

    /**
     * Moves the offer's cost items out of the player's inventory and into the
     * merchant's input slots, as vanilla does when a row is picked.
     * <p>
     * Whatever the player cannot afford is simply not moved, so the result slot
     * stays empty and the trade cannot be completed — the same feedback vanilla
     * gives.
     */
    private void autoFillInputs(Player player, MerchantMenu menu,
                                dev.csl.foliagui.menu.TradeOffer offer) {
        returnInputs(player, menu);

        // Pull the *real* cost, not the decorated stack the row displays --
        // taking a renamed token out of the inventory would never succeed.
        if (offer.isVirtualCost()) {
            // Nothing to take from the inventory: show the row's own token so
            // the client sees a filled input and draws the result.
            menu.setPhantomInput(0, offer.firstInput());
            if (offer.hasSecondInput()) menu.setPhantomInput(1, offer.secondInput());
            return;
        }

        org.bukkit.inventory.ItemStack[] wanted = {
                offer.requiredFirst(), offer.requiredSecond()
        };
        for (int i = 0; i < wanted.length; i++) {
            org.bukkit.inventory.ItemStack need = wanted[i];
            if (need == null || isAir(need.getType())) continue;
            // Vanilla doesn't lay in exactly one lot -- it fills the slot as
            // far as the player's supply and the stack size allow, so the
            // result can be clicked repeatedly (and shift-clicked) without
            // re-picking the row. See MC-211364, which is the bug report
            // about this top-up failing.
            org.bukkit.inventory.ItemStack taken = takeFromInventory(player, need);
            if (taken == null) continue;
            int room = Math.max(0, taken.getMaxStackSize() - taken.getAmount());
            // Only top up in whole lots; a partial lot pays for nothing and
            // would just strand items in the slot.
            room -= room % need.getAmount();
            if (room > 0) {
                org.bukkit.inventory.ItemStack extra =
                        takeUpTo(player, need.getType(), room);
                if (extra != null) taken.setAmount(taken.getAmount() + extra.getAmount());
            }
            menu.setInput(i, taken);
        }
    }

    /**
     * Removes up to {@code max} of {@code type} from the inventory.
     * <p>
     * Unlike {@link #takeFromInventory}, this is best-effort rather than
     * all-or-nothing: it is used to top a slot up beyond the exact cost, where
     * getting less than asked for is a perfectly good outcome.
     *
     * @return what was taken, or null if there was none
     */
    private org.bukkit.inventory.ItemStack takeUpTo(Player player,
                                                    org.bukkit.Material type, int max) {
        if (max <= 0) return null;
        int remaining = max;
        int taken = 0;
        org.bukkit.inventory.ItemStack[] contents = player.getInventory().getContents();
        org.bukkit.inventory.ItemStack template = null;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            org.bukkit.inventory.ItemStack stack = contents[i];
            if (stack == null || stack.getType() != type) continue;
            if (template == null) template = stack.clone();
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
            remaining -= take;
            taken += take;
        }
        if (template == null || taken <= 0) return null;
        template.setAmount(taken);
        return template;
    }

    /**
     * Removes {@code required} from the inventory and returns it, or null when
     * the player hasn't got enough. All-or-nothing, so a partial take can never
     * strand items in a slot the player didn't ask to fill.
     */
    private org.bukkit.inventory.ItemStack takeFromInventory(
            Player player, org.bukkit.inventory.ItemStack required) {
        int needed = required.getAmount();
        int found = 0;
        org.bukkit.inventory.ItemStack[] contents = player.getInventory().getContents();
        // Match on type, as vanilla does; a display name must not stop a
        // perfectly good stack from paying for the trade.
        for (org.bukkit.inventory.ItemStack stack : contents) {
            if (stack != null && stack.getType() == required.getType()) {
                found += stack.getAmount();
            }
        }
        if (found < needed) return null;

        int remaining = needed;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            org.bukkit.inventory.ItemStack stack = contents[i];
            if (stack == null || stack.getType() != required.getType()) continue;
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
            remaining -= take;
        }
        org.bukkit.inventory.ItemStack taken = required.clone();
        taken.setAmount(needed);
        return taken;
    }

    /** Puts whatever is sitting in the input slots back in the inventory. */
    private void returnInputs(Player player, MerchantMenu menu) {
        for (org.bukkit.inventory.ItemStack stack : menu.takeInputs()) {
            player.getInventory().addItem(stack).values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
    }

    /**
     * Maps a merchant window slot to a Bukkit inventory index, or -1.
     * <p>
     * A merchant window is three slots followed by the player's 27 main slots
     * and 9 hotbar slots; Bukkit indexes the hotbar first.
     */
    private int merchantBukkitSlot(int rawSlot) {
        int index = rawSlot - 3;
        if (index < 0) return -1;
        int bukkit = index < 27 ? index + 9 : index - 27;
        return bukkit < 0 || bukkit > 35 ? -1 : bukkit;
    }

    /** Swaps the cursor with an input slot, so trades can be built by hand. */
    private void swapInputSlot(Player player, MerchantMenu menu, int slot) {
        org.bukkit.inventory.ItemStack held = menu.cursor();
        org.bukkit.inventory.ItemStack inSlot = menu.input(slot);

        // A phantom is a stand-in the player never owned. Picking it up would
        // mint an item, so clicking one just clears it.
        if (menu.isPhantom(slot)) {
            if (held == null) {
                menu.setInput(slot, null);
            } else {
                menu.setInput(slot, held);
                menu.cursor(null);
            }
            return;
        }

        if (held != null) {
            menu.setInput(slot, held);
            menu.cursor(inSlot);
        } else if (inSlot != null) {
            menu.setInput(slot, null);
            menu.cursor(inSlot);
        }
    }

    /**
     * Mirrors a pick-up / put-down in the player's inventory <em>visually</em>,
     * without ever mutating it.
     *
     * <h2>Why nothing is moved</h2>
     * The click packet is always cancelled, so the server's copy of the
     * inventory is authoritative and untouched. This method only decides what
     * the client should be shown: which stack sits on the cursor, and what the
     * affected slot should look like while that cursor is held. Semantically it
     * is "never remove from my inventory, just put a copy on the cursor".
     * <p>
     * That has three benefits over actually moving items:
     * <ul>
     *   <li>No item can be lost. Nothing left the inventory, so a crash,
     *       disconnect or missed close handler cannot destroy anything.</li>
     *   <li>No duplication. The cursor is a picture, not a stack.</li>
     *   <li>No platform API is required — it is pure protocol state, so the
     *       same logic ports to another loader by swapping the packet layer.</li>
     * </ul>
     * The consequence is that a menu wanting to <em>consume</em> what the
     * player offered must take it explicitly, which is the correct place for
     * that decision anyway.
     */
    private void emulatePlayerInventory(Player player, Menu menu, int rawSlot, ClickType click) {
        int bukkitSlot = bukkitSlot(menu, rawSlot);
        if (bukkitSlot < 0) return;

        org.bukkit.inventory.ItemStack held = menu.virtualCursor();

        if (held != null) {
            if (menu.isCursorOwed()) {
                // These are bought goods, not a mirrored item, so putting them
                // down has to actually store them -- dropping the cursor here
                // would destroy something the player paid for.
                java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover =
                        player.getInventory().addItem(held);
                menu.virtualCursor(leftover.isEmpty()
                        ? null : leftover.values().iterator().next());
                menu.capturePlayerView(player.getInventory().getContents());
                menu.clearGhostSlot();
                MenuPackets.resyncPlayerInventory(player);
                return;
            }
            // Putting down a mirrored item: drop the illusion. The real
            // inventory never changed, so re-showing it restores the picture.
            menu.virtualCursor(null);
            menu.clearGhostSlot();
            return;
        }

        org.bukkit.inventory.ItemStack atSlot = menu.playerSlotView(bukkitSlot);
        if (atSlot == null || isAir(atSlot.getType())) return;

        // Picking up: copy onto the cursor and blank the source slot in the
        // client's view only.
        org.bukkit.inventory.ItemStack picked = atSlot.clone();
        if (click.isRight()) {
            picked.setAmount((picked.getAmount() + 1) / 2);
        }
        menu.virtualCursor(picked);
        menu.ghostSlot(bukkitSlot, click.isRight()
                ? remainderAfterHalf(atSlot)
                : null);
    }

    /** What the source slot should look like after a right-click split. */
    private static org.bukkit.inventory.ItemStack remainderAfterHalf(
            org.bukkit.inventory.ItemStack stack) {
        int keep = stack.getAmount() - (stack.getAmount() + 1) / 2;
        if (keep <= 0) return null;
        org.bukkit.inventory.ItemStack rest = stack.clone();
        rest.setAmount(keep);
        return rest;
    }

    /** Registry-free air test. */
    private static boolean isAir(org.bukkit.Material material) {
        return material == org.bukkit.Material.AIR
                || material == org.bukkit.Material.CAVE_AIR
                || material == org.bukkit.Material.VOID_AIR;
    }

    /**
     * Maps a raw window slot to a Bukkit inventory index, or -1 if it isn't a
     * player-inventory slot.
     * <p>
     * Window order after the menu is 27 main slots then 9 hotbar; Bukkit
     * indexes the hotbar first (0-8) followed by the main inventory (9-35).
     */
    private int bukkitSlot(Menu menu, int rawSlot) {
        int index = rawSlot - menu.size();
        if (index < 0) return -1;
        int slot = index < 27 ? index + 9 : index - 27;
        return slot < 0 || slot > 35 ? -1 : slot;
    }

    /** Anvil rename field. Cancelled: there is no real anvil to rename into. */
    private void handleTextInput(PacketReceiveEvent event, Player player) {
        Menu menu = gui.menuOf(player);
        if (menu == null) return;

        String text = new WrapperPlayClientNameItem(event).getItemName();
        event.setCancelled(true);

        player.getScheduler().run(gui.plugin(), task -> {
            if (!menu.isOpen()) return;
            menu.fireTextInputInternal(player, text);
        }, null);
    }

    /** Stonecutter/loom/lectern/beacon/crafter button presses. */
    private void handleButton(PacketReceiveEvent event, Player player) {
        Menu menu = gui.menuOf(player);
        if (menu == null) return;

        WrapperPlayClientClickWindowButton packet = new WrapperPlayClientClickWindowButton(event);
        if (packet.getWindowId() != menu.windowId()) return;
        int buttonId = packet.getButtonId();
        event.setCancelled(true);

        player.getScheduler().run(gui.plugin(), task -> {
            if (!menu.isOpen()) return;
            menu.fireButtonInternal(player, buttonId);
            // The client may have optimistically redrawn; re-assert the truth.
            menu.refresh();
        }, null);
    }

    /**
     * A merchant row was clicked. The server has no villager behind this
     * window, so the packet is dropped and the handler runs instead.
     */
    private void handleSelectTrade(PacketReceiveEvent event, Player player) {
        MerchantMenu menu = gui.merchantOf(player);
        if (menu == null) return;

        WrapperPlayClientSelectTrade packet = new WrapperPlayClientSelectTrade(event);
        int index = packet.getSlot();
        event.setCancelled(true);

        player.getScheduler().run(gui.plugin(), task -> {
            if (!menu.isOpen()) return;
            if (index < 0 || index >= menu.offers().size()) return;
            // Vanilla fills the input slots with the player's own items when a
            // row is picked, then shows the result. Nothing is exchanged until
            // the result slot is clicked.
            autoFillInputs(player, menu, menu.offers().get(index));
            menu.fireSelectInternal(player, index);
            menu.refreshSlots();
            MenuPackets.resyncPlayerInventory(player);
        }, null);
    }

    private void handleClick(PacketReceiveEvent event, Player player) {
        MerchantMenu merchant = gui.merchantOf(player);
        if (merchant != null) {
            handleMerchantClick(event, player, merchant);
            return;
        }

        Menu menu = gui.menuOf(player);
        if (menu == null) return;

        WrapperPlayClientClickWindow packet = new WrapperPlayClientClickWindow(event);
        if (packet.getWindowId() != menu.windowId()) return;

        int slot = packet.getSlot();
        boolean inMenu = slot >= 0 && slot < menu.size();

        // Every click in this window is cancelled. The window has no
        // server-side container, so the server would ignore it regardless --
        // letting it through just lets the client desync. Player-inventory
        // interaction is emulated below instead.
        event.setCancelled(true);

        ClickType click = ClickType.from(packet.getWindowClickType(), packet.getButton());

        // Run the handler on the thread that owns the player, so handlers can
        // safely touch the player, their inventory, and nearby blocks. This is
        // what makes the library Folia-correct: packet threads are not region
        // threads, and touching world state from here would be a hard error.
        player.getScheduler().run(gui.plugin(), task -> {
            if (!menu.isOpen()) return;

            // Emulate the container behaviour the server won't do for us.
            if (!inMenu && !menu.isPlayerInventoryLocked()) {
                emulatePlayerInventory(player, menu, slot, click);
            } else if (inMenu && menu.isInputSlot(slot)) {
                emulateInputSlot(menu, slot);
            }

            org.bukkit.inventory.ItemStack cursor = menu.virtualCursor();
            ClickContext ctx = menu.handleClick(player, slot, click, packet.getButton(), cursor);

            // A handler may have replaced the cursor; otherwise keep whatever
            // the emulation left there. Either way the client is told, so its
            // optimistic rendering can never drift from our model.
            if (ctx.isCursorChanged()) {
                menu.virtualCursor(ctx.pendingCursor());
            }
            // Push the cursor first, then the window (whose Window Items
            // packet also carries the cursor), then any client-side override
            // of the player's own slots. Order matters: the later packets must
            // not contradict the earlier ones.
            MenuPackets.setCursor(player, menu.virtualCursor());
            menu.refresh();
            if (!inMenu) MenuPackets.sendPlayerInventoryView(menu, player);

            if (ctx.isCloseRequested()) {
                gui.close(menu, true);
            }
        }, null);
    }

    private void handleClose(PacketReceiveEvent event, Player player) {
        WrapperPlayClientCloseWindow packet = new WrapperPlayClientCloseWindow(event);

        Menu menu = gui.menuOf(player);
        if (menu != null && packet.getWindowId() == menu.windowId()) {
            // The client already dismissed it; don't echo a close back.
            gui.close(menu, false);
            return;
        }
        MerchantMenu merchant = gui.merchantOf(player);
        if (merchant != null && packet.getWindowId() == merchant.windowId()) {
            gui.closeMerchant(merchant, false);
        }
    }
}
