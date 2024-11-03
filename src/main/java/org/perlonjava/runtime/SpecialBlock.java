package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.getGlobalArray;

public class SpecialBlock {

    public static final String endBlockArrayName = Character.toString(0) + "::EndBlocks";

    public static void saveEndBlock(RuntimeScalar codeRef) {
        getGlobalArray(SpecialBlock.endBlockArrayName).push(codeRef);
    }

    public static void runEndBlocks() {
        RuntimeArray endBlocks = getGlobalArray(SpecialBlock.endBlockArrayName);
        while (endBlocks.size() > 0) {
            RuntimeScalar block = endBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }
}
