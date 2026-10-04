package dev.csl.foliagui.menu;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Works out how many times a shift-clicked merchant trade should run.
 * <p>
 * Vanilla's shift-click is not "the same trade, faster": it settles as many
 * executions as the player can pay for and carry, in one go. Getting that
 * number right is the whole of the feature, so it lives here as pure
 * arithmetic over an inventory snapshot — no {@code Player}, no server — which
 * is what makes it testable without a running Minecraft.
 */
public final class TradeBatch {

    private TradeBatch() {
    }

    /**
     * The number of executions to run.
     *
     * @param contents    the player's storage slots; not modified
     * @param staged      how much of each cost is already in the input slots,
     *                    and therefore already out of {@code contents}
     * @param stagedLots  how many whole executions those slots already pay
     *                    for. Vanilla accepts a surplus, so this is often more
     *                    than one, and only the lots beyond it need funding
     *                    from the inventory.
     * @param costs       the real per-execution cost items (may contain nulls)
     * @param output      the per-execution result
     * @param cap         the row's remaining uses, i.e. advertised stock
     * @param virtualCost true when the price is not an item at all
     * @return at least 1, never more than {@code cap}
     */
    public static int size(ItemStack[] contents, int[] staged, int stagedLots,
                           ItemStack[] costs, ItemStack output, int cap,
                           boolean virtualCost) {
        if (cap <= 0) return 1;
        if (output == null || isAir(output.getType()) || output.getAmount() <= 0) return 1;

        // Bound the search before simulating, so an admin shop advertising
        // unlimited uses doesn't send the loop off to Integer.MAX_VALUE.
        int spaceBound = (contents.length * Math.max(1, output.getMaxStackSize()))
                / output.getAmount();
        int limit = Math.min(cap, Math.max(1, spaceBound));

        if (!virtualCost) {
            for (int c = 0; c < costs.length; c++) {
                ItemStack cost = costs[c];
                if (cost == null || isAir(cost.getType()) || cost.getAmount() <= 0) continue;
                int owned = count(contents, cost.getType())
                        + (staged != null && c < staged.length ? staged[c] : 0);
                limit = Math.min(limit, owned / cost.getAmount());
            }
        }
        if (limit <= 1) return 1;

        // "Does n fit" is monotone in n, so binary search rather than a linear
        // walk -- a 64-deep search is 6 simulations, not 64.
        int low = 1;
        int high = limit;
        int best = 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            if (fits(contents, costs, output, mid, stagedLots, virtualCost)) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    /**
     * True when {@code count} executions fit once their cost has left the
     * inventory.
     * <p>
     * Simulated on a copy rather than estimated: paying frees slots that the
     * result then occupies, so counting free space up front would refuse
     * batches that in fact fit.
     */
    private static boolean fits(ItemStack[] contents, ItemStack[] costs, ItemStack output,
                                int count, int stagedLots, boolean virtualCost) {
        ItemStack[] sim = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            sim[i] = contents[i] == null ? null : contents[i].clone();
        }
        if (!virtualCost) {
            for (ItemStack cost : costs) {
                if (cost == null || isAir(cost.getType()) || cost.getAmount() <= 0) continue;
                // Whatever the input slots already pay for is gone from the
                // inventory; only the lots beyond that come out here.
                int remove = cost.getAmount() * Math.max(0, count - stagedLots);
                for (int i = 0; i < sim.length && remove > 0; i++) {
                    if (sim[i] == null || sim[i].getType() != cost.getType()) continue;
                    int take = Math.min(remove, sim[i].getAmount());
                    sim[i].setAmount(sim[i].getAmount() - take);
                    remove -= take;
                    if (sim[i].getAmount() <= 0) sim[i] = null;
                }
                if (remove > 0) return false;
            }
        }
        int give = output.getAmount() * count;
        int max = Math.max(1, output.getMaxStackSize());
        for (int i = 0; i < sim.length && give > 0; i++) {
            if (sim[i] == null || isAir(sim[i].getType())) {
                give -= Math.min(give, max);
            } else if (sim[i].getType() == output.getType()) {
                give -= Math.min(give, Math.max(0, max - sim[i].getAmount()));
            }
        }
        return give <= 0;
    }

    private static int count(ItemStack[] contents, Material type) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() == type) total += stack.getAmount();
        }
        return total;
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }
}
