package dev.umb.hostagent;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Pages extra creative tabs because vanilla occupies every visible tab slot. Special tabs remain
 * visible on every page, while category tabs are filtered and remapped into the five free slots.
 */
public final class CreativePaging {

    /** First column no vanilla tab occupies. */
    public static final int FIRST_MOD_COLUMN = 7;
    /** Drawable slots per page: TOP 0..4 (TOP 5/6 are the always-visible hotbar + search tabs). */
    public static final int PAGE_SIZE = 5;
    /** Where an off-page tab is parked: 27*40 px, far past the 195 px panel. */
    static final int OFFSCREEN_COLUMN = 40;
    /** Tab strip geometry, from CreativeModeInventoryScreen (26 wide + 1 gap, 32 tall). */
    static final int TAB_STRIDE = 27;
    static final int TAB_HEIGHT = 32;

    private static volatile int page = 0;
    private static volatile int cachedLastPage = -1;
    private static volatile boolean hintBroken = false;

    private static Method selectTab;
    private static Method extractorText;
    private static Method keyEventKey;

    private CreativePaging() {
    }

    public static int page() {
        return page;
    }

    /** page 0 = vanilla; our tabs start on page 1. */
    public static int pageOfColumn(int column) {
        if (column < FIRST_MOD_COLUMN) return 0;
        return 1 + (column - FIRST_MOD_COLUMN) / PAGE_SIZE;
    }

    /** Where a tab on its own page is drawn. */
    public static int drawColumn(int column) {
        if (column < FIRST_MOD_COLUMN) return column;
        return (column - FIRST_MOD_COLUMN) % PAGE_SIZE;
    }

    static boolean isOurs(CreativeModeTab tab) {
        return tab != null && tab.row() == CreativeModeTab.Row.TOP && tab.column() >= FIRST_MOD_COLUMN;
    }

    /** Highest page index. 0 when we registered no tabs at all, so the hint stays hidden. */
    public static int lastPage() {
        int c = cachedLastPage;
        if (c >= 0) return c;
        int max = 0;
        try {
            for (CreativeModeTab t : CreativeModeTabs.allTabs()) {
                if (isOurs(t)) max = Math.max(max, pageOfColumn(t.column()));
            }
        } catch (Throwable t) {
            AgentLog.error("CreativePaging.lastPage", t, 2);
        }
        cachedLastPage = max;
        return max;
    }

    /** Replaces every {@code CreativeModeTabs.tabs()} call inside the creative screen. */
    public static List<CreativeModeTab> visibleTabs() {
        List<CreativeModeTab> all;
        try {
            all = CreativeModeTabs.tabs();
        } catch (Throwable t) {
            AgentLog.error("CreativePaging.visibleTabs", t, 2);
            return List.of();
        }
        int p = page;
        List<CreativeModeTab> out = new ArrayList<>(all.size());
        for (CreativeModeTab t : all) {
            try {
                if (isOurs(t)) {
                    if (pageOfColumn(t.column()) == p) out.add(t);
                } else if (p == 0 || t.getType() != CreativeModeTab.Type.CATEGORY) {
                    // SEARCH / INVENTORY / HOTBAR tabs are never hidden - they are how the player
                    // gets back to the search box and the survival inventory from any page.
                    out.add(t);
                }
            } catch (Throwable ignored) {
                out.add(t);
            }
        }
        return out;
    }

    /** Replaces {@code tab.column()} in getTabX and extractTabButton. */
    public static int tabColumn(CreativeModeTab tab) {
        try {
            int col = tab.column();
            if (!isOurs(tab)) return col;
            if (pageOfColumn(col) != page) return OFFSCREEN_COLUMN;
            return drawColumn(col);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Wraps: page 0 -> 1 -> ... -> lastPage -> 0. */
    public static void switchTo(Object screen, int wanted) {
        int last = lastPage();
        int p = wanted;
        if (p < 0) p = last;
        if (p > last) p = 0;
        page = p;
        AgentLog.line("creative tab page -> " + p + "/" + last);
        selectFirstOnPage(screen);
    }

    public static void nextPage(Object screen) {
        switchTo(screen, page + 1);
    }

    public static void prevPage(Object screen) {
        switchTo(screen, page - 1);
    }

    /**
     * The screen keeps a static {@code selectedTab} and {@code extractBackground} redraws its
     * button unconditionally, so leaving a hidden tab selected would both paint a stray button
     * over the new page and leave the panel showing the wrong contents. Re-select the first
     * CATEGORY tab of the new page.
     */
    private static void selectFirstOnPage(Object screen) {
        if (screen == null) return;
        try {
            CreativeModeTab target = null;
            for (CreativeModeTab t : visibleTabs()) {
                if (t.getType() == CreativeModeTab.Type.CATEGORY) {
                    target = t;
                    break;
                }
            }
            if (target == null) return;
            if (selectTab == null) {
                selectTab = screen.getClass().getDeclaredMethod("selectTab", CreativeModeTab.class);
                selectTab.setAccessible(true);
            }
            selectTab.invoke(screen, target);
        } catch (Throwable t) {
            AgentLog.error("CreativePaging.selectFirstOnPage", t, 3);
        }
    }

    /**
     * Scroll while the cursor is over the TOP tab strip. Screen coords: the strip sits at
     * {@code y in [topPos-32, topPos)} because {@code getTabY(tab) = row==TOP ? -32 : imageHeight}
     * and {@code checkTabClicked} compares against panel-relative coordinates.
     */
    public static boolean scroll(Object screen, double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        try {
            if (lastPage() < 1) return false;
            int leftPos = intField(screen, "leftPos");
            int topPos = intField(screen, "topPos");
            if (mouseY < topPos - TAB_HEIGHT || mouseY >= topPos) return false;
            if (mouseX < leftPos || mouseX > leftPos + TAB_STRIDE * 7) return false;
            switchTo(screen, page + (scrollY > 0 ? -1 : 1));
            return true;
        } catch (Throwable t) {
            AgentLog.error("CreativePaging.scroll", t, 2);
            return false;
        }
    }

    /**
     * PAGE_UP/PAGE_DOWN and '['/']' (GLFW key codes 266/267 and 91/93).
     * Consuming keyPressed does not stop charTyped, so '['/']' can still land in the search box;
     * PAGE_UP/PAGE_DOWN are the clean pair.
     */
    public static boolean key(Object screen, Object keyEvent) {
        try {
            if (lastPage() < 1 || keyEvent == null) return false;
            if (keyEventKey == null) {
                keyEventKey = keyEvent.getClass().getMethod("key");
            }
            int k = (Integer) keyEventKey.invoke(keyEvent);
            if (k == 266 || k == 91) {
                switchTo(screen, page - 1);
                return true;
            }
            if (k == 267 || k == 93) {
                switchTo(screen, page + 1);
                return true;
            }
            return false;
        } catch (Throwable t) {
            AgentLog.error("CreativePaging.key", t, 2);
            return false;
        }
    }

    /** Draws the page hint without linking client classes into headless environments. */
    public static void renderHint(Object extractor, Object screen) {
        if (hintBroken) return;
        try {
            int last = lastPage();
            if (last < 1) return;
            int leftPos = intField(screen, "leftPos");
            int topPos = intField(screen, "topPos");
            Object font = field(screen, "font").get(screen);
            if (extractorText == null) {
                extractorText = extractor.getClass().getMethod("text",
                        font.getClass(), String.class, int.class, int.class, int.class);
            }
            // Use the configured namespace because this pager is shared by every legacy mod.
            String msg = HostAgent.namespace().toUpperCase(java.util.Locale.ROOT) + " tabs: page "
                    + (page + 1) + "/" + (last + 1) + " - scroll on tabs / PgUp-PgDn";
            extractorText.invoke(extractor, font, msg, leftPos, topPos - 42, 0xFFFFFFFF);
        } catch (Throwable t) {
            hintBroken = true;
            AgentLog.loud("UMB-HOSTAGENT page hint disabled (draw failed): " + t);
            AgentLog.error("CreativePaging.renderHint", t, 4);
        }
    }

    private static int intField(Object o, String name) throws Exception {
        return field(o, name).getInt(o);
    }

    private static Field field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name + " on " + o.getClass());
    }

    /** Test seam: the page index is static process state. */
    public static void resetForTests() {
        page = 0;
        cachedLastPage = -1;
        hintBroken = false;
    }
}
