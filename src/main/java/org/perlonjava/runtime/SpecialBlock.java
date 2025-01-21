package org.perlonjava.runtime;

/**
 * The SpecialBlock class manages different types of code blocks (end, init, check, and unitcheck)
 * that can be saved and executed in a specific order. This class provides methods to save and run
 * these blocks, ensuring they are executed based on their defined order and conditions.
 * <p>
 * The order of execution is influenced by how blocks are added:
 * - Blocks added with `push` are executed in Last-In-First-Out (LIFO) order.
 * - Blocks added with `unshift` are executed in First-In-First-Out (FIFO) order.
 */
public class SpecialBlock {

    // Arrays to store different types of blocks
    public static RuntimeArray endBlocks = new RuntimeArray();
    public static RuntimeArray initBlocks = new RuntimeArray();
    public static RuntimeArray checkBlocks = new RuntimeArray();

    /**
     * Saves a code reference to the endBlocks array.
     * Blocks are added using `push`, meaning they will be executed in LIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveEndBlock(RuntimeScalar codeRef) {
        RuntimeArray.push(endBlocks, codeRef);
    }

    /**
     * Saves a code reference to the initBlocks array.
     * Blocks are added using `unshift`, meaning they will be executed in FIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveInitBlock(RuntimeScalar codeRef) {
        RuntimeArray.unshift(initBlocks, codeRef);
    }

    /**
     * Saves a code reference to the checkBlocks array.
     * Blocks are added using `push`, meaning they will be executed in LIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveCheckBlock(RuntimeScalar codeRef) {
        RuntimeArray.push(checkBlocks, codeRef);
    }

    /**
     * Executes all code blocks stored in the endBlocks array in LIFO order.
     */
    public static void runEndBlocks() {
        while (!endBlocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(endBlocks);
            if (block.getDefinedBoolean()) {
                RuntimeCode.apply(block, new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the initBlocks array in FIFO order.
     */
    public static void runInitBlocks() {
        while (!initBlocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(initBlocks);
            if (block.getDefinedBoolean()) {
                RuntimeCode.apply(block, new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the checkBlocks array in LIFO order.
     */
    public static void runCheckBlocks() {
        while (!checkBlocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(checkBlocks);
            if (block.getDefinedBoolean()) {
                RuntimeCode.apply(block, new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the provided unitcheckBlocks array in LIFO order.
     *
     * @param unitcheckBlocks the array of code blocks to be executed
     */
    public static void runUnitcheckBlocks(RuntimeArray unitcheckBlocks) {
        while (!unitcheckBlocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(unitcheckBlocks);
            if (block.getDefinedBoolean()) {
                RuntimeCode.apply(block, new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }
}
