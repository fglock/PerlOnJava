package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.perlonjava.astnode.Node;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents information about a Java class being generated.
 * This includes the class name, return label, stack level management,
 * and a stack of loop labels for managing nested loops.
 */
public class JavaClassInfo {

     public enum LocalSlotKind {
         INT,
         REF,
         MIXED
     }

     private static final boolean SLOT_KIND_TRACK = System.getenv("JPERL_SLOT_KIND_TRACK") != null;
     private static final boolean SLOT_KIND_TRACE = System.getenv("JPERL_SLOT_KIND_TRACE") != null;
     private static final boolean FAIL_UNWRITTEN_LOCAL = System.getenv("JPERL_FAIL_UNWRITTEN_LOCAL") != null;
     private static final boolean FAIL_MIXED_LOCAL = System.getenv("JPERL_FAIL_MIXED_LOCAL") != null;
     private static final String TRACE_LOCAL_SLOT_RAW = System.getenv("JPERL_TRACE_LOCAL_SLOT");
     private static final int TRACE_LOCAL_SLOT = parseTraceLocalSlot(TRACE_LOCAL_SLOT_RAW);
     private static final String TRACE_LOCAL_SLOT_CLASS_FILTER = System.getenv("JPERL_TRACE_LOCAL_SLOT_CLASS_FILTER");
     private final Map<Integer, LocalSlotKind> localSlotKinds = new HashMap<>();
     private final Set<Integer> mixedSlotWarned = new HashSet<>();
     private final Map<Integer, LocalSlotKind> unwrittenReadKinds = new HashMap<>();

     private static final ThreadLocal<Deque<Node>> currentAstNodeStack = ThreadLocal.withInitial(ArrayDeque::new);

    private static int parseTraceLocalSlot(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

     public AutoCloseable pushCurrentAstNode(Node node) {
         if (!(SLOT_KIND_TRACK || FAIL_UNWRITTEN_LOCAL || FAIL_MIXED_LOCAL || TRACE_LOCAL_SLOT >= 0)) {
             return () -> {
             };
         }
         Deque<Node> stack = currentAstNodeStack.get();
         stack.push(node);
         return () -> {
             Deque<Node> st = currentAstNodeStack.get();
             if (!st.isEmpty()) {
                 st.pop();
             }
         };
     }

     private static String formatCurrentAstNode() {
         Deque<Node> stack = currentAstNodeStack.get();
         if (stack == null || stack.isEmpty()) {
             return "<no-ast-context>";
         }
         Node n = stack.peek();
         if (n == null) {
             return "<null-ast>";
         }
         return n.getClass().getSimpleName() + "#" + n.getIndex();
     }

    /**
     * The name of the Java class.
     */
    public String javaClassName;

    /**
     * The label to return to after method execution.
     */
    public Label returnLabel;
    
    /**
     * Local variable slot for tail call trampoline - stores codeRef.
     */
    public int tailCallCodeRefSlot;
    
    /**
     * Local variable slot for tail call trampoline - stores args.
     */
    public int tailCallArgsSlot;
    
    /**
     * Local variable slot for temporarily storing marked RuntimeControlFlowList during call-site checks.
     */
    public int controlFlowTempSlot;

    public int controlFlowActionSlot;

    public int[] spillSlots;
    public int spillTop;

    public int[] intSpillSlots;
    public int intSpillTop;

    public static final class SpillRef {
        public final int slot;
        public final boolean pooled;

        public SpillRef(int slot, boolean pooled) {
            this.slot = slot;
            this.pooled = pooled;
        }
    }

    /**
     * Manages the stack level for the class.
     */
    public StackLevelManager stackLevelManager;

    /**
     * A stack of loop labels for managing nested loops.
     */
    public Deque<LoopLabels> loopLabelStack;

    public Deque<GotoLabels> gotoLabelStack;

    /**
     * Constructs a new JavaClassInfo object.
     * Initializes the class name, stack level manager, and loop label stack.
     */
    public JavaClassInfo() {
        this.javaClassName = EmitterMethodCreator.generateClassName();
        this.returnLabel = null;
        this.stackLevelManager = new StackLevelManager();
        this.loopLabelStack = new ArrayDeque<>();
        this.gotoLabelStack = new ArrayDeque<>();
        this.spillSlots = new int[0];
        this.spillTop = 0;
    }

     public void registerLocalSlotKind(int slot, LocalSlotKind kind) {
         LocalSlotKind existing = localSlotKinds.get(slot);
         if (existing == null) {
             localSlotKinds.put(slot, kind);
             return;
         }

         if (existing == kind || existing == LocalSlotKind.MIXED) {
             return;
         }

         if (FAIL_MIXED_LOCAL) {
            throw new IllegalStateException("[jperl] FAIL_MIXED_LOCAL: local slot kind became MIXED: slot=" + slot +
                    " existing=" + existing +
                    " new=" + kind +
                    " class=" + javaClassName +
                    " ast=" + formatCurrentAstNode() +
                    " inRefSpillPool=" + isInSpillSlots(slot) +
                    " inIntSpillPool=" + isInIntSpillSlots(slot));
        }

        localSlotKinds.put(slot, LocalSlotKind.MIXED);
       if (mixedSlotWarned.add(slot) && SLOT_KIND_TRACK) {
            System.err.println(
                    "[jperl] WARNING: local slot kind became MIXED: slot=" + slot +
                            " existing=" + existing +
                            " new=" + kind +
                            " class=" + javaClassName +
                            " ast=" + formatCurrentAstNode());
            if (SLOT_KIND_TRACE) {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                // Skip getStackTrace/registerLocalSlotKind/registerXxx helper frames.
                for (int i = 3; i < Math.min(stack.length, 14); i++) {
                    System.err.println("[jperl]   at " + stack[i]);
                }
            }
        }
     }

     public void registerIntLocalSlot(int slot) {
         registerLocalSlotKind(slot, LocalSlotKind.INT);
     }

     public void registerRefLocalSlot(int slot) {
        registerLocalSlotKind(slot, LocalSlotKind.REF);
    }

    public void clearLocalSlotKinds() {
        localSlotKinds.clear();
        mixedSlotWarned.clear();
        unwrittenReadKinds.clear();
    }

    private boolean isInSpillSlots(int slot) {
        if (spillSlots == null) {
            return false;
        }
        for (int s : spillSlots) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    private boolean isInIntSpillSlots(int slot) {
        if (intSpillSlots == null) {
            return false;
        }
        for (int s : intSpillSlots) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    public Map<Integer, LocalSlotKind> getLocalSlotKindsSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(localSlotKinds));
    }

    public Map<Integer, LocalSlotKind> getUnwrittenReadKindsSnapshot() {
        return Collections.unmodifiableMap(new HashMap<>(unwrittenReadKinds));
    }

    public MethodVisitor wrapWithLocalSlotTracking(MethodVisitor mv, int access, String desc) {
        // Always wrap so we can collect slot kinds for deterministic initialization.
        // Printing/throwing is gated by env vars inside the visitor.
        return new LocalSlotTrackingMethodVisitor(mv, this, access, desc);
    }

    private static final class LocalSlotTrackingMethodVisitor extends MethodVisitor {
        private final JavaClassInfo info;
        private final Set<Integer> writtenSlots = new HashSet<>();
        private final Set<Integer> readSlots = new HashSet<>();
        private final Map<Integer, String> firstReadOp = new HashMap<>();
        private final Map<Integer, String> firstReadAst = new HashMap<>();
        private int tracePrinted = 0;

        private LocalSlotTrackingMethodVisitor(MethodVisitor delegate, JavaClassInfo info, int access, String desc) {
            super(Opcodes.ASM9, delegate);
            this.info = info;

            // All JVM method parameters are always considered initialized.
            // Many generated constructors take a large number of closure env arguments.
            int slot = 0;
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (!isStatic) {
                writtenSlots.add(0); // this
                slot = 1;
            }
            if (desc != null) {
                for (Type arg : Type.getArgumentTypes(desc)) {
                    writtenSlots.add(slot);
                    slot += arg.getSize();
                }
            }
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            switch (opcode) {
                case Opcodes.ISTORE -> {
                    writtenSlots.add(var);
                    info.registerIntLocalSlot(var);
                }
                case Opcodes.ASTORE -> {
                    writtenSlots.add(var);
                    info.registerRefLocalSlot(var);
                }
                case Opcodes.ILOAD -> {
                    info.registerIntLocalSlot(var);
                    recordRead(opcode, var);
                }
                case Opcodes.ALOAD -> {
                    info.registerRefLocalSlot(var);
                    recordRead(opcode, var);
                }
                default -> {
                }
            }

            if (TRACE_LOCAL_SLOT >= 0 && var == TRACE_LOCAL_SLOT) {
                if (TRACE_LOCAL_SLOT_CLASS_FILTER == null
                        || TRACE_LOCAL_SLOT_CLASS_FILTER.isEmpty()
                        || (info.javaClassName != null && info.javaClassName.contains(TRACE_LOCAL_SLOT_CLASS_FILTER))) {
                    if (tracePrinted++ < 40) {
                        String op = opcode == Opcodes.ALOAD ? "ALOAD"
                                : (opcode == Opcodes.ILOAD ? "ILOAD"
                                : (opcode == Opcodes.ASTORE ? "ASTORE"
                                : (opcode == Opcodes.ISTORE ? "ISTORE" : String.valueOf(opcode))));
                        System.err.println("[jperl] TRACE_LOCAL_SLOT op=" + op + " slot=" + var + " class=" + info.javaClassName + " ast=" + formatCurrentAstNode());
                    }
                }
            }
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            writtenSlots.add(var);
            info.registerIntLocalSlot(var);
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitEnd() {
            // Only flag locals that are read but never written anywhere in the method.
            if (SLOT_KIND_TRACK || SLOT_KIND_TRACE || FAIL_UNWRITTEN_LOCAL) {
                for (Integer slot : readSlots) {
                    if (writtenSlots.contains(slot)) {
                        continue;
                    }
                    String op = firstReadOp.getOrDefault(slot, "<unknown>");
                    String ast = firstReadAst.getOrDefault(slot, "<no-ast-context>");
                    String msg = "[jperl] WARNING: read from local slot that was never written in this method: op=" + op +
                            " slot=" + slot + " class=" + info.javaClassName + " ast=" + ast;

                    JavaClassInfo.LocalSlotKind kind = ("ILOAD".equals(op))
                            ? JavaClassInfo.LocalSlotKind.INT
                            : JavaClassInfo.LocalSlotKind.REF;
                    info.unwrittenReadKinds.putIfAbsent(slot, kind);

                    System.err.println(msg);
                    if (FAIL_UNWRITTEN_LOCAL) {
                        throw new IllegalStateException(msg);
                    }
                }
            }
            super.visitEnd();
        }

        private void recordRead(int opcode, int var) {
            readSlots.add(var);
            if (!firstReadOp.containsKey(var)) {
                String op = opcode == Opcodes.ALOAD ? "ALOAD" : (opcode == Opcodes.ILOAD ? "ILOAD" : String.valueOf(opcode));
                firstReadOp.put(var, op);
                firstReadAst.put(var, formatCurrentAstNode());
            }
        }
    }

    public int acquireSpillSlot() {
        if (spillTop >= spillSlots.length) {
            return -1;
        }
        return spillSlots[spillTop++];
    }

    public int acquireIntSpillSlot() {
        if (intSpillSlots == null || intSpillTop >= intSpillSlots.length) {
            return -1;
        }
        return intSpillSlots[intSpillTop++];
    }

    public void releaseSpillSlot() {
        if (spillTop > 0) {
            spillTop--;
        }
    }

    public void releaseIntSpillSlot() {
        if (intSpillTop > 0) {
            intSpillTop--;
        }
    }

    public SpillRef tryAcquirePooledSpillRef() {
        int slot = acquireSpillSlot();
        if (slot < 0) {
            return null;
        }
        return new SpillRef(slot, true);
    }

    public SpillRef acquireSpillRefOrAllocate(ScopedSymbolTable symbolTable) {
        int slot = acquireSpillSlot();
        if (slot >= 0) {
            return new SpillRef(slot, true);
        }
        return new SpillRef(symbolTable.allocateLocalVariable(), false);
    }

    public void storeSpillRef(MethodVisitor mv, SpillRef ref) {
        mv.visitVarInsn(Opcodes.ASTORE, ref.slot);
    }

    public void loadSpillRef(MethodVisitor mv, SpillRef ref) {
        mv.visitVarInsn(Opcodes.ALOAD, ref.slot);
    }

    public void releaseSpillRef(SpillRef ref) {
        if (ref.pooled) {
            releaseSpillSlot();
        }
    }

    /**
     * Pushes a new set of loop labels onto the loop label stack.
     *
     * @param labelName the name of the loop label
     * @param nextLabel the label for the next iteration
     * @param redoLabel the label for redoing the current iteration
     * @param lastLabel the label for exiting the loop
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int context) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevelManager.getStackLevel(), context));
    }

    /**
     * Pushes a new set of loop labels with isTrueLoop flag.
     *
     * @param labelName     the name of the loop label
     * @param nextLabel     the label for the next iteration
     * @param redoLabel     the label for redoing the current iteration
     * @param lastLabel     the label for exiting the loop
     * @param stackLevel    the current stack level
     * @param context       the context type
     * @param isTrueLoop    whether this is a true loop (for/while/until) or pseudo-loop (do-while/bare)
     */
    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int stackLevel, int context, boolean isTrueLoop) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevel, context, isTrueLoop));
    }

    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int stackLevel, int context, boolean isTrueLoop, boolean isUnlabeledControlFlowTarget) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevel, context, isTrueLoop, isUnlabeledControlFlowTarget));
    }

    public void pushLoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int stackLevel, int context, boolean isTrueLoop, boolean isUnlabeledControlFlowTarget, boolean isRealLoop) {
        loopLabelStack.push(new LoopLabels(labelName, nextLabel, redoLabel, lastLabel, stackLevel, context, isTrueLoop, isUnlabeledControlFlowTarget, isRealLoop));
    }
    
    /**
     * Pushes a LoopLabels object onto the loop label stack.
     * This is useful when you've already constructed a LoopLabels object with a control flow handler.
     *
     * @param loopLabels the LoopLabels object to push
     */
    public void pushLoopLabels(LoopLabels loopLabels) {
        loopLabelStack.push(loopLabels);
    }

    /**
     * Pops the top set of loop labels from the loop label stack and returns it.
     *
     * @return the popped LoopLabels object
     */
    public LoopLabels popLoopLabels() {
        return loopLabelStack.pop();
    }
    
    /**
     * Gets the innermost (current) loop labels.
     * Returns null if not currently inside a loop.
     *
     * @return the innermost LoopLabels object, or null if none
     */
    public LoopLabels getInnermostLoopLabels() {
        return loopLabelStack.peek();
    }

    /**
     * Gets the parent loop labels (the loop containing the current loop).
     * Returns null if there's no parent loop.
     *
     * @return the parent LoopLabels object, or null if none
     */
    public LoopLabels getParentLoopLabels() {
        if (loopLabelStack.size() < 2) {
            return null;
        }
        // Convert deque to array to access second-to-top element
        LoopLabels[] array = loopLabelStack.toArray(new LoopLabels[0]);
        return array[array.length - 2];
    }

    /**
     * Finds loop labels by their name.
     *
     * @param labelName the name of the loop label to find
     * @return the LoopLabels object with the specified name, or the top of the stack if the name is null
     */
    public LoopLabels findLoopLabelsByName(String labelName) {
        if (labelName == null) {
            return loopLabelStack.peek();
        }
        for (LoopLabels loopLabels : loopLabelStack) {
            if (loopLabels.labelName != null && loopLabels.labelName.equals(labelName)) {
                return loopLabels;
            }
        }
        return null;
    }

    /**
     * Finds the innermost "true" loop labels.
     * This skips pseudo-loops like bare/labeled blocks (e.g. SKIP: { ... }) that
     * may be present on the loop label stack to support redo/last/next on blocks.
     *
     * For unlabeled next/last/redo, Perl semantics target the nearest enclosing
     * true loop, not a labeled block used for SKIP.
     *
     * @return the innermost LoopLabels with isTrueLoop=true, or null if none
     */
    public LoopLabels findInnermostTrueLoopLabels() {
        // Prefer a real loop (for/foreach/while/until) if one exists.
        for (LoopLabels loopLabels : loopLabelStack) {
            if (loopLabels != null && loopLabels.isUnlabeledControlFlowTarget && loopLabels.isRealLoop) {
                return loopLabels;
            }
        }

        // Fallback: allow a block to act as a loop target only when there's no real loop.
        for (LoopLabels loopLabels : loopLabelStack) {
            if (loopLabels != null && loopLabels.isUnlabeledControlFlowTarget) {
                return loopLabels;
            }
        }

        return null;
    }

    public void pushGotoLabels(String labelName, Label gotoLabel) {
        gotoLabelStack.push(new GotoLabels(labelName, gotoLabel, stackLevelManager.getStackLevel()));
    }

    public GotoLabels findGotoLabelsByName(String labelName) {
        for (GotoLabels gotoLabels : gotoLabelStack) {
            if (gotoLabels.labelName.equals(labelName)) {
                return gotoLabels;
            }
        }
        return null;
    }

    public void popGotoLabels() {
        gotoLabelStack.pop();
    }

    /**
     * Increments the stack level by a specified amount.
     *
     * @param level the amount to increment the stack level by
     */
    public void incrementStackLevel(int level) {
        stackLevelManager.increment(level);
    }

    /**
     * Decrements the stack level by a specified amount.
     *
     * @param level the amount to decrement the stack level by
     */
    public void decrementStackLevel(int level) {
        stackLevelManager.decrement(level);
    }

    /**
     * Resets the stack level to its initial state.
     */
    public void resetStackLevel() {
        stackLevelManager.reset();
    }

    /**
     * Returns a string representation of the JavaClassInfo object.
     *
     * @return a string representation of the JavaClassInfo object
     */
    @Override
    public String toString() {
        return "JavaClassInfo{\n" +
                "    javaClassName='" + javaClassName + "',\n" +
                "    returnLabel=" + (returnLabel != null ? returnLabel.toString() : "null") + ",\n" +
                "    asmStackLevel=" + stackLevelManager.getStackLevel() + ",\n" +
                "    loopLabelStack=" + loopLabelStack + "\n" +
                "    gotoLabelStack=" + gotoLabelStack + "\n" +
                "}";
    }
}
