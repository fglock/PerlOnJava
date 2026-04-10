package org.perlonjava.runtime.runtimetypes;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

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

    // State is now held per-PerlRuntime. These accessors delegate to the current runtime.
    // Public getters preserve backward compatibility for any code that reads these fields.
    public static RuntimeArray getEndBlocks() { return PerlRuntime.current().endBlocks; }
    public static RuntimeArray getInitBlocks() { return PerlRuntime.current().initBlocks; }
    public static RuntimeArray getCheckBlocks() { return PerlRuntime.current().checkBlocks; }

    /**
     * Saves a code reference to the endBlocks array.
     * Blocks are added using `push`, meaning they will be executed in LIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveEndBlock(RuntimeScalar codeRef) {
        RuntimeArray.push(getEndBlocks(), codeRef);
    }

    /**
     * Saves a code reference to the initBlocks array.
     * Blocks are added using `unshift`, meaning they will be executed in FIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveInitBlock(RuntimeScalar codeRef) {
        RuntimeArray.unshift(getInitBlocks(), codeRef);
    }

    /**
     * Saves a code reference to the checkBlocks array.
     * Blocks are added using `push`, meaning they will be executed in LIFO order.
     *
     * @param codeRef the code reference to be saved
     */
    public static void saveCheckBlock(RuntimeScalar codeRef) {
        RuntimeArray.push(getCheckBlocks(), codeRef);
    }

    /**
     * Executes all code blocks stored in the endBlocks array in LIFO order.
     * 
     * @param resetChildStatus if true, reset $? to 0 before running END blocks (normal exit).
     *                         if false, preserve $? (die/exception path).
     */
    public static void runEndBlocks(boolean resetChildStatus) {
        if (resetChildStatus) {
            getGlobalVariable("main::?").set(0);
        }
        RuntimeArray blocks = getEndBlocks();
        while (!blocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(blocks);
            if (block.getDefinedBoolean()) {
                RuntimeCode.apply(block, new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the endBlocks array in LIFO order.
     * Resets $? to 0 before running (normal exit behavior).
     */
    public static void runEndBlocks() {
        runEndBlocks(true);
    }

    /**
     * Executes all code blocks stored in the initBlocks array in FIFO order.
     */
    public static void runInitBlocks() {
        RuntimeArray blocks = getInitBlocks();
        while (!blocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(blocks);
            if (block.getDefinedBoolean()) {
                RuntimeCode.apply(block, new RuntimeArray(), RuntimeContextType.VOID);
            }
        }
    }

    /**
     * Executes all code blocks stored in the checkBlocks array in LIFO order.
     */
    public static void runCheckBlocks() {
        RuntimeArray blocks = getCheckBlocks();
        while (!blocks.isEmpty()) {
            RuntimeScalar block = RuntimeArray.pop(blocks);
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
