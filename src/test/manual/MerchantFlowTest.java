import dev.csl.foliagui.menu.MerchantMenu;
import dev.csl.foliagui.menu.TradeOffer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Walks the merchant flow the way a player does: pick a row, then click the
 * result. Catches the case where staging state is consumed before the trade
 * handler reads it.
 */
public class MerchantFlowTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.printf("  %-52s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        installServer();

        var ctor = dev.csl.foliagui.FoliaGUI.class
                .getDeclaredConstructor(org.bukkit.plugin.Plugin.class);
        ctor.setAccessible(true);
        Object gui = ctor.newInstance((org.bukkit.plugin.Plugin) null);

        var mctor = MerchantMenu.class.getDeclaredConstructor(
                dev.csl.foliagui.FoliaGUI.class, Component.class);
        MerchantMenu menu = mctor.newInstance(gui, Component.text("t"));

        ItemStack cost = new ItemStack(Material.DIRT, 32);
        ItemStack result = new ItemStack(Material.EMERALD, 1);
        menu.addOffer(new TradeOffer(cost, result));

        // Stage by filling the input, as picking a row does.
        menu.setInput(0, new ItemStack(Material.DIRT, 32));
        check("input produces a staged output", menu.hasStage());
        check("staged index is the matching row", menu.stagedIndex() == 0);

        // The listener reads stagedIndex(), then the handler runs. If the
        // handler empties the inputs first, the index must already be captured.
        final int[] seen = {-1};
        final boolean[] hadStageInside = {false};
        menu.onTrade((pl, index) -> {
            seen[0] = index;
            // FoliaShops does exactly this: consume the staged cost.
            menu.takeInputs();
            hadStageInside[0] = index >= 0;
        });

        int captured = menu.stagedIndex();
        menu.fireTradeInternal(null, captured);

        check("trade handler receives a valid index", seen[0] == 0);
        check("index stays valid even after inputs are taken", hadStageInside[0]);
        check("inputs are empty after the trade", menu.input(0) == null);
        check("stage clears once inputs are consumed", !menu.hasStage());

        // Re-staging for a repeat purchase must work.
        menu.setInput(0, new ItemStack(Material.DIRT, 32));
        check("can stage again after a trade", menu.hasStage());

        // A stale index must not be trusted once the stage is gone.
        menu.takeInputs();
        check("stagedIndex is -1 with nothing staged", menu.stagedIndex() == -1);

        // --- refreshing the offer list must not strand the staged match
        MerchantMenu live = mctor.newInstance(gui, Component.text("live"));
        live.addOffer(new TradeOffer(cost, result));
        live.setInput(0, new ItemStack(Material.DIRT, 32));
        check("staged before an offer refresh", live.hasStage());

        // FoliaShops re-sends offers after every trade (stock may have moved).
        live.setOffers(java.util.List.of(new TradeOffer(cost, result)));
        check("still staged after setOffers", live.hasStage());
        check("index still resolves after setOffers",
                live.stagedIndex() >= 0 && live.stagedIndex() < live.offers().size());

        // An offer list that no longer contains the match must clear it,
        // rather than leaving an index pointing at the wrong row.
        live.setOffers(java.util.List.of(new TradeOffer(
                new ItemStack(Material.STONE, 5), result)));
        check("mismatched refresh clears the stage", !live.hasStage());
        check("stale index is dropped", live.stagedIndex() == -1);

        // --- a decorated row must still be paid with a plain item.
        // This is what broke the flow: rows show a renamed "price token", and
        // isSimilar() against a plain dragged stack never matched, so no
        // output appeared and the result click did nothing.
        MerchantMenu deco = mctor.newInstance(gui, Component.text("deco"));
        ItemStack pretty = new ItemStack(Material.DIRT, 32);
        var meta = pretty.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("32x Dirt"));
            pretty.setItemMeta(meta);
        }
        TradeOffer decorated = new TradeOffer(pretty, result);
        decorated.matching(new ItemStack(Material.DIRT, 32), null);
        deco.addOffer(decorated);

        deco.setInput(0, new ItemStack(Material.DIRT, 32));
        check("plain item satisfies a decorated row", deco.hasStage());
        check("decorated row yields its output",
                deco.stagedOutput() != null
                        && deco.stagedOutput().getType() == Material.EMERALD);

        deco.setInput(0, new ItemStack(Material.DIRT, 8));
        check("too few still fails on a decorated row", !deco.hasStage());

        deco.setInput(0, new ItemStack(Material.STONE, 64));
        check("wrong item still fails on a decorated row", !deco.hasStage());

        // --- money-priced offers have no item cost at all
        MerchantMenu money = mctor.newInstance(gui, Component.text("money"));
        TradeOffer priced = new TradeOffer(
                new ItemStack(Material.PAPER, 1), result);
        priced.matching(new ItemStack(Material.AIR), null);
        money.addOffer(priced);

        check("money offer is flagged virtual", priced.isVirtualCost());
        // A cost of "nothing" is satisfied by empty slots, so a money-priced
        // row is ready to buy the moment the window opens -- the same as a
        // vanilla trade the player can already afford.
        check("money row is immediately buyable", money.hasStage());
        check("money row reports its index", money.stagedIndex() == 0);
        check("money row shows its output", money.stagedOutput() != null);

        // Putting an unrelated item in a slot must break the match, or the
        // player could be charged while holding something irrelevant.
        money.setInput(0, new ItemStack(Material.STONE, 1));
        check("stray input blocks a money row", !money.hasStage());
        money.setInput(0, null);
        check("clearing the slot restores it", money.hasStage());

        // --- phantoms: stand-ins the player never owned
        MerchantMenu ph = mctor.newInstance(gui, Component.text("ph"));
        TradeOffer virt = new TradeOffer(new ItemStack(Material.PAPER, 1), result);
        virt.matching(new ItemStack(Material.AIR), null);
        ph.addOffer(virt);

        // The listener selects the row, then seeds the phantom -- do both.
        ph.selectRow(0);
        ph.setPhantomInput(0, new ItemStack(Material.PAPER, 1));
        check("phantom fills the input slot", ph.input(0) != null);
        check("slot is flagged phantom", ph.isPhantom(0));
        check("phantom still stages the output", ph.hasStage());

        // The critical property: a phantom must never be handed back, or the
        // player walks away with an item that was only ever a label.
        java.util.List<ItemStack> returned = ph.takeInputs();
        check("phantom is NOT returned to the player", returned.isEmpty());
        check("phantom flag clears with the slot", !ph.isPhantom(0));

        // A real item in the same slot must still be returned.
        ph.setInput(0, new ItemStack(Material.DIRT, 5));
        check("real input is not a phantom", !ph.isPhantom(0));
        check("real input IS returned",
                ph.takeInputs().size() == 1);

        // ------------------------------------------------ shift-click batches
        System.out.println("\nbulk (shift-click) trades");
        MerchantMenu bulk = mctor.newInstance(gui, Component.text("bulk"));
        bulk.addOffer(new TradeOffer(new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.WHITE_WOOL, 1)));

        int[] bulkSeen = {-1, -1};
        bulk.onBulkTrade((pl, idx, n) -> {
            bulkSeen[0] = idx;
            bulkSeen[1] = n;
            check("isBulk() is true inside a bulk handler", bulk.isBulk());
        });
        bulk.setInput(0, new ItemStack(Material.EMERALD, 1));
        bulk.addBulkCost(new ItemStack(Material.EMERALD, 31));
        bulk.fireBulkTradeInternal(null, 0, 32);
        check("bulk handler receives the row", bulkSeen[0] == 0);
        check("bulk handler receives the count", bulkSeen[1] == 32);
        check("isBulk() is false once the handler returns", !bulk.isBulk());

        // The surplus is the player's property and must come back with the
        // visible slots, not be silently swallowed.
        MerchantMenu ret = mctor.newInstance(gui, Component.text("ret"));
        ret.addOffer(new TradeOffer(new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.WHITE_WOOL, 1)));
        ret.setInput(0, new ItemStack(Material.EMERALD, 1));
        ret.addBulkCost(new ItemStack(Material.EMERALD, 31));
        var bulkReturned = ret.takeInputs();
        int total = bulkReturned.stream().mapToInt(ItemStack::getAmount).sum();
        check("takeInputs returns staged + batched cost", total == 32);
        check("batched cost is not returned twice", ret.takeInputs().isEmpty());

        MerchantMenu closed = mctor.newInstance(gui, Component.text("c"));
        closed.addBulkCost(new ItemStack(Material.EMERALD, 10));
        closed.clearStage();
        check("clearStage drops the batched cost", closed.takeInputs().isEmpty());

        // Falling back keeps an older menu working rather than multiplying a
        // trade its handler never agreed to.
        MerchantMenu legacy = mctor.newInstance(gui, Component.text("l"));
        legacy.addOffer(new TradeOffer(new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.WHITE_WOOL, 1)));
        int[] calls = {0};
        legacy.onTrade((pl, idx) -> calls[0]++);
        legacy.fireBulkTradeInternal(null, 0, 20);
        check("no bulk handler falls back to a single trade", calls[0] == 1);

        // remainingUses is what bounds a batch against advertised stock.
        TradeOffer stocked = new TradeOffer(new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.WHITE_WOOL, 1)).uses(3, 10);
        check("remainingUses is maxUses - uses", stocked.remainingUses() == 7);
        check("an unlimited row reports unlimited uses",
                new TradeOffer(new ItemStack(Material.EMERALD, 1),
                        new ItemStack(Material.WHITE_WOOL, 1))
                        .uses(0, Integer.MAX_VALUE).remainingUses() == Integer.MAX_VALUE);
        check("a disabled row has no uses left", stocked.disabled(true).remainingUses() == 0);

        // ------------------------------------------- surplus in the input slot
        System.out.println("\nvanilla accepts more than the cost, never less");
        MerchantMenu sur = mctor.newInstance(gui, Component.text("sur"));
        sur.addOffer(new TradeOffer(new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.WHITE_WOOL, 1)));

        sur.setInput(0, new ItemStack(Material.EMERALD, 64));
        check("a full stack on a 1-cost row still stages", sur.hasStage());
        check("output is one lot, not the whole stack",
                sur.stagedOutput().getAmount() == 1);
        check("the slot pays for 64 lots", sur.stagedLots() == 64);

        var oneLot = sur.consumeCost(1);
        check("only one lot is consumed",
                oneLot.stream().mapToInt(ItemStack::getAmount).sum() == 1);
        check("the surplus stays in the slot", sur.input(0).getAmount() == 63);
        check("and still stages a result", sur.hasStage());
        check("with the remaining lots recounted", sur.stagedLots() == 63);

        var tenLots = sur.consumeCost(10);
        check("a batch consumes exactly its cost",
                tenLots.stream().mapToInt(ItemStack::getAmount).sum() == 10);
        check("leaving the rest behind", sur.input(0).getAmount() == 53);

        // Less than the cost must still be refused.
        MerchantMenu few = mctor.newInstance(gui, Component.text("few"));
        few.addOffer(new TradeOffer(new ItemStack(Material.EMERALD, 5),
                new ItemStack(Material.WHITE_WOOL, 1)));
        few.setInput(0, new ItemStack(Material.EMERALD, 4));
        check("less than the cost never stages", !few.hasStage());
        check("and pays for no lots", few.stagedLots() == 0);

        // A non-exact multiple pays for the whole lots only.
        few.setInput(0, new ItemStack(Material.EMERALD, 12));
        check("12 on a 5-cost row stages", few.hasStage());
        check("12 on a 5-cost row is two lots", few.stagedLots() == 2);
        var twoLots = few.consumeCost(2);
        check("two lots costs ten",
                twoLots.stream().mapToInt(ItemStack::getAmount).sum() == 10);
        check("the odd remainder is left alone", few.input(0).getAmount() == 2);
        check("a remainder under one lot stops staging", !few.hasStage());

        // consumeCost must still surrender the batched surplus it holds.
        MerchantMenu both = mctor.newInstance(gui, Component.text("both"));
        both.addOffer(new TradeOffer(new ItemStack(Material.EMERALD, 1),
                new ItemStack(Material.WHITE_WOOL, 1)));
        both.setInput(0, new ItemStack(Material.EMERALD, 3));
        both.addBulkCost(new ItemStack(Material.EMERALD, 7));
        var mixed = both.consumeCost(3);
        check("consumeCost returns slot cost plus batched surplus",
                mixed.stream().mapToInt(ItemStack::getAmount).sum() == 10);

        System.out.println("\nPASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void installServer() throws Exception {
        Object server = Proxy.newProxyInstance(
                MerchantFlowTest.class.getClassLoader(), new Class[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItemFactory" -> Proxy.newProxyInstance(
                            MerchantFlowTest.class.getClassLoader(),
                            new Class[]{org.bukkit.inventory.ItemFactory.class},
                            (x, y, z) -> switch (y.getName()) {
                                case "equals" -> Boolean.TRUE;
                                case "isApplicable" -> Boolean.FALSE;
                                case "hashCode" -> 0;
                                default -> null;
                            });
                    case "getLogger" -> java.util.logging.Logger.getLogger("test");
                    default -> null;
                });
        Field f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);
    }
}
