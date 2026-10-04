import dev.csl.foliagui.menu.ClickType;
import dev.csl.foliagui.menu.Menu;
import dev.csl.foliagui.menu.MenuType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Proves the cursor is purely visual: it mirrors the player's inventory
 * without ever mutating it.
 * <p>
 * The real inventory must be byte-identical before and after any click, while
 * the cursor and the client-side "ghost" override reflect what the player sees.
 * An earlier version really moved the items, which destroyed them whenever a
 * repaint blanked the cursor.
 */
public class InventoryMoveTest {

    static int pass = 0, fail = 0;
    static ItemStack[] slots = new ItemStack[36];

    static void check(String what, boolean ok) {
        System.out.printf("  %-52s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        installServer();
        Object player = fakePlayer();

        Menu menu = newMenu();
        Method emulate = Class.forName("dev.csl.foliagui.packet.MenuListener")
                .getDeclaredMethod("emulatePlayerInventory",
                        org.bukkit.entity.Player.class, Menu.class, int.class, ClickType.class);
        emulate.setAccessible(true);
        Object listener = newListener();

        int rawSlot = 27;                       // -> bukkit slot 9
        slots[9] = new ItemStack(Material.DIAMOND, 16);
        menu.capturePlayerView(slots);

        // --- pick up
        emulate.invoke(listener, player, menu, rawSlot, ClickType.LEFT);
        check("left-click shows the stack on the cursor",
                menu.virtualCursor() != null && menu.virtualCursor().getAmount() == 16);
        check("real inventory is NOT modified",
                slots[9] != null && slots[9].getAmount() == 16);
        check("source slot is ghosted empty for the client",
                menu.ghostSlotIndex() == 9 && menu.ghostSlotItem() == null);

        // --- put back down
        emulate.invoke(listener, player, menu, rawSlot, ClickType.LEFT);
        check("putting down clears the cursor", menu.virtualCursor() == null);
        check("ghost override is dropped", menu.ghostSlotIndex() == -1);
        check("inventory still untouched",
                slots[9] != null && slots[9].getAmount() == 16);

        // --- right-click shows half
        emulate.invoke(listener, player, menu, rawSlot, ClickType.RIGHT);
        check("right-click shows half on the cursor",
                menu.virtualCursor() != null && menu.virtualCursor().getAmount() == 8);
        check("ghost shows the remaining half",
                menu.ghostSlotItem() != null && menu.ghostSlotItem().getAmount() == 8);
        check("inventory STILL untouched after split",
                slots[9] != null && slots[9].getAmount() == 16);

        // --- closing loses nothing, because nothing was taken
        menu.markClosed();
        check("close clears the cursor", menu.virtualCursor() == null);
        check("close clears the ghost", menu.ghostSlotIndex() == -1);
        check("inventory intact after close",
                slots[9] != null && slots[9].getAmount() == 16);

        // --- empty slot is a no-op
        Menu m2 = newMenu();
        m2.capturePlayerView(slots);
        emulate.invoke(listener, player, m2, 35, ClickType.LEFT);
        check("clicking an empty slot does nothing", m2.virtualCursor() == null);

        // --- bought goods delivered to the cursor
        Menu m3 = newMenu();
        m3.capturePlayerView(slots);
        check("fresh menu owes nothing", !m3.isCursorOwed());

        ItemStack bought = new ItemStack(Material.EMERALD, 4);
        ItemStack leftover = m3.addToCursor(bought);
        check("goods land on the cursor", m3.virtualCursor() != null
                && m3.virtualCursor().getType() == Material.EMERALD
                && m3.virtualCursor().getAmount() == 4);
        check("nothing rejected when the cursor was empty", leftover == null);
        check("cursor is flagged as owed", m3.isCursorOwed());

        // buying twice merges rather than replacing
        m3.addToCursor(new ItemStack(Material.EMERALD, 3));
        check("a second purchase merges", m3.virtualCursor().getAmount() == 7);

        // a full cursor rejects the remainder so it can fall back to the bag
        m3.virtualCursor(new ItemStack(Material.EMERALD, 64));
        ItemStack rejected = m3.addToCursor(new ItemStack(Material.EMERALD, 5));
        check("overflow is handed back", rejected != null && rejected.getAmount() == 5);

        // mismatched items never silently merge
        m3.virtualCursor(new ItemStack(Material.EMERALD, 1));
        ItemStack wrong = m3.addToCursor(new ItemStack(Material.DIAMOND, 1));
        check("different item is rejected, not merged",
                wrong != null && wrong.getType() == Material.DIAMOND);

        // a mirrored (picked-up) cursor must NOT be treated as owed
        Menu m4 = newMenu();
        m4.capturePlayerView(slots);
        emulate.invoke(listener, player, m4, 27, ClickType.LEFT);
        check("a mirrored cursor is not owed", !m4.isCursorOwed());

        System.out.println("\nPASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    static Menu newMenu() throws Exception {
        var ctor = dev.csl.foliagui.FoliaGUI.class
                .getDeclaredConstructor(org.bukkit.plugin.Plugin.class);
        ctor.setAccessible(true);
        Object gui = ctor.newInstance((org.bukkit.plugin.Plugin) null);
        var mctor = Menu.class.getDeclaredConstructor(
                dev.csl.foliagui.FoliaGUI.class, MenuType.class, Component.class);
        return mctor.newInstance(gui, MenuType.GENERIC_9X3, Component.text("t"));
    }

    static Object newListener() throws Exception {
        var c = Class.forName("dev.csl.foliagui.packet.MenuListener");
        var ctor = c.getDeclaredConstructor(dev.csl.foliagui.FoliaGUI.class);
        ctor.setAccessible(true);
        return ctor.newInstance((dev.csl.foliagui.FoliaGUI) null);
    }

    static Object fakePlayer() {
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(
                InventoryMoveTest.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItem" -> slots[(int) a[0]];
                    case "setItem" -> {
                        slots[(int) a[0]] = (ItemStack) a[1];
                        yield null;
                    }
                    default -> null;
                });
        return Proxy.newProxyInstance(InventoryMoveTest.class.getClassLoader(),
                new Class[]{org.bukkit.entity.Player.class},
                (p, m, a) -> m.getName().equals("getInventory") ? inv : null);
    }

    static void installServer() throws Exception {
        Object server = Proxy.newProxyInstance(
                InventoryMoveTest.class.getClassLoader(), new Class[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItemFactory" -> Proxy.newProxyInstance(
                            InventoryMoveTest.class.getClassLoader(),
                            new Class[]{org.bukkit.inventory.ItemFactory.class},
                            (x, y, z) -> switch (y.getName()) {
                                // ItemStack#isSimilar consults these; the stub
                                // must answer rather than return null.
                                // Both stacks are plain (no meta), so vanilla
                                // treats them as similar and merges them.
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
