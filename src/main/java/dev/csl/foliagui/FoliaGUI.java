package dev.csl.foliagui;

import dev.csl.foliagui.menu.Menu;
import dev.csl.foliagui.menu.MerchantMenu;
import dev.csl.foliagui.packet.MenuListener;
import dev.csl.foliagui.packet.MenuPackets;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point and menu registry.
 *
 * <pre>{@code
 * public final class MyPlugin extends JavaPlugin {
 *     private FoliaGUI gui;
 *
 *     @Override public void onLoad() {
 *         PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
 *         PacketEvents.getAPI().load();
 *     }
 *
 *     @Override public void onEnable() {
 *         PacketEvents.getAPI().init();
 *         gui = FoliaGUI.create(this);
 *     }
 *
 *     @Override public void onDisable() {
 *         gui.shutdown();
 *     }
 *
 *     public FoliaGUI gui() { return gui; }
 * }
 * }</pre>
 *
 * <h2>No global lookup, by design</h2>
 * There is deliberately no {@code FoliaGUI.get()} or {@code FoliaGUI.of(plugin)}.
 * Hold the instance {@link #create(Plugin)} returns and pass it around.
 * <p>
 * A static registry would work until two plugins both used the library. Each
 * one normally <em>bundles and relocates</em> its own copy, so
 * {@code com.a.libs.FoliaGUI} and {@code com.b.libs.FoliaGUI} are unrelated
 * classes with unrelated static state. A global lookup then behaves differently
 * depending on which copy the caller happened to link against, and a plugin
 * reaching for another plugin's menus gets an empty registry, a foreign
 * instance, or a {@code ClassCastException} across classloaders — all with no
 * useful error. Requiring an explicit reference makes that impossible: if you
 * can't reach the object, you were never meant to drive that plugin's menus.
 * <p>
 * If you <em>do</em> want to expose your menus to other plugins, publish your
 * own accessor (as {@code MyPlugin#gui()} above) — then the dependency is
 * explicit and the classloader question is yours to answer.
 */
public final class FoliaGUI implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Menu> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, MerchantMenu> openMerchants = new ConcurrentHashMap<>();
    /**
     * Window ids cycle in 1..99. Zero is the player's own inventory and must
     * never be used; some clients also dislike ids above 100.
     */
    private final AtomicInteger windowIds = new AtomicInteger(1);

    private FoliaGUI(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates an instance for {@code plugin} and registers its packet and
     * Bukkit listeners. Call once from {@code onEnable}, after
     * {@code PacketEvents.getAPI().init()}, and keep the returned reference.
     */
    public static FoliaGUI create(Plugin plugin) {
        FoliaGUI gui = new FoliaGUI(plugin);
        PacketEvents.getAPI().getEventManager()
                .registerListener(new MenuListener(gui), PacketListenerPriority.NORMAL);
        Bukkit.getPluginManager().registerEvents(gui, plugin);
        return gui;
    }

    /** Closes every open menu. Call from {@code onDisable}. */
    public void shutdown() {
        closeAll();
    }

    /**
     * Runs {@code task} owning {@code player}, or immediately if the plugin is
     * disabled.
     * <p>
     * Schedulers reject work from a disabled plugin
     * ({@code "Plugin attempted to register task while disabled"}), so during
     * {@code onDisable} the callbacks below must run inline. That is safe here:
     * everything these paths do is send packets, which is thread-safe and does
     * not touch world state.
     */
    private void runForPlayer(Player player, Runnable task) {
        if (!plugin.isEnabled()) {
            task.run();
            return;
        }
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    public Plugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------- factories

    /** A chest menu with the given row count (1-6). */
    public Menu menu(int rows, net.kyori.adventure.text.Component title) {
        return new Menu(this, dev.csl.foliagui.menu.MenuType.chestRows(rows), title);
    }

    /** A menu of any supported container shape. */
    public Menu menu(dev.csl.foliagui.menu.MenuType type,
                     net.kyori.adventure.text.Component title) {
        return new Menu(this, type, title);
    }

    /** A paginated chest menu backed by a live list. */
    public <T> dev.csl.foliagui.menu.PaginatedMenu<T> paginated(
            int rows, net.kyori.adventure.text.Component title,
            java.util.function.Supplier<java.util.List<T>> source) {
        return new dev.csl.foliagui.menu.PaginatedMenu<>(
                this, dev.csl.foliagui.menu.MenuType.chestRows(rows), title, source);
    }

    /** A villager trade screen. */
    public MerchantMenu merchant(net.kyori.adventure.text.Component title) {
        return new MerchantMenu(this, title);
    }

    // -------------------------------------------------------------- registry

    public Menu menuOf(Player player) {
        return openMenus.get(player.getUniqueId());
    }

    public MerchantMenu merchantOf(Player player) {
        return openMerchants.get(player.getUniqueId());
    }

    private int nextWindowId() {
        return windowIds.updateAndGet(i -> i >= 99 ? 1 : i + 1);
    }

    // ------------------------------------------------------------- lifecycle

    /** Opens {@code menu} for {@code player}, closing whatever was open. */
    public void open(Menu menu, Player player) {
        closeExisting(player, menu, null);
        runForPlayer(player, () -> {
            // Dismiss any real container so the client isn't juggling a
            // server-side window and our packet window at once.
            player.closeInventory();

            int id = nextWindowId();
            menu.markOpened(player, id);
            // Snapshot the inventory so click handling needs no platform read.
            menu.capturePlayerView(player.getInventory().getContents());
            openMenus.put(player.getUniqueId(), menu);

            MenuPackets.sendOpen(menu, player);
            menu.refresh();
            menu.fireOpenInternal(player);
        });
    }

    /** Opens a villager trade window, replacing whatever was open. */
    public void openMerchant(MerchantMenu menu, Player player) {
        closeExisting(player, null, menu);
        runForPlayer(player, () -> {
            player.closeInventory();

            int id = nextWindowId();
            menu.markOpened(player, id);
            openMerchants.put(player.getUniqueId(), menu);

            MenuPackets.sendMerchantOpen(menu, player);
            // Offers must follow the open packet: the client discards trade
            // rows for a window it hasn't been told about yet.
            MenuPackets.sendMerchantOffers(menu, player);
        });
    }

    private void closeExisting(Player player, Menu keepMenu, MerchantMenu keepMerchant) {
        Menu chest = openMenus.get(player.getUniqueId());
        if (chest != null && chest != keepMenu) close(chest, false);
        MerchantMenu merchant = openMerchants.get(player.getUniqueId());
        if (merchant != null && merchant != keepMerchant) closeMerchant(merchant, false);
    }

    /**
     * Closes a menu.
     *
     * @param tellClient whether to send a Close Window packet; false when the
     *                   client initiated the close and already dismissed it
     */
    public void close(Menu menu, boolean tellClient) {
        Player player = menu.viewer();
        if (player == null) {
            menu.markClosed();
            return;
        }
        openMenus.remove(player.getUniqueId(), menu);
        boolean wasOpen = menu.isOpen();

        // A mirrored cursor is only a picture and needs no returning, but one
        // holding bought goods is genuinely owed. Input slots may also hold a
        // real item the menu placed there.
        java.util.List<org.bukkit.inventory.ItemStack> toReturn = new java.util.ArrayList<>();
        if (menu.isCursorOwed()) toReturn.add(menu.virtualCursor());
        for (int slot : menu.inputSlotSet()) {
            org.bukkit.inventory.ItemStack held = menu.inputItem(slot);
            if (held != null) toReturn.add(held);
        }

        menu.markClosed();
        if (!wasOpen) return;

        runForPlayer(player, () -> {
            if (tellClient) MenuPackets.sendClose(menu, player);
            MenuPackets.clearCursor(player);
            for (org.bukkit.inventory.ItemStack stack : toReturn) {
                player.getInventory().addItem(stack).values().forEach(overflow ->
                        player.getWorld().dropItemNaturally(player.getLocation(), overflow));
            }
            // The window was never real; make the client's view of the
            // player's own inventory authoritative again.
            MenuPackets.resyncPlayerInventory(player);
            menu.fireCloseInternal(player);
        });
    }

    public void closeMerchant(MerchantMenu menu, boolean tellClient) {
        Player player = menu.viewer();
        if (player == null) {
            menu.markClosed();
            return;
        }
        openMerchants.remove(player.getUniqueId(), menu);
        boolean wasOpen = menu.isOpen();

        // A merchant window holds real items in two places: goods from a
        // completed trade on the cursor, and the player's own stock sitting in
        // the input slots. Both must come back, or closing mid-trade destroys
        // them.
        java.util.List<org.bukkit.inventory.ItemStack> toReturn = new java.util.ArrayList<>();
        if (menu.cursor() != null) toReturn.add(menu.cursor());
        toReturn.addAll(menu.takeInputs());

        menu.markClosed();
        if (!wasOpen) return;

        runForPlayer(player, () -> {
            if (tellClient) MenuPackets.sendMerchantClose(menu, player);
            MenuPackets.clearCursor(player);
            for (org.bukkit.inventory.ItemStack stack : toReturn) {
                player.getInventory().addItem(stack).values().forEach(overflow ->
                        player.getWorld().dropItemNaturally(player.getLocation(), overflow));
            }
            MenuPackets.resyncPlayerInventory(player);
            menu.fireCloseInternal(player);
        });
    }

    public void closeAll() {
        // Snapshot first: close() removes from these maps as it goes.
        for (Menu menu : new java.util.ArrayList<>(openMenus.values())) {
            close(menu, true);
        }
        for (MerchantMenu menu : new java.util.ArrayList<>(openMerchants.values())) {
            closeMerchant(menu, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Menu menu = openMenus.remove(player.getUniqueId());
        if (menu != null) {
            if (menu.isCursorOwed()) player.getInventory().addItem(menu.virtualCursor());
            // Put back anything held on the cursor or left in an input slot,
            // straight into the inventory being saved as the player leaves.
            for (int slot : menu.inputSlotSet()) {
                org.bukkit.inventory.ItemStack held = menu.inputItem(slot);
                if (held != null) player.getInventory().addItem(held);
            }
            menu.markClosed();
        }
        MerchantMenu merchant = openMerchants.remove(player.getUniqueId());
        if (merchant != null) {
            // Cursor goods and anything left in the input slots go into the
            // inventory being saved as the player leaves.
            if (merchant.cursor() != null) {
                player.getInventory().addItem(merchant.cursor());
            }
            for (org.bukkit.inventory.ItemStack stack : merchant.takeInputs()) {
                player.getInventory().addItem(stack);
            }
            merchant.markClosed();
        }
    }
}
