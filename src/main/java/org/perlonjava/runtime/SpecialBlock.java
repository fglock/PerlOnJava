package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.getGlobalArray;

public class SpecialBlock {

    public static final String endBlockArrayName = Character.toString(0) + "::EndBlocks";
    public static int endBlockIndex = -1;

    static {
        // Initialize END block list
        getGlobalArray(SpecialBlock.endBlockArrayName);
    }

    public static int getEndBlockIndex() {
        return ++endBlockIndex;
    }

    public static void runEndBlocks() {
        RuntimeArray endBlocks = getGlobalArray(SpecialBlock.endBlockArrayName);
        while (endBlocks.size() > 0) {
            endBlockIndex--;
            RuntimeScalar block = endBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }
}
