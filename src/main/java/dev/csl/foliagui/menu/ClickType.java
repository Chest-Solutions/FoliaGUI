package dev.csl.foliagui.menu;

import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;

/**
 * A friendlier reading of the raw (mode, button) pair the client sends.
 * <p>
 * The vanilla Click Window packet encodes intent across two fields; this
 * flattens the combinations that matter for menus into one enum so handlers
 * don't have to decode protocol trivia.
 */
public enum ClickType {

    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
    DROP,
    CONTROL_DROP,
    NUMBER_KEY,
    OFFHAND_SWAP,
    DOUBLE_CLICK,
    /** Drag-paint across slots, or anything unrecognised. */
    OTHER;

    public boolean isLeft() {
        return this == LEFT || this == SHIFT_LEFT || this == DOUBLE_CLICK;
    }

    public boolean isRight() {
        return this == RIGHT || this == SHIFT_RIGHT;
    }

    public boolean isShift() {
        return this == SHIFT_LEFT || this == SHIFT_RIGHT;
    }

    public boolean isDrop() {
        return this == DROP || this == CONTROL_DROP;
    }

    /** Decodes the packet's click mode and button into a {@link ClickType}. */
    public static ClickType from(WrapperPlayClientClickWindow.WindowClickType mode, int button) {
        if (mode == null) return OTHER;
        return switch (mode) {
            case PICKUP -> button == 0 ? LEFT : RIGHT;
            case QUICK_MOVE -> button == 0 ? SHIFT_LEFT : SHIFT_RIGHT;
            case SWAP -> button == 40 ? OFFHAND_SWAP : NUMBER_KEY;
            case CLONE -> MIDDLE;
            case THROW -> button == 0 ? DROP : CONTROL_DROP;
            case PICKUP_ALL -> DOUBLE_CLICK;
            default -> OTHER;
        };
    }
}
