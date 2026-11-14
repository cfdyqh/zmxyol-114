package org.yjmobile.zmxy.offline;

/**
 * JNI bridge into libgame.so via libmod.so.
 *
 * libgame.so ships unstripped with 40k+ dynamic symbols, so the companion
 * library can dlsym the game's own C++ members and drive the save layer from
 * C++ instead of hand-written Thumb patches - which is what makes the internal
 * state observable at all.
 *
 * Everything here must be called only after libgame.so is mapped; {@link #probe}
 * returns -1 until then, so callers poll it from a background thread.
 */
public final class Mod {
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("mod");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
    }

    private Mod() {
    }

    public static boolean available() {
        return loaded;
    }

    /** @return 0 once libgame.so is loaded and every symbol resolved, -1 before. */
    public static native int probe();

    /** Logs the parsed files/savedata.dat wrapper and its members; returns members found. */
    public static native int dump();

    /** Cache the plain-JSON save; libmod applies it on the render thread. */
    public static native void setJson(String json);

    /** Apply a plain-JSON save (already decrypted). Bitmask: 1 = bag, 2 = level. */
    public static native int applyJson(String json);

    /** Applies cache.bag / cache.level from the client's own copy. */
    public static native int applySave();

    /** Write 灵魂/仙气/战功/冥币 counters directly into the bag manager. */
    public static native int setCurrencies(int value);

    /**
     * Hand out pets on the render thread.
     *
     * Calls YJPetManager::randomAddPet() - the client's own debug "give me pets"
     * routine (reached in-game through YJTownScene::addPet_T). It rebuilds the pet
     * list with full evolution chains, so the pets show up in the pet panel and
     * are persisted through savedata.pet on the next checkpoint.
     *
     * @return 1 when armed
     */
    public static native int addPets(String outPath);

    /**
     * Re-wear the given item ids on the render thread.
     *
     * The client's own restore only rebuilds items from their id, and nothing
     * reads {@code savedata.equips} back into YJBagManager, so worn gear came back
     * as loose bag entries. libmod looks each id up with getItemsByType() and
     * calls YJBagManager::equipItem() once the bag is populated.
     *
     * @param csvCommaSeparatedIds e.g. "1300,1301"
     * @return number of ids armed, -1 on failure
     */
    public static native int setEquips(String csvCommaSeparatedIds);

    /** Probe which item ids the game's factory accepts. */
    public static native int itemProbe();

    /** Ask the render thread to save the client state locally (non-blocking). */
    public static native void requestDump(String path);

    /** Diagnose serialisation ABI/layout. */
    public static native int stateProbe();

    /** Create and install the role skill-desc object (fixes skill crashes). */
    public static native int fixupRoleDesc();

    /** Dump the client's own bag serialisation to learn its schema. */
    public static native int bagSchema();

    /** Try createItemByJson() on an arbitrary object; 1 = accepted. */
    public static native int itemTest(String json);

    /** Number of items the bag manager actually holds. */
    public static native int bagCount();

    /** Scan the bag singleton for a real vtable pointer. */
    public static native int scanBag();

    /** Calls YJBagManager's vtable slot +0x44 (the guard that made it bail out). */
    public static native int vtable44();
}
