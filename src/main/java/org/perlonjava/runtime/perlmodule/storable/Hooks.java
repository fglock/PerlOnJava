package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * STORABLE_freeze / STORABLE_thaw hook readers/writers.
 * <p>
 * <strong>OWNER: hooks-agent</strong>
 * <p>
 * Opcodes covered:
 * <ul>
 *   <li>{@link Opcodes#SX_HOOK} — output of a class's STORABLE_freeze
 *       method. See {@code retrieve_hook} in Storable.xs (search for
 *       "static SV *retrieve_hook").</li>
 * </ul>
 * <p>
 * The wire format is intricate (flags byte, class name or index,
 * frozen string, list of sub-objects). The reader must:
 * <ol>
 *   <li>Parse the SX_HOOK header.</li>
 *   <li>Resolve the class (either inline name or class-table index).</li>
 *   <li>Instantiate a placeholder SV, recordSeen it.</li>
 *   <li>Recurse into sub-objects via {@link StorableReader#dispatch}.</li>
 *   <li>Call the class's STORABLE_thaw method with the frozen string
 *       and the sub-object list. PerlOnJava already has hook-calling
 *       plumbing in {@code Storable.java}; reuse it.</li>
 * </ol>
 */
public final class Hooks {
    private Hooks() {}

    public static RuntimeScalar readHook(StorableReader r, StorableContext c) {
        throw new StorableFormatException("hooks-agent: SX_HOOK not yet implemented");
    }
}
