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
        endBlocks.push(codeRef);
    }

    /**
     * Saves a code reference to the initBlocks array.
     * Blocks are added using `unshift`, meaning they will be executed in FIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveInitBlock(RuntimeScalar codeRef) {
        initBlocks.unshift(codeRef);
    }

    /**
     * Saves a code reference to the checkBlocks array.
     * Blocks are added using `push`, meaning they will be executed in LIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveCheckBlock(RuntimeScalar codeRef) {
        checkBlocks.push(codeRef);
    }

    /**
     * Executes all code blocks stored in the endBlocks array in LIFO order.
     */
    public static void runEndBlocks() {
        while (endBlocks.size() > 0) {
            RuntimeScalar block = endBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the initBlocks array in FIFO order.
     */
    public static void runInitBlocks() {
        while (initBlocks.size() > 0) {
            RuntimeScalar block = initBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the checkBlocks array in LIFO order.
     */
    public static void runCheckBlocks() {
        while (checkBlocks.size() > 0) {
            RuntimeScalar block = checkBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the provided unitcheckBlocks array in LIFO order.
     *
     * @param unitcheckBlocks the array of code blocks to be executed
     */
    public static void runUnitcheckBlocks(RuntimeArray unitcheckBlocks) {
        while (unitcheckBlocks.size() > 0) {
            RuntimeScalar block = unitcheckBlocks.pop();
            if (block.getDefinedBoolean()) {
                block.apply(new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }
}
