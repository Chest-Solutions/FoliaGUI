package dev.csl.foliagui.packet;

import dev.csl.foliagui.item.GuiItem;
import dev.csl.foliagui.menu.Menu;
import dev.csl.foliagui.menu.MenuType;
import dev.csl.foliagui.menu.MerchantMenu;
import dev.csl.foliagui.menu.TradeOffer;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Every outbound packet the library sends. Isolated here so the rest of the
 * codebase stays free of protocol types.
 */
public final class MenuPackets {

    private MenuPackets() {
    }

    private static void send(Player player, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    /** Opens (or re-opens, for a retitle) the window on the client. */
    public static void sendOpen(Menu menu, Player player) {
        send(player, new WrapperPlayServerOpenWindow(
                menu.windowId(),
                menu.type().protocolId(),
                menu.title()));
    }

    /** Replaces the whole window's contents in a single packet. */
    public static void sendContents(Menu menu, Player player, GuiItem[] items) {
        List<ItemStack> converted = new ArrayList<>(items.length);
        for (GuiItem item : items) {
            converted.add(toProtocol(item));
        }
        // The last field is the carried (cursor) item. Sending EMPTY here
        // blanks the cursor on every repaint, which silently destroyed
        // whatever the player was holding -- always echo the real value.
        send(player, new WrapperPlayServerWindowItems(
                menu.windowId(),
                menu.nextStateId(),
                converted,
                stack(menu.virtualCursor())));
    }

    public static void sendSlot(Menu menu, Player player, int slot, org.bukkit.inventory.ItemStack stack) {
        send(player, new WrapperPlayServerSetSlot(
                menu.windowId(),
                menu.nextStateId(),
                slot,
                stack == null ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(stack)));
    }

    /**
     * Clears the cursor stack. Sent when a menu closes so a click that the
     * client optimistically rendered as "picked up" doesn't linger on screen.
     */
    public static void clearCursor(Player player) {
        send(player, new WrapperPlayServerSetSlot(-1, 0, -1, ItemStack.EMPTY));
    }

    /**
     * Puts a specific stack on the player's cursor. Window id -1, slot -1 is
     * the vanilla encoding for "the carried item".
     */
    public static void setCursor(Player player, org.bukkit.inventory.ItemStack stack) {
        send(player, new WrapperPlayServerSetSlot(-1, 0, -1, stack(stack)));
    }

    /** Converts a protocol stack back to Bukkit, or null when empty. */
    public static org.bukkit.inventory.ItemStack toBukkit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        org.bukkit.inventory.ItemStack converted = SpigotConversionUtil.toBukkitItemStack(stack);
        return converted == null || isAir(converted.getType()) ? null : converted;
    }

    public static void sendClose(Menu menu, Player player) {
        send(player, new WrapperPlayServerCloseWindow(menu.windowId()));
    }

    /**
     * Draws the player's own inventory inside our window, applying any
     * client-side override.
     * <p>
     * Set Slot with our window id addresses the lower inventory as the client
     * sees it, so a "picked up" stack can be hidden without the server's copy
     * ever changing. Sending the real value again restores it.
     */
    public static void sendPlayerInventoryView(Menu menu, Player player) {
        int ghost = menu.ghostSlotIndex();
        if (ghost < 0) {
            // Nothing overridden: let the server state speak for itself.
            resyncPlayerInventory(player);
            return;
        }
        // Window slots run: [menu][27 main][9 hotbar]; Bukkit is hotbar first.
        int windowSlot = ghost < 9 ? menu.size() + 27 + ghost : menu.size() + ghost - 9;
        send(player, new WrapperPlayServerSetSlot(
                menu.windowId(), menu.nextStateId(), windowSlot,
                stack(menu.ghostSlotItem())));
    }

    /**
     * Re-sends the player's real inventory.
     * <p>
     * Necessary after closing a packet menu: the client may have speculatively
     * moved items it saw in the fake window. Asking the server for the truth
     * costs one packet and guarantees the player's view matches reality.
     */
    public static void resyncPlayerInventory(Player player) {
        try {
            // Paper exposes this directly; older APIs need the deprecated name.
            Method m = player.getClass().getMethod("updateInventory");
            m.invoke(player);
        } catch (ReflectiveOperationException ignored) {
            player.updateInventory();
        }
    }

    // ----------------------------------------------------------- merchant

    /** Opens a villager window. Trade rows arrive separately. */
    public static void sendMerchantOpen(MerchantMenu menu, Player player) {
        send(player, new WrapperPlayServerOpenWindow(
                menu.windowId(),
                MenuType.MERCHANT.protocolId(),
                menu.title()));
    }

    /**
     * Replaces the merchant's trade list in place.
     * <p>
     * A disabled offer is expressed the way vanilla does it: uses == maxUses,
     * which makes the client draw the red X and refuse to select the row.
     */
    public static void sendMerchantOffers(MerchantMenu menu, Player player) {
        List<MerchantOffer> wire = new ArrayList<>(menu.offers().size());
        for (TradeOffer offer : menu.offers()) {
            // Advertise the *real* cost, not the decorated stack the row shows.
            // The client matches the input slots against these itself and only
            // draws the result when they agree -- send it a renamed token and
            // it can never match the plain item the player puts in, so the
            // output slot stays stubbornly empty.
            // A virtual cost has no real item, so keep showing the token --
            // otherwise the row displays no price at all. The listener puts a
            // matching phantom in the slot, so the client still agrees.
            org.bukkit.inventory.ItemStack first = offer.isVirtualCost()
                    ? offer.firstInput() : offer.requiredFirst();
            org.bukkit.inventory.ItemStack second = offer.isVirtualCost()
                    ? offer.secondInput() : offer.requiredSecond();
            wire.add(MerchantOffer.of(
                    stack(first),
                    second == null ? ItemStack.EMPTY : stack(second),
                    stack(offer.output()),
                    offer.uses(),
                    offer.maxUses(),
                    offer.xp(),
                    offer.specialPrice(),
                    offer.priceMultiplier(),
                    offer.demand()));
        }
        send(player, new WrapperPlayServerMerchantOffers(
                menu.windowId(),
                wire,
                menu.villagerLevel(),
                menu.villagerXp(),
                menu.showProgress(),
                menu.canRestock()));
    }

    /**
     * Blanks the merchant's three slots (two inputs and the result). The client
     * fills these in locally when a row is selected, so they must be corrected
     * once the handler has run.
     */
    /**
     * Draws the merchant's three slots: two inputs and the result.
     * <p>
     * This is the staging area a vanilla villager uses. Picking a trade row
     * fills it in and the exchange only happens when the player clicks the
     * result, so these slots must reflect the staged trade rather than being
     * blanked.
     */
    public static void sendMerchantSlots(MerchantMenu menu, Player player) {
        setMerchantSlot(menu, player, 0, menu.stagedInput());
        setMerchantSlot(menu, player, 1, menu.stagedInput2());
        setMerchantSlot(menu, player, 2, menu.stagedOutput());
        // Re-assert the cursor too: the client moves it optimistically, and
        // blanking it here would destroy goods already handed over.
        send(player, new WrapperPlayServerSetSlot(-1, 0, -1, stack(menu.cursor())));
    }

    private static void setMerchantSlot(MerchantMenu menu, Player player,
                                        int slot, org.bukkit.inventory.ItemStack item) {
        send(player, new WrapperPlayServerSetSlot(
                menu.windowId(), 0, slot, stack(item)));
    }

    public static void sendMerchantClose(MerchantMenu menu, Player player) {
        send(player, new WrapperPlayServerCloseWindow(menu.windowId()));
    }

    private static ItemStack stack(org.bukkit.inventory.ItemStack bukkit) {
        return bukkit == null || isAir(bukkit.getType())
                ? ItemStack.EMPTY
                : SpigotConversionUtil.fromBukkitItemStack(bukkit);
    }

    /** Registry-free air test; see ClickContext#hasCursor. */
    private static boolean isAir(org.bukkit.Material material) {
        return material == org.bukkit.Material.AIR
                || material == org.bukkit.Material.CAVE_AIR
                || material == org.bukkit.Material.VOID_AIR;
    }

    private static ItemStack toProtocol(GuiItem item) {
        if (item == null || item.stack() == null) return ItemStack.EMPTY;
        return SpigotConversionUtil.fromBukkitItemStack(item.stack());
    }
}
