package org.perlonjava.runtime.perlmodule.storable;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;

/**
 * Encoder for {@code SX_VSTRING} / {@code SX_LVSTRING}.
 * <p>
 * Wire format (Storable.xs {@code retrieve_vstring} L5833, {@code
 * retrieve_lvstring} L5864):
 * <pre>
 *   SX_VSTRING  &lt;vstr-len 1 byte&gt;  &lt;vstr-bytes&gt;  &lt;regular scalar body&gt;
 *   SX_LVSTRING &lt;vstr-len U32&gt;     &lt;vstr-bytes&gt;  &lt;regular scalar body&gt;
 * </pre>
 * The v-string magic bytes come <em>first</em>, then a recursive scalar
 * opcode for the textual scalar (typically SX_SCALAR/SX_LSCALAR with
 * the same bytes). On retrieve, the regular scalar gets v-string magic
 * attached.
 * <p>
 * <strong>Approximation note.</strong> PerlOnJava represents a v-string
 * as a {@link RuntimeScalar} whose {@code type} is
 * {@link RuntimeScalarType#VSTRING} and whose {@code value} is a
 * {@link String} holding the raw v-string content (e.g. for
 * {@code v1.2.3} the value is {@code "\u0001\u0002\u0003"}). The
 * textual source form ({@code "v1.2.3"}) used as v-string magic by
 * upstream Perl is <em>not</em> preserved in our representation, so we
 * use the same content bytes for both the magic blob and the regular
 * scalar body. Round-trip preserves the v-string content and its
 * VSTRING type tag, which is what most uses of v-strings care about.
 */
public final class VStringEncoder {
    private VStringEncoder() {}

    /** Emit SX_VSTRING / SX_LVSTRING + body. */
    public static void write(StorableContext c, RuntimeScalar v) {
        // Identity-key the v-string itself so a downstream identical
        // reference resolves through the seen-table. Mirrors the
        // single-tag allocation that upstream's retrieve_vstring
        // performs (the inner scalar is the only fresh SV).
        c.recordWriteSeen(v);

        String s = (String) v.value;
        // V-string content is by definition codepoints 0..255 (or
        // arbitrary code units when the source declares unicode); we
        // serialize as ISO-8859-1 to round-trip the raw byte values
        // for the common (ASCII-range) case. Higher code points get
        // truncated, matching the lossy nature of the existing v-string
        // model in PerlOnJava.
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);

        // Opcode + length prefix: SX_VSTRING (1-byte length) for short,
        // SX_LVSTRING (U32 length) for >255.
        if (bytes.length <= 255) {
            c.writeByte(Opcodes.SX_VSTRING);
            c.writeByte(bytes.length);
        } else {
            c.writeByte(Opcodes.SX_LVSTRING);
            c.writeU32Length(bytes.length);
        }
        // V-string magic blob: we use the same content bytes (see note
        // above about not preserving the textual source form).
        c.writeBytes(bytes);

        // Regular scalar body: SX_SCALAR for short, SX_LSCALAR for >255.
        // The reader will recurse via dispatch() to consume this.
        if (bytes.length <= Opcodes.LG_SCALAR) {
            c.writeByte(Opcodes.SX_SCALAR);
            c.writeByte(bytes.length);
        } else {
            c.writeByte(Opcodes.SX_LSCALAR);
            c.writeU32Length(bytes.length);
        }
        c.writeBytes(bytes);
    }
}
