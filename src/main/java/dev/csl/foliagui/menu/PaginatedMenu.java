package dev.csl.foliagui.menu;

import dev.csl.foliagui.item.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link Menu} that lays a list of entries across pages.
 * <p>
 * Hand-rolling paging is the single most repeated chunk of boilerplate in
 * inventory GUIs — content slots, page clamping, previous/next arrows that
 * appear only when they should. This does all of it, and re-reads the backing
 * list on every draw so the display follows a live collection.
 *
 * <pre>{@code
 * PaginatedMenu<Warp> menu = gui.paginated(5, Component.text("Warps"), () -> warps);
 * menu.renderer(warp -> GuiItem.of(Material.ENDER_PEARL, Component.text(warp.name()))
 *         .onClick(ctx -> ctx.player().teleportAsync(warp.location())));
 * menu.open(player);
 * }</pre>
 *
 * @param <T> the element type being listed
 */
public class PaginatedMenu<T> extends Menu {

    /** Interior slots of a 6-row chest, i.e. everything inside a 1-slot border. */
    private static final int[] BORDERED_6 = interior(9, 6);

    private final Supplier<List<T>> source;
    private Function<T, GuiItem> renderer = value ->
            GuiItem.of(Material.PAPER, Component.text(String.valueOf(value)));

    private int[] contentSlots;
    private int previousSlot;
    private int nextSlot;
    private int page;

    private GuiItem previousButton;
    private GuiItem nextButton;
    private Runnable decorator;
    private Material filler;
    private Component emptyText;

    public PaginatedMenu(dev.csl.foliagui.FoliaGUI gui, MenuType type, Component title,
                         Supplier<List<T>> source) {
        super(gui, type, title);
        this.source = source;
        this.contentSlots = defaultContentSlots(type);
        this.previousSlot = defaultPreviousSlot(type);
        this.nextSlot = defaultNextSlot(type);
        this.previousButton = GuiItem.of(Material.ARROW, Component.text("Previous page"));
        this.nextButton = GuiItem.of(Material.ARROW, Component.text("Next page"));
    }

    // ------------------------------------------------------------- behaviour

    /** How each element becomes an icon. Required for anything non-trivial. */
    public PaginatedMenu<T> renderer(Function<T, GuiItem> renderer) {
        this.renderer = renderer;
        return this;
    }

    /** Overrides which slots hold content. */
    public PaginatedMenu<T> contentSlots(int... slots) {
        this.contentSlots = slots.clone();
        return this;
    }

    public PaginatedMenu<T> navigationSlots(int previous, int next) {
        this.previousSlot = previous;
        this.nextSlot = next;
        return this;
    }

    public PaginatedMenu<T> navigationButtons(GuiItem previous, GuiItem next) {
        this.previousButton = previous;
        this.nextButton = next;
        return this;
    }

    /** Fills unused slots after drawing. */
    public PaginatedMenu<T> filler(Material material) {
        this.filler = material;
        return this;
    }

    /** Shown in the first content slot when the list is empty. */
    public PaginatedMenu<T> emptyText(Component text) {
        this.emptyText = text;
        return this;
    }

    /**
     * Persistent chrome — headers, footers, info panels — drawn after every
     * repaint.
     * <p>
     * Necessary because {@link #draw()} clears the whole menu, so anything set
     * once at construction is wiped by the first page turn. Register it here
     * and it survives paging, {@link #reload()} and reopening.
     */
    public PaginatedMenu<T> decorator(Runnable decorator) {
        this.decorator = decorator;
        return this;
    }

    // ------------------------------------------------------------- paging

    public int page() {
        return page;
    }

    public int pageCount() {
        int size = source.get().size();
        return Math.max(1, (int) Math.ceil(size / (double) contentSlots.length));
    }

    public void page(int newPage) {
        this.page = Math.max(0, Math.min(newPage, pageCount() - 1));
        draw();
        refresh();
    }

    public void nextPage() {
        page(page + 1);
    }

    public void previousPage() {
        page(page - 1);
    }

    @Override
    public void open(Player player) {
        draw();
        super.open(player);
    }

    /** Re-reads the source list and repaints. Safe to call while open. */
    public void reload() {
        draw();
        if (isOpen()) refresh();
    }

    /**
     * Lays out the current page. Override to add fixed decorations, calling
     * {@code super.draw()} first.
     */
    protected void draw() {
        clear();
        List<T> entries = source.get();
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) contentSlots.length));
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < contentSlots.length; i++) {
            int index = page * contentSlots.length + i;
            if (index >= entries.size()) break;
            GuiItem item = renderer.apply(entries.get(index));
            if (item != null) set(contentSlots[i], item);
        }

        if (entries.isEmpty() && emptyText != null && contentSlots.length > 0) {
            set(contentSlots[0], GuiItem.of(Material.BARRIER, emptyText));
        }

        if (page > 0 && previousSlot >= 0) {
            set(previousSlot, withPageInfo(previousButton, page, pages)
                    .onClick(ctx -> previousPage()));
        }
        if (page < pages - 1 && nextSlot >= 0) {
            set(nextSlot, withPageInfo(nextButton, page + 2, pages)
                    .onClick(ctx -> nextPage()));
        }

        // Chrome goes on after the page content, then the filler last so it
        // only occupies whatever is still empty.
        if (decorator != null) decorator.run();
        if (filler != null) fill(filler);
    }

    /**
     * Substitutes {@code <page>} and {@code <pages>} in the button's name, so
     * callers can label arrows without recomputing the page count.
     */
    private GuiItem withPageInfo(GuiItem button, int targetPage, int pages) {
        org.bukkit.inventory.ItemStack stack = button.stack().clone();
        var meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String raw = MiniMessage.miniMessage().serialize(meta.displayName());
            meta.displayName(MiniMessage.miniMessage().deserialize(raw,
                    Placeholder.parsed("page", String.valueOf(targetPage)),
                    Placeholder.parsed("pages", String.valueOf(pages))));
            stack.setItemMeta(meta);
        }
        return new GuiItem(stack);
    }

    // -------------------------------------------------------------- defaults

    private static int[] defaultContentSlots(MenuType type) {
        // Chests get a tidy bordered layout; other shapes use every slot.
        if (type == MenuType.GENERIC_9X6) return BORDERED_6;
        if (type == MenuType.GENERIC_9X5) return interior(9, 5);
        if (type == MenuType.GENERIC_9X4) return interior(9, 4);
        if (type == MenuType.GENERIC_9X3) return interior(9, 3);
        int[] all = new int[type.size()];
        for (int i = 0; i < all.length; i++) all[i] = i;
        return all;
    }

    /** Every slot except the outer ring. */
    private static int[] interior(int columns, int rows) {
        List<Integer> slots = new ArrayList<>();
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c < columns - 1; c++) {
                slots.add(r * columns + c);
            }
        }
        int[] out = new int[slots.size()];
        for (int i = 0; i < out.length; i++) out[i] = slots.get(i);
        return out;
    }

    private static int defaultPreviousSlot(MenuType type) {
        return type.isChest() && type.rows() >= 3 ? type.size() - 9 + 2 : -1;
    }

    private static int defaultNextSlot(MenuType type) {
        return type.isChest() && type.rows() >= 3 ? type.size() - 9 + 6 : -1;
    }
}
