package dev.csl.foliagui.menu;

import dev.csl.foliagui.packet.MenuPackets;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The real vanilla villager trading screen, driven entirely by packets.
 * <p>
 * Unlike a chest menu, the merchant window's rows come from the Merchant
 * Offers packet rather than from slot contents. Selecting a row makes the
 * client send Select Trade, which FoliaGUI intercepts and cancels — the trade
 * is never executed by the server. Your handler decides what actually happens.
 * <p>
 * That matters for shops with currency prices: vanilla merchants can only
 * barter item-for-item, but because the trade is never really performed you
 * can display a token item as the "cost" and settle the real payment however
 * you like.
 *
 * <pre>{@code
 * MerchantMenu menu = new MerchantMenu(Component.text("Bob's Shop"));
 * menu.addOffer(new TradeOffer(priceToken, new ItemStack(Material.DIAMOND)));
 * menu.onTrade((player, index) -> buy(player, index));
 * menu.open(player);
 * }</pre>
 */
public final class MerchantMenu {

    private Component title;
    private final List<TradeOffer> offers = new ArrayList<>();
    private BiConsumer<Player, Integer> tradeHandler;
    private BiConsumer<Player, Integer> selectHandler;
    private java.util.function.Consumer<Player> closeHandler;

    private int villagerLevel;
    private int villagerXp;
    private boolean showProgress;
    private boolean canRestock;

    private final dev.csl.foliagui.FoliaGUI owner;
    /**
     * The stack shown on the player's cursor.
     * <p>
     * Vanilla hands a completed trade to the cursor and lets the player decide
     * where it goes; the result slot is not auto-collected. Reproducing that
     * needs the cursor to be part of the menu's state.
     */
    /**
     * The trade currently staged in the merchant's own three slots.
     * <p>
     * Vanilla does not complete a trade when a row is picked. It lays the
     * required inputs into slots 0 and 1 and the result into slot 2, and waits
     * for the player to click the result. Reproducing that means remembering
     * which row is staged and what is being shown.
     */
    /**
     * Real items sitting in the merchant's two input slots.
     * <p>
     * These are the player's own goods, moved out of their inventory exactly as
     * a vanilla villager does. They are returned when the window closes, so
     * nothing is lost if the trade is abandoned.
     */
    private final ItemStack[] inputs = new ItemStack[2];
    /**
     * Marks an input slot as holding a server-placed stand-in rather than the
     * player's property -- the token shown for a money price, say. Phantoms are
     * never handed back, because the player never owned them.
     */
    private final boolean[] phantom = new boolean[2];
    /** The result the current inputs earn, or null when they match nothing. */
    private volatile ItemStack stagedOutput;
    /** Index of the offer the inputs satisfy, or -1. */
    private volatile int matchedIndex = -1;
    /** The row the player last clicked, for offers with no item cost. */
    private volatile int selectedRow = -1;

    private volatile ItemStack cursor;
    private volatile Player viewer;
    private volatile int windowId = -1;
    private volatile boolean open;

    public MerchantMenu(dev.csl.foliagui.FoliaGUI gui, Component title) {
        if (gui == null) {
            throw new IllegalArgumentException("menu needs an owning FoliaGUI instance");
        }
        this.owner = gui;
        this.title = title;
    }

    /** The instance that owns this menu. */
    public dev.csl.foliagui.FoliaGUI gui() {
        return owner;
    }

    // ---------------------------------------------------------------- offers

    public MerchantMenu addOffer(TradeOffer offer) {
        offers.add(offer);
        recompute();
        return this;
    }

    public MerchantMenu setOffers(List<TradeOffer> newOffers) {
        offers.clear();
        offers.addAll(newOffers);
        // The staged match points into the old list, so it must be recomputed
        // against the new one -- otherwise the output slot keeps showing a
        // result for a row that has moved, or the index no longer resolves and
        // clicking the result does nothing.
        recompute();
        return this;
    }

    public List<TradeOffer> offers() {
        return offers;
    }

    public MerchantMenu clearOffers() {
        offers.clear();
        recompute();
        return this;
    }

    /**
     * Pushes the current offer list to the client.
     * <p>
     * Unlike Bukkit-backed merchant GUIs, this needs no re-open: the Merchant
     * Offers packet fully replaces the trade list in place, so the list can
     * shrink as well as grow with no flicker.
     */
    public void updateOffers() {
        if (!open || viewer == null) return;
        MenuPackets.sendMerchantOffers(this, viewer);
    }

    // --------------------------------------------------------- villager trim

    /** Shows the experience bar and level badge, as a real villager would. */
    public MerchantMenu villager(int level, int xp, boolean showProgress, boolean canRestock) {
        this.villagerLevel = level;
        this.villagerXp = xp;
        this.showProgress = showProgress;
        this.canRestock = canRestock;
        return this;
    }

    public int villagerLevel() {
        return villagerLevel;
    }

    public int villagerXp() {
        return villagerXp;
    }

    public boolean showProgress() {
        return showProgress;
    }

    public boolean canRestock() {
        return canRestock;
    }

    // -------------------------------------------------------------- handlers

    /**
     * Called when a row is picked, before anything is exchanged.
     * <p>
     * The handler decides what to lay out in the merchant slots, normally by
     * calling {@link #stage}. Nothing is bought at this point.
     */
    public MerchantMenu onSelect(BiConsumer<Player, Integer> handler) {
        this.selectHandler = handler;
        return this;
    }

    /**
     * Called when the player clicks the result slot of a staged trade — the
     * point at which the exchange actually happens.
     */
    public MerchantMenu onTrade(BiConsumer<Player, Integer> handler) {
        this.tradeHandler = handler;
        return this;
    }

    /**
     * A trade the player asked to repeat, by shift-clicking the result.
     * <p>
     * Vanilla does not fire the trade several times over; it works out how
     * many the player can afford and has room for, and settles them in one go.
     * Reproducing that needs the count to reach the handler, so a shop can
     * check stock and take payment once for the whole batch instead of racing
     * a loop of independent trades.
     */
    @FunctionalInterface
    public interface BulkTradeHandler {
        void accept(Player player, int index, int count);
    }

    private BulkTradeHandler bulkHandler;
    /** True while a shift-click batch is being handled. */
    private volatile boolean bulk;

    public MerchantMenu onBulkTrade(BulkTradeHandler handler) {
        this.bulkHandler = handler;
        return this;
    }

    /**
     * True when the trade currently running came from a shift-click.
     * <p>
     * Matters for delivery: a normal trade hands the goods to the cursor, but
     * a shift-clicked batch goes straight into the inventory — which is the
     * whole point of shift-clicking.
     */
    public boolean isBulk() {
        return bulk;
    }

    public void fireBulkTradeInternal(Player player, int index, int count) {
        bulk = true;
        try {
            if (bulkHandler != null) {
                bulkHandler.accept(player, index, count);
            } else if (tradeHandler != null) {
                // No bulk handler registered: behave exactly as a single click,
                // so an existing menu keeps working rather than silently
                // multiplying a trade it never agreed to.
                tradeHandler.accept(player, index);
            }
        } finally {
            bulk = false;
        }
    }

    /** Remembers which row was clicked, so money-priced offers stay usable. */
    public void selectRow(int index) {
        this.selectedRow = index;
        recompute();
    }

    /** Puts a real stack into input slot 0 or 1. */
    public void setInput(int slot, ItemStack stack) {
        if (slot < 0 || slot > 1) return;
        inputs[slot] = copy(stack);
        phantom[slot] = false;
        recompute();
    }

    /**
     * Places a stand-in the player does not own, so a cost that isn't an item
     * can still be shown and matched by the client.
     */
    public void setPhantomInput(int slot, ItemStack stack) {
        if (slot < 0 || slot > 1) return;
        inputs[slot] = copy(stack);
        phantom[slot] = inputs[slot] != null;
        recompute();
    }

    public boolean isPhantom(int slot) {
        return slot >= 0 && slot <= 1 && phantom[slot];
    }

    public ItemStack input(int slot) {
        return slot < 0 || slot > 1 ? null : inputs[slot];
    }

    /**
     * Cost collected for the extra repeats of a shift-clicked trade.
     * <p>
     * The window has only two input slots, so a batch of twenty cannot be laid
     * out in them. The surplus is held here instead: it is still the player's
     * property, so it must be returned on failure or on close exactly as the
     * visible slots are.
     */
    private final List<ItemStack> bulkCost = new ArrayList<>();

    /** Adds cost already taken from the inventory for a batched trade. */
    public void addBulkCost(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return;
        bulkCost.add(stack.clone());
    }

    /**
     * How many whole executions the input slots currently pay for.
     * <p>
     * Vanilla accepts <em>more</em> than a trade costs, never less: a row
     * costing one emerald happily takes a stack of sixty-four, and the surplus
     * simply stays in the slot. So the slots hold a number of lots, not a
     * boolean "paid", and the rest of the class has to reason in those terms.
     *
     * @return 0 when the inputs don't cover the matched offer
     */
    public int stagedLots() {
        if (matchedIndex < 0 || matchedIndex >= offers.size()) return 0;
        TradeOffer offer = offers.get(matchedIndex);
        // A cost that isn't an item can't be counted out of the slots; one
        // click is one trade.
        if (offer.isVirtualCost()) return 1;
        return lotsFor(offer, inputs[0], inputs[1]);
    }

    /** Whole lots of {@code offer} that {@code a} and {@code b} pay for. */
    private static int lotsFor(TradeOffer offer, ItemStack a, ItemStack b) {
        int direct = Math.min(lots(offer.requiredFirst(), a), lots(offer.requiredSecond(), b));
        int swapped = Math.min(lots(offer.requiredFirst(), b), lots(offer.requiredSecond(), a));
        return Math.max(direct, swapped);
    }

    private static int lots(ItemStack required, ItemStack supplied) {
        boolean needsNothing = required == null || required.getType() == Material.AIR;
        boolean suppliedNothing = supplied == null || supplied.getType() == Material.AIR;
        // An unused cost slot is satisfied any number of times over, so it must
        // not cap the batch -- but it does have to hold nothing.
        if (needsNothing) return suppliedNothing ? Integer.MAX_VALUE : 0;
        if (suppliedNothing) return 0;
        if (supplied.getType() != required.getType()) return 0;
        if (required.getAmount() <= 0) return Integer.MAX_VALUE;
        return supplied.getAmount() / required.getAmount();
    }

    /**
     * Takes the cost of {@code lots} executions out of the input slots and
     * returns it, leaving any surplus where it is.
     * <p>
     * This is what vanilla does when the result is clicked, and the reason it
     * is not simply {@link #takeInputs()}: emptying the slots would swallow the
     * change. A player who dropped a stack of sixty-four onto a one-emerald row
     * would pay all sixty-four for a single item.
     *
     * @return the stacks actually consumed, for a caller that has to refund
     */
    public List<ItemStack> consumeCost(int lots) {
        List<ItemStack> paid = new ArrayList<>(2);
        if (lots <= 0 || matchedIndex < 0 || matchedIndex >= offers.size()) return paid;
        TradeOffer offer = offers.get(matchedIndex);
        if (offer.isVirtualCost()) {
            // Nothing real to take; clear any stand-in so the slots don't keep
            // showing a price that was never paid in items.
            for (int i = 0; i < inputs.length; i++) {
                if (phantom[i]) {
                    inputs[i] = null;
                    phantom[i] = false;
                }
            }
            paid.addAll(bulkCost);
            bulkCost.clear();
            recompute();
            return paid;
        }

        ItemStack[] costs = {offer.requiredFirst(), offer.requiredSecond()};
        for (ItemStack cost : costs) {
            if (cost == null || cost.getType() == Material.AIR || cost.getAmount() <= 0) continue;
            int owed = cost.getAmount() * lots;
            for (int i = 0; i < inputs.length && owed > 0; i++) {
                ItemStack in = inputs[i];
                if (in == null || phantom[i] || in.getType() != cost.getType()) continue;
                int take = Math.min(owed, in.getAmount());
                owed -= take;
                int left = in.getAmount() - take;
                inputs[i] = left <= 0 ? null : withAmount(in, left);
                paid.add(withAmount(in, take));
            }
        }
        paid.addAll(bulkCost);
        bulkCost.clear();
        // The surplus may still pay for another go, which is exactly how
        // vanilla lets you click the result repeatedly without re-filling.
        recompute();
        return paid;
    }

    private static ItemStack withAmount(ItemStack base, int amount) {
        ItemStack copy = base.clone();
        copy.setAmount(amount);
        return copy;
    }

    /** Empties the batched surplus and returns it, leaving the slots alone. */
    public List<ItemStack> takeBulkCost() {
        List<ItemStack> held = new ArrayList<>(bulkCost);
        bulkCost.clear();
        return held;
    }

    /**
     * Empties both input slots — and any batched surplus — and returns what
     * they held.
     */
    public List<ItemStack> takeInputs() {
        List<ItemStack> held = new ArrayList<>(2);
        for (int i = 0; i < inputs.length; i++) {
            // Phantoms are dropped, not returned: handing one back would mint
            // an item out of nothing.
            if (inputs[i] != null && !phantom[i]) held.add(inputs[i]);
            inputs[i] = null;
            phantom[i] = false;
        }
        held.addAll(bulkCost);
        bulkCost.clear();
        recompute();
        return held;
    }

    /**
     * Works out which offer the current inputs satisfy and what it pays.
     * <p>
     * This is the behaviour that makes a merchant window feel real: the output
     * follows the inputs, so dragging items in shows a result without picking a
     * row, and inputs that match nothing show an empty result slot.
     */
    public void recompute() {
        matchedIndex = -1;
        stagedOutput = null;
        for (int i = 0; i < offers.size(); i++) {
            TradeOffer offer = offers.get(i);
            if (offer.isDisabled()) continue;
            if (satisfiedBy(offer, inputs[0], inputs[1])) {
                matchedIndex = i;
                stagedOutput = copy(offer.output());
                return;
            }
        }
        // Nothing matched on items. A row whose cost is not an item at all --
        // money through an economy plugin -- still needs to be completable.
        // Its slots are either empty or hold a phantom stand-in, neither of
        // which is something the player handed over.
        boolean noRealInput = (inputs[0] == null || phantom[0])
                && (inputs[1] == null || phantom[1]);
        if (noRealInput && selectedRow >= 0 && selectedRow < offers.size()) {
            TradeOffer offer = offers.get(selectedRow);
            if (offer.isVirtualCost() && !offer.isDisabled()) {
                matchedIndex = selectedRow;
                stagedOutput = copy(offer.output());
            }
        }
    }

    /** True when {@code a} and {@code b} cover the offer's cost, either way round. */
    private static boolean satisfiedBy(TradeOffer offer, ItemStack a, ItemStack b) {
        ItemStack first = offer.requiredFirst();
        ItemStack second = offer.requiredSecond();
        return (covers(first, a) && covers(second, b))
                || (covers(first, b) && covers(second, a));
    }

    private static boolean covers(ItemStack required, ItemStack supplied) {
        boolean needsNothing = required == null || required.getType() == Material.AIR;
        boolean suppliedNothing = supplied == null || supplied.getType() == Material.AIR;
        if (needsNothing) return suppliedNothing;
        if (suppliedNothing) return false;
        // Vanilla matches on item type and count, not on display name. Using
        // isSimilar() here meant a decorated row item could never be satisfied
        // by the plain stack a player drags in.
        return supplied.getType() == required.getType()
                && supplied.getAmount() >= required.getAmount();
    }

    /** Empties both input slots without returning anything. */
    public void clearStage() {
        inputs[0] = null;
        inputs[1] = null;
        phantom[0] = false;
        phantom[1] = false;
        stagedOutput = null;
        matchedIndex = -1;
        selectedRow = -1;
        bulkCost.clear();
    }

    /** True when the inputs currently earn a result. */
    public boolean hasStage() {
        return matchedIndex >= 0;
    }

    public int stagedIndex() {
        return matchedIndex;
    }

    public ItemStack stagedInput() {
        return inputs[0];
    }

    public ItemStack stagedInput2() {
        return inputs[1];
    }

    public ItemStack stagedOutput() {
        return stagedOutput;
    }

    /** Pushes the three merchant slots to the client. */
    public void refreshSlots() {
        if (!open || viewer == null) return;
        dev.csl.foliagui.packet.MenuPackets.sendMerchantSlots(this, viewer);
    }

    public void fireSelectInternal(Player player, int index) {
        if (selectHandler != null) selectHandler.accept(player, index);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR ? null : stack.clone();
    }

    public MerchantMenu onClose(java.util.function.Consumer<Player> handler) {
        this.closeHandler = handler;
        return this;
    }

    // ------------------------------------------------------------ lifecycle

    public void open(Player player) {
        gui().openMerchant(this, player);
    }

    public void close() {
        gui().closeMerchant(this, true);
    }

    public Component title() {
        return title;
    }

    public void title(Component newTitle) {
        this.title = newTitle;
        if (open && viewer != null) {
            MenuPackets.sendMerchantOpen(this, viewer);
            updateOffers();
        }
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

    public void markOpened(Player player, int id) {
        this.viewer = player;
        this.windowId = id;
        this.open = true;
    }

    public void markClosed() {
        this.open = false;
        this.windowId = -1;
        this.cursor = null;
        clearStage();
    }

    /** What the player is currently carrying, or null. */
    public ItemStack cursor() {
        return cursor;
    }

    public void cursor(ItemStack stack) {
        this.cursor = stack == null || stack.getType() == Material.AIR ? null : stack;
    }

    /**
     * Puts {@code stack} on the cursor, merging with whatever is already there
     * when the two are compatible.
     *
     * @return the part that would not fit, or null if all of it landed
     */
    public ItemStack addToCursor(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        ItemStack held = cursor;
        if (held == null) {
            cursor(stack.clone());
            return null;
        }
        if (!held.isSimilar(stack)) return stack.clone();

        int max = held.getMaxStackSize();
        int room = max - held.getAmount();
        if (room <= 0) return stack.clone();

        int moved = Math.min(room, stack.getAmount());
        ItemStack merged = held.clone();
        merged.setAmount(held.getAmount() + moved);
        cursor(merged);

        int leftover = stack.getAmount() - moved;
        if (leftover <= 0) return null;
        ItemStack rest = stack.clone();
        rest.setAmount(leftover);
        return rest;
    }

    /** Pushes the current cursor to the client. */
    public void refreshCursor() {
        if (!open || viewer == null) return;
        dev.csl.foliagui.packet.MenuPackets.setCursor(viewer, cursor);
    }

    public void fireTradeInternal(Player player, int index) {
        if (tradeHandler != null) tradeHandler.accept(player, index);
    }

    public void fireCloseInternal(Player player) {
        if (closeHandler != null) closeHandler.accept(player);
    }
}
