package dev.csl.foliagui.item;

import dev.csl.foliagui.menu.ClickContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** An icon plus its click handler. Immutable apart from the handler. */
public final class GuiItem {

    private final ItemStack stack;
    private Consumer<ClickContext> handler;

    public GuiItem(ItemStack stack) {
        this.stack = stack;
    }

    public GuiItem(ItemStack stack, Consumer<ClickContext> handler) {
        this.stack = stack;
        this.handler = handler;
    }

    public ItemStack stack() {
        return stack;
    }

    public Consumer<ClickContext> handler() {
        return handler;
    }

    public GuiItem onClick(Consumer<ClickContext> handler) {
        this.handler = handler;
        return this;
    }

    public void handle(ClickContext ctx) {
        if (handler != null) handler.accept(ctx);
    }

    // ------------------------------------------------------------- factories

    public static GuiItem of(Material material) {
        return new GuiItem(new ItemStack(material));
    }

    public static GuiItem of(Material material, Component name, Component... lore) {
        return new GuiItem(build(new ItemStack(material), name, Arrays.asList(lore)));
    }

    public static GuiItem of(ItemStack stack, Component name, List<Component> lore) {
        return new GuiItem(build(stack.clone(), name, lore));
    }

    /** A blank, unclickable filler pane. */
    public static GuiItem filler(Material material) {
        return new GuiItem(build(new ItemStack(material), Component.empty(), List.of()));
    }

    /**
     * Applies a name and lore, suppressing the italic default Minecraft adds to
     * custom names so text renders as authored.
     */
    public static ItemStack build(ItemStack stack, Component name, List<Component> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (name != null) {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> out = new ArrayList<>(lore.size());
            for (Component line : lore) {
                out.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(out);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
