package dev.csl.foliagui.menu;

/**
 * Every vanilla container shape, with the protocol id the Open Screen packet
 * carries.
 *
 * <h2>Why the ids are hardcoded</h2>
 * The packet needs a numeric menu type and there is no way to look one up:
 * PacketEvents ships ~80 versioned registries but no {@code menu} registry, and
 * Bukkit's {@code Registry.MENU} only exists on 1.21+ where it still exposes
 * keys rather than protocol ids. A table is the only option.
 *
 * <h2>Why no version handling</h2>
 * These ids come from the ordered {@code minecraft:menu} registry, so an
 * insertion shifts everything after it. Exactly one such insertion has
 * happened: {@code crafter_3x3} at index 7, in <b>1.20.3</b>. Every version
 * this library supports is newer than that, so the layout below is simply
 * correct — no version branching required.
 *
 * <h2>Which menus are useful</h2>
 * All of them open, but they differ in how much the client expects the server
 * to drive:
 * <ul>
 *   <li><b>Grids</b> ({@code GENERIC_*}, {@link #HOPPER}, {@link #SHULKER_BOX})
 *       render purely from Window Items — nothing else needed.</li>
 *   <li><b>Interactive</b> ({@link #ANVIL}, {@link #LECTERN}, {@link #LOOM},
 *       {@link #STONECUTTER}, {@link #BEACON}, {@link #ENCHANTMENT},
 *       {@link #CRAFTER_3X3}) send extra packets. FoliaGUI decodes these into
 *       {@link Menu#onTextInput} and {@link Menu#onButton}, which is how anvil
 *       text prompts and stonecutter-style pickers are built.</li>
 *   <li><b>Progress-driven</b> ({@link #FURNACE}, {@link #SMOKER},
 *       {@link #BLAST_FURNACE}, {@link #BREWING_STAND}) have burn and brew
 *       bars fed by container properties FoliaGUI does not send, so those
 *       gauges sit empty. Fine as a themed backdrop; misleading as a
 *       simulation.</li>
 * </ul>
 */
public enum MenuType {

    GENERIC_9X1(0, "minecraft:chest", 9, 1),
    GENERIC_9X2(1, "minecraft:chest", 9, 2),
    GENERIC_9X3(2, "minecraft:chest", 9, 3),
    GENERIC_9X4(3, "minecraft:chest", 9, 4),
    GENERIC_9X5(4, "minecraft:chest", 9, 5),
    GENERIC_9X6(5, "minecraft:chest", 9, 6),
    /** Dispenser / dropper grid. */
    GENERIC_3X3(6, "minecraft:dispenser", 3, 3),
    /** Crafter. Sends button presses for slot toggles. */
    CRAFTER_3X3(7, "minecraft:crafter", 3, 3),
    /** Two inputs and a result, plus a rename field — see {@code onTextInput}. */
    ANVIL(8, "minecraft:anvil", 3, 1),
    /** Effect picker; selections arrive as button presses. */
    BEACON(9, "minecraft:beacon", 1, 1),
    BLAST_FURNACE(10, "minecraft:blast_furnace", 3, 1),
    BREWING_STAND(11, "minecraft:brewing_stand", 5, 1),
    /** 3x3 grid plus result slot. */
    CRAFTING(12, "minecraft:crafting_table", 10, 1),
    ENCHANTMENT(13, "minecraft:enchanting_table", 2, 1),
    FURNACE(14, "minecraft:furnace", 3, 1),
    GRINDSTONE(15, "minecraft:grindstone", 3, 1),
    HOPPER(16, "minecraft:hopper", 5, 1),
    /** Book view; page turns arrive as button presses. */
    LECTERN(17, "minecraft:lectern", 1, 1),
    /** Pattern picker; selections arrive as button presses. */
    LOOM(18, "minecraft:loom", 4, 1),
    /** Villager trade screen. Drive it with {@link MerchantMenu}. */
    MERCHANT(19, "minecraft:villager", 3, 1),
    SHULKER_BOX(20, "minecraft:shulker_box", 9, 3),
    SMITHING(21, "minecraft:smithing_table", 4, 1),
    SMOKER(22, "minecraft:smoker", 3, 1),
    CARTOGRAPHY_TABLE(23, "minecraft:cartography_table", 3, 1),
    /** Recipe picker; selections arrive as button presses. */
    STONECUTTER(24, "minecraft:stonecutter", 2, 1);

    private final int protocolId;
    private final String legacyId;
    private final int columns;
    private final int rows;

    MenuType(int protocolId, String legacyId, int columns, int rows) {
        this.protocolId = protocolId;
        this.legacyId = legacyId;
        this.columns = columns;
        this.rows = rows;
    }

    /** The id written to the Open Screen packet. */
    public int protocolId() {
        return protocolId;
    }

    /** Namespaced id, used by pre-1.14 clients that sent strings. */
    public String legacyId() {
        return legacyId;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    /** Number of slots in the top (menu) inventory. */
    public int size() {
        return columns * rows;
    }

    /** True for the six chest shapes, the only freely sized menus. */
    public boolean isChest() {
        return ordinal() <= GENERIC_9X6.ordinal();
    }

    /** True when this menu accepts a rename field ({@code onTextInput}). */
    public boolean hasTextInput() {
        return this == ANVIL;
    }

    /** True when this menu sends button presses ({@code onButton}). */
    public boolean hasButtons() {
        return this == LECTERN || this == LOOM || this == STONECUTTER
                || this == BEACON || this == ENCHANTMENT || this == CRAFTER_3X3;
    }

    /** The chest shape holding at least {@code rows} rows, clamped to 1–6. */
    public static MenuType chestRows(int rows) {
        return switch (Math.max(1, Math.min(6, rows))) {
            case 1 -> GENERIC_9X1;
            case 2 -> GENERIC_9X2;
            case 3 -> GENERIC_9X3;
            case 4 -> GENERIC_9X4;
            case 5 -> GENERIC_9X5;
            default -> GENERIC_9X6;
        };
    }
}
