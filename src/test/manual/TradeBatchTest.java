import dev.csl.foliagui.menu.TradeBatch;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Exercises shift-click batch sizing: the number of trades vanilla would
 * settle in one go. Pure arithmetic over an inventory snapshot, so it runs
 * without a server.
 */
public class TradeBatchTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.printf("  %-58s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    static void eq(String what, int actual, int expected) {
        boolean ok = actual == expected;
        System.out.printf("  %-58s %s%s%n", what, ok ? "PASS" : "FAIL",
                ok ? "" : "  (got " + actual + ", want " + expected + ")");
        if (ok) pass++; else fail++;
    }

    /** 36 storage slots, all empty. */
    static ItemStack[] inv() {
        return new ItemStack[36];
    }

    public static void main(String[] args) throws Exception {
        installServer();

        ItemStack wool = new ItemStack(Material.WHITE_WOOL, 1);
        ItemStack emerald = new ItemStack(Material.EMERALD, 1);
        ItemStack[] oneEmerald = {emerald, null};

        // ---- the reported case: a full stack of payment, one item per trade.
        System.out.println("stack of currency buys a stack of goods");
        ItemStack[] a = inv();
        a[0] = new ItemStack(Material.EMERALD, 64);
        // 64 in the inventory plus the lot already staged in the input slot.
        eq("64 held + 1 staged buys 65 wool", TradeBatch.size(a, new int[]{1, 0}, 1,
                oneEmerald, wool, Integer.MAX_VALUE, false), 65);

        // One lot is already staged, so 63 remain in the inventory.
        ItemStack[] b = inv();
        b[0] = new ItemStack(Material.EMERALD, 63);
        eq("63 left + 1 staged still buys 64", TradeBatch.size(b, new int[]{1, 0}, 1,
                oneEmerald, wool, Integer.MAX_VALUE, false), 64);

        System.out.println("bounded by what the player can pay");
        ItemStack[] c = inv();
        c[0] = new ItemStack(Material.EMERALD, 4);
        eq("5 emeralds total buys 5", TradeBatch.size(c, new int[]{1, 0}, 1,
                oneEmerald, wool, Integer.MAX_VALUE, false), 5);

        ItemStack[] d = inv();
        eq("nothing left to pay with buys the staged 1", TradeBatch.size(d,
                new int[]{1, 0}, 1, oneEmerald, wool, Integer.MAX_VALUE, false), 1);

        System.out.println("bounded by advertised stock");
        ItemStack[] e = inv();
        e[0] = new ItemStack(Material.EMERALD, 64);
        eq("stock of 7 caps the batch", TradeBatch.size(e, new int[]{1, 0}, 1,
                oneEmerald, wool, 7, false), 7);
        eq("stock of 0 never trades more than the staged lot",
                TradeBatch.size(e, new int[]{1, 0}, 1, oneEmerald, wool, 0, false), 1);

        System.out.println("bounded by room for the goods");
        // 35 slots of junk, one slot of emeralds: paying frees that one slot.
        ItemStack[] f = inv();
        for (int i = 0; i < 35; i++) f[i] = new ItemStack(Material.STONE, 64);
        f[35] = new ItemStack(Material.EMERALD, 64);
        int n = TradeBatch.size(f, new int[]{1, 0}, 1, oneEmerald, wool,
                Integer.MAX_VALUE, false);
        check("a nearly full inventory still trades", n >= 1);
        check("but not more than the freed slot holds", n <= 64);

        ItemStack[] g = inv();
        for (int i = 0; i < 36; i++) g[i] = new ItemStack(Material.STONE, 64);
        eq("a completely full inventory falls back to 1", TradeBatch.size(g,
                new int[]{1, 0}, 1, oneEmerald, wool, Integer.MAX_VALUE, true), 1);

        System.out.println("partial stacks of the result are topped up");
        ItemStack[] h = inv();
        for (int i = 0; i < 35; i++) h[i] = new ItemStack(Material.STONE, 64);
        h[35] = new ItemStack(Material.WHITE_WOOL, 60);
        // Virtual cost: no items to remove, only 4 wool of room left.
        eq("room in an existing stack is used", TradeBatch.size(h, new int[]{0, 0}, 1,
                new ItemStack[]{new ItemStack(Material.AIR), null}, wool,
                Integer.MAX_VALUE, true), 4);

        System.out.println("multi-item results and costs");
        ItemStack[] i2 = inv();
        i2[0] = new ItemStack(Material.EMERALD, 64);
        eq("a cost of 8 each buys 8 from 64", TradeBatch.size(i2, new int[]{8, 0}, 1,
                new ItemStack[]{new ItemStack(Material.EMERALD, 8), null},
                wool, Integer.MAX_VALUE, false), 9);

        ItemStack[] j = inv();
        j[0] = new ItemStack(Material.EMERALD, 64);
        int outStacks = TradeBatch.size(j, new int[]{1, 0}, 1, oneEmerald,
                new ItemStack(Material.WHITE_WOOL, 16), Integer.MAX_VALUE, false);
        // 65 lots of 16 is 1040 wool: 17 slots, and paying frees the emerald
        // slot, so an otherwise empty inventory takes the lot.
        eq("a 16-per-trade result batches the same 65 times", outStacks, 65);

        System.out.println("two-item costs");
        ItemStack[] k = inv();
        k[0] = new ItemStack(Material.EMERALD, 64);
        k[1] = new ItemStack(Material.DIAMOND, 3);
        eq("the scarcer of two costs decides", TradeBatch.size(k, new int[]{1, 1}, 1,
                new ItemStack[]{emerald, new ItemStack(Material.DIAMOND, 1)},
                wool, Integer.MAX_VALUE, false), 4);

        System.out.println("virtual (money) cost");
        ItemStack[] l = inv();
        eq("money price is not limited by carried items", TradeBatch.size(l,
                new int[]{0, 0}, 1, new ItemStack[]{new ItemStack(Material.AIR), null},
                wool, 10, true), 10);

        System.out.println("degenerate inputs");
        eq("a null output never batches", TradeBatch.size(inv(), new int[]{0, 0}, 1,
                oneEmerald, null, 64, false), 1);
        eq("an air output never batches", TradeBatch.size(inv(), new int[]{0, 0}, 1,
                oneEmerald, new ItemStack(Material.AIR), 64, false), 1);

        // ---- a surplus in the slot already pays for several lots.
        System.out.println("surplus already staged in the input slot");
        ItemStack[] m = inv();
        // 10 emeralds staged, none left in the inventory: 10 lots, no top-up.
        eq("10 staged lots need nothing from the inventory", TradeBatch.size(m,
                new int[]{10, 0}, 10, oneEmerald, wool, Integer.MAX_VALUE, false), 10);

        ItemStack[] n2 = inv();
        n2[0] = new ItemStack(Material.EMERALD, 5);
        eq("staged surplus and inventory both count", TradeBatch.size(n2,
                new int[]{10, 0}, 10, oneEmerald, wool, Integer.MAX_VALUE, false), 15);

        eq("stock still caps a staged surplus", TradeBatch.size(inv(),
                new int[]{10, 0}, 10, oneEmerald, wool, 3, false), 3);

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    /** Minimal Bukkit server so ItemStack/Material behave. */
    static void installServer() throws Exception {
        Object factory = Proxy.newProxyInstance(
                TradeBatchTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.inventory.ItemFactory.class},
                (p, m, a) -> switch (m.getName()) {
                    case "equals" -> Boolean.TRUE;
                    case "isApplicable" -> Boolean.FALSE;
                    case "hashCode" -> 0;
                    default -> null;
                });
        Object server = Proxy.newProxyInstance(
                TradeBatchTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getItemFactory" -> factory;
                    case "getLogger" -> java.util.logging.Logger.getLogger("t");
                    case "toString" -> "test";
                    case "hashCode" -> 0;
                    case "equals" -> Boolean.FALSE;
                    default -> null;
                });
        Field f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);
    }
}
