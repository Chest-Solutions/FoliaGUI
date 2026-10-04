import dev.csl.foliagui.item.GuiItem;
import dev.csl.foliagui.menu.ClickContext;
import dev.csl.foliagui.menu.ClickType;
import dev.csl.foliagui.menu.Menu;
import dev.csl.foliagui.menu.MenuType;
import dev.csl.foliagui.menu.MerchantMenu;
import dev.csl.foliagui.menu.PaginatedMenu;
import dev.csl.foliagui.menu.TradeOffer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.lang.reflect.Method;

/**
 * Headless exercise of the menu logic: slot maths, click decoding and handler
 * routing, with no server or client involved.
 *
 * <pre>
 * javac -cp FoliaGUI.jar:paper-api.jar:packetevents.jar MenuLogicTest.java
 * java  -cp ...:. MenuLogicTest
 * </pre>
 */
public class MenuLogicTest {

    static int pass = 0, fail = 0;
    static dev.csl.foliagui.FoliaGUI gui;

    /**
     * Builds a FoliaGUI without going through create(), which would try to
     * register listeners against a server that isn't running.
     */
    static dev.csl.foliagui.FoliaGUI newDetachedGui() throws Exception {
        var ctor = dev.csl.foliagui.FoliaGUI.class
                .getDeclaredConstructor(org.bukkit.plugin.Plugin.class);
        ctor.setAccessible(true);
        return ctor.newInstance((org.bukkit.plugin.Plugin) null);
    }

    static void check(String what, boolean ok) {
        System.out.printf("  %-46s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        installFakeServer(); // ItemMeta needs a Server for the ItemFactory
        gui = newDetachedGui(); // an instance without packet/Bukkit listeners

        // ---- container geometry
        check("chest(3) is 27 slots", MenuType.chestRows(3).size() == 27);
        check("chest(6) is 54 slots", MenuType.chestRows(6).size() == 54);
        check("chest(99) clamps to 6 rows", MenuType.chestRows(99) == MenuType.GENERIC_9X6);
        check("chest(0) clamps to 1 row", MenuType.chestRows(0) == MenuType.GENERIC_9X1);
        check("hopper is 5 slots", MenuType.HOPPER.size() == 5);
        // Protocol ids come from the ordered minecraft:menu registry. Getting
        // these wrong silently opens the WRONG SCREEN, so pin the whole table.
        check("generic_9x3 id 2", MenuType.GENERIC_9X3.protocolId() == 2);
        check("generic_3x3 id 6", MenuType.GENERIC_3X3.protocolId() == 6);
        // Ids are the post-1.20.3 minecraft:menu ordering. Every supported
        // server is >= 1.20.6, so this table needs no version branching --
        // but a wrong value silently opens the WRONG SCREEN, so pin them all.
        check("crafter_3x3 id 7", MenuType.CRAFTER_3X3.protocolId() == 7);
        check("anvil id 8", MenuType.ANVIL.protocolId() == 8);
        check("hopper id 16", MenuType.HOPPER.protocolId() == 16);
        check("lectern id 17", MenuType.LECTERN.protocolId() == 17);
        check("loom id 18", MenuType.LOOM.protocolId() == 18);
        check("merchant id 19", MenuType.MERCHANT.protocolId() == 19);
        check("shulker box id 20", MenuType.SHULKER_BOX.protocolId() == 20);
        check("smithing id 21", MenuType.SMITHING.protocolId() == 21);
        check("stonecutter id 24", MenuType.STONECUTTER.protocolId() == 24);
        check("all 25 vanilla menus present", MenuType.values().length == 25);
        check("ids are contiguous 0..24", contiguousIds());
        check("shulker box is 27 slots", MenuType.SHULKER_BOX.size() == 27);
        check("anvil is 3 slots", MenuType.ANVIL.size() == 3);
        check("crafting is 10 slots", MenuType.CRAFTING.size() == 10);
        check("anvil reports text input", MenuType.ANVIL.hasTextInput());
        check("chest has no text input", !MenuType.GENERIC_9X3.hasTextInput());
        check("stonecutter reports buttons", MenuType.STONECUTTER.hasButtons());
        check("chest has no buttons", !MenuType.GENERIC_9X6.hasButtons());
        check("chest types report isChest", MenuType.GENERIC_9X6.isChest());
        check("hopper is not a chest", !MenuType.HOPPER.isChest());

        // ---- click decoding: (mode, button) -> ClickType
        Object pickup = wct("PICKUP");
        Object quick = wct("QUICK_MOVE");
        Object swap = wct("SWAP");
        Object thrw = wct("THROW");
        Object all = wct("PICKUP_ALL");
        check("PICKUP/0 -> LEFT", ClickType.from(cast(pickup), 0) == ClickType.LEFT);
        check("PICKUP/1 -> RIGHT", ClickType.from(cast(pickup), 1) == ClickType.RIGHT);
        check("QUICK_MOVE/0 -> SHIFT_LEFT", ClickType.from(cast(quick), 0) == ClickType.SHIFT_LEFT);
        check("QUICK_MOVE/1 -> SHIFT_RIGHT", ClickType.from(cast(quick), 1) == ClickType.SHIFT_RIGHT);
        check("SWAP/40 -> OFFHAND_SWAP", ClickType.from(cast(swap), 40) == ClickType.OFFHAND_SWAP);
        check("SWAP/3 -> NUMBER_KEY", ClickType.from(cast(swap), 3) == ClickType.NUMBER_KEY);
        check("THROW/0 -> DROP", ClickType.from(cast(thrw), 0) == ClickType.DROP);
        check("PICKUP_ALL -> DOUBLE_CLICK", ClickType.from(cast(all), 0) == ClickType.DOUBLE_CLICK);
        check("null mode -> OTHER", ClickType.from(null, 0) == ClickType.OTHER);
        check("SHIFT_LEFT is shift and left",
                ClickType.SHIFT_LEFT.isShift() && ClickType.SHIFT_LEFT.isLeft());

        // ---- slot maths
        Menu m = gui.menu(3, Component.text("Test"));
        check("menu reports 27 slots", m.size() == 27);
        m.set(4, GuiItem.of(Material.DIAMOND));
        check("set/get slot 4", m.get(4) != null && m.get(4).stack().getType() == Material.DIAMOND);
        m.set(2, 1, GuiItem.of(Material.EMERALD)); // column 2, row 1 -> slot 11
        check("set(col=2,row=1) maps to slot 11",
                m.get(11) != null && m.get(11).stack().getType() == Material.EMERALD);
        m.set(999, GuiItem.of(Material.STONE));
        check("out-of-range set is ignored", m.get(999) == null);

        // ---- handler routing
        final int[] slotHits = {0};
        final int[] globalHits = {0};
        final int[] seenSlot = {-1};
        m.set(7, new GuiItem(new org.bukkit.inventory.ItemStack(Material.STONE), ctx -> {
            slotHits[0]++;
            seenSlot[0] = ctx.slot();
        }));
        m.onClick(ctx -> globalHits[0]++);

        click(m, 7, ClickType.LEFT, 0);
        check("slot handler fired", slotHits[0] == 1);
        check("global handler fired", globalHits[0] == 1);
        check("handler saw slot 7", seenSlot[0] == 7);

        click(m, 3, ClickType.LEFT, 0);
        check("global fires on empty slot", globalHits[0] == 2);
        check("other slot's handler not fired", slotHits[0] == 1);

        // ---- player inventory is isolated unless opted in
        int before = globalHits[0];
        click(m, 50, ClickType.LEFT, 0);
        check("player-inventory click ignored by default", globalHits[0] == before);

        final boolean[] playerInv = {false};
        m.onPlayerInventoryClick(ctx -> playerInv[0] = true);
        click(m, 50, ClickType.LEFT, 0);
        check("player-inventory handler fires when opted in", playerInv[0]);

        // ---- close request
        Menu m2 = gui.menu(1, Component.text("x"));
        m2.set(0, new GuiItem(new org.bukkit.inventory.ItemStack(Material.STONE),
                ClickContext::close));
        ClickContext ctx = click(m2, 0, ClickType.LEFT, 0);
        check("ctx.close() sets closeRequested", ctx.isCloseRequested());

        // ---- decorators never clobber real content
        Menu m3 = gui.menu(3, Component.text("y"));
        m3.set(13, GuiItem.of(Material.BEACON));
        m3.fill(Material.GRAY_STAINED_GLASS_PANE);
        check("fill preserves existing item", m3.get(13).stack().getType() == Material.BEACON);
        check("fill populates empty slots", m3.get(0) != null);

        Menu m4 = gui.menu(3, Component.text("z"));
        m4.border(Material.BLACK_STAINED_GLASS_PANE);
        check("border sets first slot", m4.get(0) != null);
        check("border sets last slot", m4.get(26) != null);
        check("border leaves centre empty", m4.get(13) == null);

        // ---- merchant offers
        MerchantMenu mm = gui.merchant(Component.text("Trader"));
        check("merchant starts empty", mm.offers().isEmpty());
        org.bukkit.inventory.ItemStack cost = new org.bukkit.inventory.ItemStack(Material.EMERALD, 5);
        org.bukkit.inventory.ItemStack out = new org.bukkit.inventory.ItemStack(Material.DIAMOND);
        mm.addOffer(new TradeOffer(cost, out));
        check("addOffer appends", mm.offers().size() == 1);
        check("offer keeps first input", mm.offers().get(0).firstInput().getAmount() == 5);
        check("single-input offer has no second", !mm.offers().get(0).hasSecondInput());

        TradeOffer two = new TradeOffer(cost,
                new org.bukkit.inventory.ItemStack(Material.GOLD_INGOT), out);
        check("two-input offer reports second", two.hasSecondInput());
        TradeOffer nullSecond = new TradeOffer(cost, null, out);
        check("null second input handled", !nullSecond.hasSecondInput());

        // disabled == uses saturated, which is how vanilla draws the red X
        TradeOffer off = new TradeOffer(cost, out).uses(0, 10).disabled(true);
        check("disabled offer saturates uses", off.uses() == off.maxUses());
        TradeOffer live = new TradeOffer(cost, out).uses(3, 10);
        check("live offer keeps uses 3/10", live.uses() == 3 && live.maxUses() == 10);
        check("live offer is not disabled", !live.isDisabled());

        final int[] tradeIdx = {-1};
        mm.onTrade((pl, i) -> tradeIdx[0] = i);
        mm.fireTradeInternal(null, 0);
        check("trade handler receives index", tradeIdx[0] == 0);

        // --- vanilla staging: the output follows whatever is in the inputs
        MerchantMenu sm = gui.merchant(Component.text("stage"));
        org.bukkit.inventory.ItemStack grass = new org.bukkit.inventory.ItemStack(Material.DIRT, 32);
        org.bukkit.inventory.ItemStack emerald = new org.bukkit.inventory.ItemStack(Material.EMERALD, 1);
        sm.addOffer(new TradeOffer(grass, emerald));

        check("nothing staged initially", !sm.hasStage());
        check("empty inputs produce no output", sm.stagedOutput() == null);

        // exact cost -> the result appears without picking a row
        sm.setInput(0, new org.bukkit.inventory.ItemStack(Material.DIRT, 32));
        check("matching input produces the output", sm.hasStage()
                && sm.stagedOutput() != null
                && sm.stagedOutput().getType() == Material.EMERALD);
        check("matched row is reported", sm.stagedIndex() == 0);

        // more than enough still matches, as vanilla allows
        sm.setInput(0, new org.bukkit.inventory.ItemStack(Material.DIRT, 64));
        check("a larger stack still matches", sm.hasStage());

        // too few -> no output
        sm.setInput(0, new org.bukkit.inventory.ItemStack(Material.DIRT, 8));
        check("insufficient input clears the output", !sm.hasStage()
                && sm.stagedOutput() == null);

        // wrong item -> no output
        sm.setInput(0, new org.bukkit.inventory.ItemStack(Material.STONE, 64));
        check("conflicting input clears the output", !sm.hasStage());

        // taking the inputs back returns exactly what was put in
        sm.setInput(0, new org.bukkit.inventory.ItemStack(Material.DIRT, 32));
        java.util.List<org.bukkit.inventory.ItemStack> back = sm.takeInputs();
        check("takeInputs returns the staged items", back.size() == 1
                && back.get(0).getType() == Material.DIRT
                && back.get(0).getAmount() == 32);
        check("inputs are empty after taking", sm.input(0) == null);
        check("output clears once inputs are gone", !sm.hasStage());

        // two-input offers match in either slot order
        MerchantMenu pair = gui.merchant(Component.text("two"));
        pair.addOffer(new TradeOffer(
                new org.bukkit.inventory.ItemStack(Material.DIRT, 4),
                new org.bukkit.inventory.ItemStack(Material.STONE, 2),
                emerald));
        pair.setInput(0, new org.bukkit.inventory.ItemStack(Material.STONE, 2));
        pair.setInput(1, new org.bukkit.inventory.ItemStack(Material.DIRT, 4));
        check("two-input offer matches in either order", pair.hasStage());

        pair.setInput(1, null);
        check("removing one input breaks the match", !pair.hasStage());

        // closing must not silently keep the player's goods
        pair.setInput(0, new org.bukkit.inventory.ItemStack(Material.DIRT, 4));
        pair.markClosed();
        check("closing clears the stage", !pair.hasStage());

        mm.setOffers(java.util.List.of(new TradeOffer(cost, out), new TradeOffer(cost, out)));
        check("setOffers replaces list", mm.offers().size() == 2);
        mm.clearOffers();
        check("clearOffers empties list", mm.offers().isEmpty());
        check("merchant not open before open()", !mm.isOpen());
        check("closed merchant has windowId -1", mm.windowId() == -1);

        // ---- cursor plumbing
        Menu cm = gui.menu(1, Component.text("c"));
        final ClickContext[] captured = {null};
        cm.set(0, new GuiItem(new org.bukkit.inventory.ItemStack(Material.STONE),
                c -> captured[0] = c));

        org.bukkit.inventory.ItemStack held =
                new org.bukkit.inventory.ItemStack(Material.DIAMOND, 3);
        clickWithCursor(cm, 0, ClickType.LEFT, 0, held);
        check("handler sees the cursor item",
                captured[0].cursor() != null
                        && captured[0].cursor().getType() == Material.DIAMOND);
        check("cursor amount preserved", captured[0].cursor().getAmount() == 3);
        check("hasCursor true when holding", captured[0].hasCursor());

        clickWithCursor(cm, 0, ClickType.LEFT, 0, null);
        check("hasCursor false when empty", !captured[0].hasCursor());
        check("cursor() null when empty", captured[0].cursor() == null);
        check("no cursor change requested by default", !captured[0].isCursorChanged());

        Menu cm2 = gui.menu(1, Component.text("c2"));
        cm2.set(0, new GuiItem(new org.bukkit.inventory.ItemStack(Material.STONE),
                c -> c.setCursor(new org.bukkit.inventory.ItemStack(Material.EMERALD, 7))));
        ClickContext exported = clickWithCursor(cm2, 0, ClickType.LEFT, 0, null);
        check("setCursor flags a change", exported.isCursorChanged());
        check("pendingCursor carries the stack",
                exported.pendingCursor() != null
                        && exported.pendingCursor().getType() == Material.EMERALD
                        && exported.pendingCursor().getAmount() == 7);

        Menu cm3 = gui.menu(1, Component.text("c3"));
        cm3.set(0, new GuiItem(new org.bukkit.inventory.ItemStack(Material.STONE),
                ClickContext::clearCursor));
        ClickContext cleared = clickWithCursor(cm3, 0, ClickType.LEFT, 0, held);
        check("clearCursor flags a change", cleared.isCursorChanged());
        check("clearCursor sets null", cleared.pendingCursor() == null);

        // ---- pagination
        java.util.List<String> data = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) data.add("item" + i);
        PaginatedMenu<String> pm = new PaginatedMenu<>(
                gui, MenuType.GENERIC_9X3, Component.text("p"), () -> data);
        pm.renderer(s -> GuiItem.of(Material.PAPER, Component.text(s)));
        check("9x3 interior holds 7 per page", pm.pageCount() == 5);
        pm.page(0);
        check("page 0 fills first content slot", pm.get(10) != null);
        check("page 0 leaves border empty", pm.get(0) == null);
        pm.nextPage();
        check("nextPage advances", pm.page() == 1);
        pm.page(999);
        check("page clamps to last", pm.page() == pm.pageCount() - 1);
        pm.page(-5);
        check("page clamps to zero", pm.page() == 0);

        java.util.List<String> empty = new java.util.ArrayList<>();
        PaginatedMenu<String> pe = new PaginatedMenu<>(
                gui, MenuType.GENERIC_9X3, Component.text("e"), () -> empty);
        pe.emptyText(Component.text("Nothing here"));
        pe.page(0);
        check("empty list still reports one page", pe.pageCount() == 1);
        check("empty text is drawn", pe.get(10) != null);

        // list is read live, so growth is picked up without rebuilding
        data.add("item30");
        check("page count follows a live list", pm.pageCount() == 5);
        for (int i = 0; i < 20; i++) data.add("extra" + i);
        check("page count grows with the list", pm.pageCount() == 8);

        // ---- interactive menu callbacks
        Menu anvil = gui.menu(MenuType.ANVIL, Component.text("a"));
        final String[] typed = {null};
        anvil.onTextInput((pl, text) -> typed[0] = text);
        anvil.fireTextInputInternal(null, "hello");
        check("text input handler receives text", "hello".equals(typed[0]));

        Menu cutter = gui.menu(MenuType.STONECUTTER, Component.text("s"));
        final int[] pressed = {-1};
        cutter.onButton((pl, id) -> pressed[0] = id);
        cutter.fireButtonInternal(null, 3);
        check("button handler receives id", pressed[0] == 3);

        Menu noHandlers = gui.menu(3, Component.text("n"));
        noHandlers.fireTextInputInternal(null, "x");
        noHandlers.fireButtonInternal(null, 1);
        check("missing handlers are no-ops", true);

        // ---- decorator must survive repaints (chrome was being wiped)
        java.util.List<String> deco = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) deco.add("d" + i);
        PaginatedMenu<String> pd = new PaginatedMenu<>(
                gui, MenuType.GENERIC_9X5, Component.text("d"), () -> deco);
        pd.renderer(s -> GuiItem.of(Material.PAPER, Component.text(s)));
        pd.decorator(() -> pd.set(4, GuiItem.of(Material.BEACON, Component.text("header"))));
        pd.page(0);
        check("decorator drawn on first page", pd.get(4) != null
                && pd.get(4).stack().getType() == Material.BEACON);
        pd.nextPage();
        check("decorator survives a page turn", pd.get(4) != null
                && pd.get(4).stack().getType() == Material.BEACON);
        pd.reload();
        check("decorator survives reload", pd.get(4) != null
                && pd.get(4).stack().getType() == Material.BEACON);
        check("content still drawn alongside chrome", pd.get(10) != null);

        // ---- virtual cursor: the server has no container for a packet
        // window, so FoliaGUI must track the cursor itself
        Menu vc = gui.menu(3, Component.text("vc"));
        check("virtual cursor starts empty", vc.virtualCursor() == null);
        vc.virtualCursor(new org.bukkit.inventory.ItemStack(Material.GOLD_INGOT, 4));
        check("virtual cursor stores a stack", vc.virtualCursor() != null
                && vc.virtualCursor().getType() == Material.GOLD_INGOT
                && vc.virtualCursor().getAmount() == 4);
        ClickContext vctx = clickWithCursor(vc, 0, ClickType.LEFT, 0, vc.virtualCursor());
        check("handler sees the tracked cursor", vctx.hasCursor()
                && vctx.cursor().getType() == Material.GOLD_INGOT);
        vc.virtualCursor(new org.bukkit.inventory.ItemStack(Material.AIR));
        check("AIR clears the virtual cursor", vc.virtualCursor() == null);
        vc.virtualCursor(new org.bukkit.inventory.ItemStack(Material.DIAMOND));
        vc.markClosed();
        check("closing clears the cursor", vc.virtualCursor() == null);

        // ---- input slots behave like real container slots
        Menu isl = gui.menu(3, Component.text("in"));
        isl.inputSlots(13, 12);
        check("slot marked as input", isl.isInputSlot(13));
        check("other slots are not inputs", !isl.isInputSlot(5));
        check("empty input reads null", isl.inputItem(13) == null);
        isl.set(13, new GuiItem(new org.bukkit.inventory.ItemStack(Material.GOLD_INGOT, 5)));
        check("input slot reports its stack", isl.inputItem(13) != null
                && isl.inputItem(13).getAmount() == 5);
        // filler must NOT occupy an input slot, or nothing can be dropped in
        isl.fill(Material.BLACK_STAINED_GLASS_PANE);
        check("fill leaves empty input slots free", isl.get(12) == null);
        check("fill preserves a filled input slot",
                isl.inputItem(13) != null
                        && isl.inputItem(13).getType() == Material.GOLD_INGOT);
        check("fill still fills ordinary slots", isl.get(5) != null);

        // A redraw must not destroy what the player put in an input slot.
        isl.clear();
        check("clear() PRESERVES input slot contents",
                isl.inputItem(13) != null
                        && isl.inputItem(13).getType() == Material.GOLD_INGOT);
        check("clear() still wipes ordinary slots", isl.get(5) == null);
        isl.clearAll();
        check("clearAll() wipes input slots too", isl.inputItem(13) == null);
        check("clearAll() unregisters input slots", !isl.isInputSlot(13));

        final int[] changed = {-1};
        isl.inputSlots(13, 12);
        isl.onInputChange(s -> changed[0] = s);
        isl.fireInputChangeInternal(12);
        check("input change handler fires", changed[0] == 12);

        // ---- player inventory must stay usable by default
        Menu pi = gui.menu(3, Component.text("pi"));
        check("player inventory unlocked by default", !pi.isPlayerInventoryLocked());
        pi.lockPlayerInventory(true);
        check("lockPlayerInventory(true) locks it", pi.isPlayerInventoryLocked());
        pi.lockPlayerInventory(false);
        check("lockPlayerInventory(false) unlocks", !pi.isPlayerInventoryLocked());

        // observing player-inventory clicks must NOT lock the inventory
        Menu obs = gui.menu(3, Component.text("obs"));
        final boolean[] observed = {false};
        obs.onPlayerInventoryClick(c -> observed[0] = true);
        check("observer does not lock inventory", !obs.isPlayerInventoryLocked());
        clickWithCursor(obs, 50, ClickType.LEFT, 0, null);
        check("observer still receives the click", observed[0]);

        // ---- state ids must advance so the client accepts updates
        Menu m5 = gui.menu(1, Component.text("s"));
        int s1 = m5.nextStateId();
        int s2 = m5.nextStateId();
        check("stateId increments monotonically", s2 == s1 + 1);

        System.out.println("\nPASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    /**
     * Bukkit.getItemFactory() is static state normally supplied by a running
     * server. A dynamic proxy is enough for meta-less filler items: any
     * ItemMeta request simply returns null, which GuiItem.build() tolerates.
     */
    static void installFakeServer() throws Exception {
        java.lang.reflect.Field f = org.bukkit.Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        Object server = java.lang.reflect.Proxy.newProxyInstance(
                MenuLogicTest.class.getClassLoader(),
                new Class[]{org.bukkit.Server.class},
                (proxy, method, a) -> {
                    if (method.getName().equals("getItemFactory")) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                MenuLogicTest.class.getClassLoader(),
                                new Class[]{org.bukkit.inventory.ItemFactory.class},
                                (p2, m2, a2) -> switch (m2.getName()) {
                                    // ItemStack#isSimilar consults the factory;
                                    // plain stacks have no meta, so "equal".
                                    case "equals" -> Boolean.TRUE;
                                    case "isApplicable" -> Boolean.FALSE;
                                    case "hashCode" -> 0;
                                    default -> null;
                                });
                    }
                    if (method.getName().equals("getLogger")) {
                        return java.util.logging.Logger.getLogger("test");
                    }
                    return null;
                });
        f.set(null, server);
    }

    /** Every protocol id must be unique and cover 0..24 with no gaps. */
    static boolean contiguousIds() {
        boolean[] seen = new boolean[25];
        for (MenuType type : MenuType.values()) {
            int id = type.protocolId();
            if (id < 0 || id > 24 || seen[id]) return false;
            seen[id] = true;
        }
        for (boolean b : seen) if (!b) return false;
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object wct(String name) throws Exception {
        Class<?> c = Class.forName(
                "com.github.retrooper.packetevents.wrapper.play.client."
                        + "WrapperPlayClientClickWindow$WindowClickType");
        return Enum.valueOf((Class<Enum>) c, name);
    }

    @SuppressWarnings("unchecked")
    static com.github.retrooper.packetevents.wrapper.play.client
            .WrapperPlayClientClickWindow.WindowClickType cast(Object o) {
        return (com.github.retrooper.packetevents.wrapper.play.client
                .WrapperPlayClientClickWindow.WindowClickType) o;
    }

    static ClickContext clickWithCursor(Menu m, int slot, ClickType type, int button,
                                        org.bukkit.inventory.ItemStack cursor) throws Exception {
        Method h = Menu.class.getMethod("handleClick",
                org.bukkit.entity.Player.class, int.class, ClickType.class, int.class,
                org.bukkit.inventory.ItemStack.class);
        return (ClickContext) h.invoke(m, null, slot, type, button, cursor);
    }

    static ClickContext click(Menu m, int slot, ClickType type, int button) throws Exception {
        Method h = Menu.class.getMethod("handleClick",
                org.bukkit.entity.Player.class, int.class, ClickType.class, int.class);
        return (ClickContext) h.invoke(m, null, slot, type, button);
    }
}
