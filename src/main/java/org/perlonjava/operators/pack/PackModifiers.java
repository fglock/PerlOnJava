package org.perlonjava.operators.pack;

/**
 * Holds format modifiers like endianness
 */
public class PackModifiers {
    public final boolean bigEndian;
    public final boolean littleEndian;
    public final boolean nativeSize;

    public PackModifiers(boolean bigEndian, boolean littleEndian, boolean nativeSize) {
        this.bigEndian = bigEndian;
        this.littleEndian = littleEndian;
        this.nativeSize = nativeSize;
    }

    public static PackModifiers DEFAULT = new PackModifiers(false, false, false);
}