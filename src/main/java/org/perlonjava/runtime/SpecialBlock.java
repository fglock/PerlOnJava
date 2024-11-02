package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.getGlobalArray;

public class SpecialBlock {

    public static final String endBlockArray = Character.toString(0) + "::EndBlocks";
    public static int endBlockIndex = 0;

    static {
        // Initialize END block list
        getGlobalArray(SpecialBlock.endBlockArray);
    }

    public static void runEndBlocks() {
        for (RuntimeScalar block : getGlobalArray(SpecialBlock.endBlockArray).elements.reversed()) {
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
                block.undefine();
            }
        }
    }
}
