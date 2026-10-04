import dev.csl.foliagui.item.GuiItem;
import dev.csl.foliagui.menu.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The offer editor draws its "Price" / "Amount" steppers from the contents of
 * the input slots, so those labels are only correct if something repaints when
 * a slot changes. This covers the wiring that makes that happen.
 */
public class InputChangeTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.printf("  %-56s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        installServer();

        var ctor = dev.csl.foliagui.FoliaGUI.class
                .getDeclaredConstructor(org.bukkit.plugin.Plugin.class);
        ctor.setAccessible(true);
        Object gui = ctor.newInstance((org.bukkit.plugin.Plugin) null);

        var mctor = Menu.class.getDeclaredConstructor(
                dev.csl.foliagui.FoliaGUI.class,
                dev.csl.foliagui.menu.MenuType.class, Component.class);
        mctor.setAccessible(true);
        Menu menu = mctor.newInstance(gui,
                dev.csl.foliagui.menu.MenuType.GENERIC_9X3, Component.text("editor"));

        final int PAY = 20, CTRL = 11;
        menu.inputSlots(PAY);

        // Stand-in for OfferGui.redraw(): the label is rendered from the slot.
        List<String> repaints = new ArrayList<>();
        Runnable redraw = () -> {
            ItemStack pay = menu.inputItem(PAY);
            String label = pay == null ? "-" : pay.getAmount() + "x " + pay.getType();
            menu.set(CTRL, new GuiItem(new ItemStack(Material.SUNFLOWER)));
            repaints.add(label);
        };
        menu.onInputChange(slot -> redraw.run());
        redraw.run();

        check("label starts empty", repaints.get(repaints.size() - 1).equals("-"));

        // Drop 5 grass in, exactly as the packet listener does.
        menu.set(PAY, new GuiItem(new ItemStack(Material.GRASS_BLOCK, 5)));
        menu.fireInputChangeInternal(PAY);
        check("label follows an item being added",
                repaints.get(repaints.size() - 1).equals("5x GRASS_BLOCK"));

        // Swap it for a different item and amount.
        menu.set(PAY, new GuiItem(new ItemStack(Material.DIAMOND, 3)));
        menu.fireInputChangeInternal(PAY);
        check("label follows the item type changing",
                repaints.get(repaints.size() - 1).equals("3x DIAMOND"));

        // Take it back out.
        menu.set(PAY, null);
        menu.fireInputChangeInternal(PAY);
        check("label follows the slot being emptied",
                repaints.get(repaints.size() - 1).equals("-"));

        // A redraw re-registers the handler; that must not stack up or recurse.
        int before = repaints.size();
        menu.onInputChange(slot -> redraw.run());
        menu.set(PAY, new GuiItem(new ItemStack(Material.EMERALD, 2)));
        menu.fireInputChangeInternal(PAY);
        check("re-registering keeps exactly one handler",
                repaints.size() == before + 1);
        check("and it still reports the right label",
                repaints.get(repaints.size() - 1).equals("2x EMERALD"));

        // set() must not itself fire the change event, or redraw() would
        // recurse forever the moment it painted a control.
        int quiet = repaints.size();
        menu.set(CTRL, new GuiItem(new ItemStack(Material.COMPARATOR)));
        check("painting a control does not fire onInputChange",
                repaints.size() == quiet);

        // clear() preserves input slots, so a redraw can't drop the item.
        menu.clear();
        check("clear() keeps the input slot", menu.inputItem(PAY) != null);
        check("clear() removes ordinary chrome", menu.get(CTRL) == null);

        System.out.printf("%nPASS=%d  FAIL=%d%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void installServer() throws Exception {
        Object factory = Proxy.newProxyInstance(
                InputChangeTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.inventory.ItemFactory.class},
                (p, m, a) -> switch (m.getName()) {
                    case "equals" -> Boolean.TRUE;
                    case "isApplicable" -> Boolean.FALSE;
                    case "hashCode" -> 0;
                    default -> null;
                });
        Object server = Proxy.newProxyInstance(
                InputChangeTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItemFactory" -> factory;
                    case "getLogger" -> java.util.logging.Logger.getLogger("t");
                    case "hashCode" -> 0;
                    case "equals" -> Boolean.FALSE;
                    default -> null;
                });
        Field f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);
    }
}
