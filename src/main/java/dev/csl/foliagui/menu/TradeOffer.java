package dev.csl.foliagui.menu;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * One row in a {@link MerchantMenu}: up to two input items and one output.
 * <p>
 * This mirrors the vanilla merchant offer wire format, but the inputs are
 * <em>display only</em>. FoliaGUI never lets the client complete a trade
 * itself — selecting a row fires a handler and nothing more — so the inputs
 * are simply what the player is shown they must provide.
 */
public final class TradeOffer {

    private final ItemStack firstInput;
    private final ItemStack secondInput;
    private final ItemStack output;
    /**
     * What the player must genuinely hand over, when that differs from what
     * the row displays.
     * <p>
     * A row often shows a decorated stand-in -- a renamed "price token", say --
     * but matching against a display name would never succeed, because the item
     * a player drags in is plain. Setting a match item keeps the pretty display
     * while comparing against the real thing.
     */
    private ItemStack matchFirst;
    private ItemStack matchSecond;
    private boolean disabled;
    private int uses;
    private int maxUses = Integer.MAX_VALUE;
    private int xp;
    private float priceMultiplier;
    private int demand;
    private int specialPrice;

    public TradeOffer(ItemStack firstInput, ItemStack output) {
        this(firstInput, null, output);
    }

    public TradeOffer(ItemStack firstInput, ItemStack secondInput, ItemStack output) {
        this.firstInput = firstInput;
        this.secondInput = secondInput;
        this.output = output;
    }

    public ItemStack firstInput() {
        return firstInput;
    }

    public ItemStack secondInput() {
        return secondInput;
    }

    public ItemStack output() {
        return output;
    }

    public boolean hasSecondInput() {
        return secondInput != null && secondInput.getType() != Material.AIR;
    }

    /**
     * Greys the row out with the vanilla red X. Implemented by reporting the
     * trade as fully used up, which is exactly how vanilla marks a trade
     * unavailable.
     */
    public TradeOffer disabled(boolean disabled) {
        this.disabled = disabled;
        return this;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Sets the items this offer actually requires, independent of the
     * decorated stacks shown in the row.
     */
    public TradeOffer matching(ItemStack first, ItemStack second) {
        this.matchFirst = first;
        this.matchSecond = second;
        return this;
    }

    /** The real first cost: the match override if set, else the display item. */
    public ItemStack requiredFirst() {
        return matchFirst != null ? matchFirst : firstInput;
    }

    /** The real second cost, or null when the offer needs only one item. */
    public ItemStack requiredSecond() {
        return matchSecond != null ? matchSecond : secondInput;
    }

    /**
     * True when the offer is paid for by something other than items -- a Vault
     * balance, say -- so the input slots cannot be filled from the inventory.
     */
    public boolean isVirtualCost() {
        return matchFirst != null && matchFirst.getType() == Material.AIR;
    }

    /** Shows a "x of y" style depletion state, and greys out when exhausted. */
    public TradeOffer uses(int uses, int maxUses) {
        this.uses = uses;
        this.maxUses = maxUses;
        return this;
    }

    public int uses() {
        return disabled ? Math.max(1, maxUses) : uses;
    }

    public int maxUses() {
        return disabled ? Math.max(1, maxUses) : maxUses;
    }

    /**
     * How many more times this offer may be executed before it is exhausted.
     * <p>
     * Shift-clicking the result repeats a trade, so the row's own depletion
     * state -- which is how stock is advertised to the client -- has to bound
     * that repeat, or the window would offer more than the shop can supply.
     */
    public int remainingUses() {
        if (disabled) return 0;
        if (maxUses == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return Math.max(0, maxUses - uses);
    }

    public TradeOffer xp(int xp) {
        this.xp = xp;
        return this;
    }

    public int xp() {
        return xp;
    }

    public TradeOffer priceMultiplier(float multiplier) {
        this.priceMultiplier = multiplier;
        return this;
    }

    public float priceMultiplier() {
        return priceMultiplier;
    }

    public TradeOffer demand(int demand) {
        this.demand = demand;
        return this;
    }

    public int demand() {
        return demand;
    }

    public TradeOffer specialPrice(int specialPrice) {
        this.specialPrice = specialPrice;
        return this;
    }

    public int specialPrice() {
        return specialPrice;
    }
}
