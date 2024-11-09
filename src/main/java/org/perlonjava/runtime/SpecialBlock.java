package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.getGlobalArray;

public class SpecialBlock {

    public static RuntimeArray endBlocks = new RuntimeArray();
    public static RuntimeArray initBlocks = new RuntimeArray();
    public static RuntimeArray checkBlocks = new RuntimeArray();

    public static void saveEndBlock(RuntimeScalar codeRef) {
        endBlocks.push(codeRef);
    }

    public static void saveInitBlock(RuntimeScalar codeRef) {
        initBlocks.push(codeRef);
    }

    public static void saveCheckBlock(RuntimeScalar codeRef) {
        checkBlocks.unshift(codeRef);  // runs in LIFO order
    }

    public static void runEndBlocks() {
        while (endBlocks.size() > 0) {
            RuntimeScalar block = endBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    public static void runInitBlocks() {
        while (initBlocks.size() > 0) {
            RuntimeScalar block = initBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    public static void runCheckBlocks() {
        while (checkBlocks.size() > 0) {
            RuntimeScalar block = checkBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }
}
