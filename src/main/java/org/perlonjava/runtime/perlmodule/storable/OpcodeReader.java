package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * SPI implemented by each opcode-group helper class
 * ({@link Scalars}, {@link Refs}, {@link Containers}, {@link Blessed},
 * {@link Hooks}, {@link Misc}).
 * <p>
 * The opcode byte has already been consumed by {@link StorableReader}
 * before {@code read} is called; the implementation reads only the
 * body. The implementation is responsible for calling
 * {@link StorableContext#recordSeen(RuntimeScalar)} for every fresh
 * scalar it produces, in the order upstream Storable would have
 * stored it (see {@code SEEN_NN} in {@code Storable.xs}).
 * <p>
 * Implementations that need to recurse into a child opcode use
 * {@link StorableReader#dispatch(StorableContext)}.
 */
@FunctionalInterface
public interface OpcodeReader {
    RuntimeScalar read(StorableReader top, StorableContext ctx);
}
