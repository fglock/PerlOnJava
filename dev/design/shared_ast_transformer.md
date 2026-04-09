# Shared AST Transformer for Backend Parity

## Problem Statement

The bytecode interpreter and JVM backend have recurring parity issues due to duplicated compilation logic. Recent bugs include:

1. **Context propagation errors** - Array/hash element assignments not preserving correct context
2. **Lvalue semantics** - `($hash{KEY} = $val) =~ s///` modifying copies instead of elements
3. **Hash literal context** - `{ @list }` returning array count instead of elements
4. **Operator context** - Various operators compiled with wrong scalar/list/void context

These bugs share a root cause: the bytecode compiler (`CompileAssignment.java`, `CompileBinaryOperator.java`, etc.) reimplements logic that already exists in the JVM emitter, introducing subtle differences.

## Current Architecture

```
                    ┌─────────────────┐
                    │   Parser/Lexer  │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │    Raw AST      │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
    ┌─────────────────┐           ┌─────────────────┐
    │  JVM Emitter    │           │ Bytecode Compiler│
    │  (EmitterVisitor│           │ (BytecodeCompiler│
    │   + helpers)    │           │  + helpers)      │
    └────────┬────────┘           └────────┬────────┘
             │                             │
             ▼                             ▼
    ┌─────────────────┐           ┌─────────────────┐
    │  JVM Bytecode   │           │ Interpreter BC  │
    └─────────────────┘           └─────────────────┘
```

**Problems:**
- Both backends make independent decisions about context, lvalues, operator semantics
- Bug fixes must be applied twice (often with subtle differences)
- Test coverage doesn't catch parity issues until runtime

## Proposed Architecture

```
                    ┌─────────────────┐
                    │   Parser/Lexer  │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │    Raw AST      │
                    └────────┬────────┘
                             │
                             ▼
              ┌───────────────────────────────┐
              │      AST Transformer          │
              │  ┌─────────────────────────┐  │
              │  │ 1. PragmaResolver       │  │  ◄── strict, warnings, features
              │  │ 2. VariableResolver     │  │  ◄── variable identity, closures
              │  │ 3. LabelCollector       │  │  ◄── goto/next/last/redo targets
              │  │ 4. BlockAnalyzer        │  │  ◄── local, regex detection
              │  │ 5. ContextResolver      │  │  ◄── scalar/list/void context
              │  │ 6. LvalueResolver       │  │  ◄── lvalue annotations
              │  │ 7. ConstantFolder       │  │  ◄── existing optimization
              │  │ 8. WarningEmitter       │  │  ◄── compile-time warnings
              │  └─────────────────────────┘  │
              └───────────────┬───────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  Normalized AST │  ◄── Fully resolved, annotated AST
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
    ┌─────────────────┐           ┌─────────────────┐
    │  JVM Emitter    │           │ Bytecode Compiler│
    │  (simplified)   │           │  (simplified)    │
    └────────┬────────┘           └────────┬────────┘
             │                             │
             ▼                             ▼
    ┌─────────────────┐           ┌─────────────────┐
    │  JVM Bytecode   │           │ Interpreter BC  │
    └─────────────────┘           └─────────────────┘
```

## AST Annotation Structure

### Existing Infrastructure in AbstractNode

The `AbstractNode` class already provides annotation infrastructure:

```java
// src/main/java/org/perlonjava/frontend/astnode/AbstractNode.java
public abstract class AbstractNode implements Node {
    // Token position for error messages
    public int tokenIndex;
    
    // Generic annotation map (lazy initialized)
    public Map<String, Object> annotations;
    
    // Bit flags for common boolean annotations (memory efficient)
    private int internalAnnotationFlags;
    private static final int FLAG_BLOCK_ALREADY_REFACTORED = 1;
    private static final int FLAG_QUEUED_FOR_REFACTOR = 2;
    private static final int FLAG_CHUNK_ALREADY_REFACTORED = 4;
    
    // Cached analysis results (avoid repeated traversal)
    private int cachedBytecodeSize = Integer.MIN_VALUE;      // JVM-specific
    private byte cachedHasAnyControlFlow = -1;               // Tri-state: -1=unset, 0=false, 1=true
    
    // API methods
    public void setAnnotation(String key, Object value);
    public Object getAnnotation(String key);
    public boolean getBooleanAnnotation(String key);
}
```

### Extending for Shared Transformer

**Option A: Add typed fields** (memory efficient, compile-time type checking)
```java
// Add to AbstractNode
private byte cachedContext = -1;        // RuntimeContextType as byte
private byte cachedLvalueType = -1;     // SCALAR, LIST, or VOID
private boolean isLvalue;
```

**Option B: Use annotation map** (flexible, no schema changes)
```java
node.setAnnotation("context", RuntimeContextType.SCALAR);
node.setAnnotation("isLvalue", true);
```

**Recommendation**: Use Option A for frequently-accessed annotations (context, lvalue). These are read by every emit operation, so field access is significantly faster than map lookup.

### Proposed ASTAnnotation Class

```java
public class ASTAnnotation {
    // Context resolution
    public RuntimeContextType context;      // SCALAR, LIST, VOID - context this node executes in
    public RuntimeContextType callContext;  // Context to pass to subroutine calls (wantarray)
    public boolean isLvalue;                // Must return mutable reference
    public boolean needsAutovivification;   // May create container elements
    
    // Operator/function argument contexts
    public ArgumentContexts argContexts;    // Per-argument context requirements
    
    // Variable resolution
    public VariableBinding binding;         // Links to declaration
    public boolean isCaptured;              // Used by inner closure
    public int closureDepth;                // Nesting level for captures
    public List<VariableBinding> capturedVariables;  // For subroutines: captured vars
    
    // Block analysis (for save/restore optimization)
    public boolean containsLocal;           // Block has 'local' declarations
    public List<LocalDeclaration> localDeclarations;  // Details of each local
    public boolean containsRegex;           // Block uses regex (needs RegexState save)
    
    // Pragma tracking
    public PragmaState pragmas;             // strict, warnings, features
    
    // Labels
    public LabelInfo labelTarget;           // For goto/next/last/redo
    
    // Optimization hints
    public boolean isConstant;              // Can be compile-time evaluated
    public RuntimeScalar constantValue;     // Folded value if constant
}

public class ArgumentContexts {
    // For operators/functions with specific argument context requirements
    public RuntimeContextType[] contexts;   // Context for each argument position
    public boolean lastArgTakesRemainder;   // Last arg consumes remaining (like print)
    
    // Examples:
    // push(@array, LIST)  -> [SCALAR, LIST]  (array ref, then list)
    // substr($str, ...)   -> [SCALAR, SCALAR, SCALAR, SCALAR]
    // print(LIST)         -> [LIST], lastArgTakesRemainder=true
    // map { } LIST        -> [SCALAR, LIST] (block returns scalar per item)
    // sort { } LIST       -> [SCALAR, LIST] (comparator returns scalar)
}

public class VariableBinding {
    public String name;                     // Variable name with sigil
    public int declarationId;               // Unique ID for this declaration
    public Node declarationNode;            // Points to my/our/local/state
    public ScopeType scopeType;             // LEXICAL, PACKAGE, DYNAMIC
    public boolean isState;                 // state variable
}

public class PragmaState {
    public boolean strictVars;
    public boolean strictRefs;
    public boolean strictSubs;
    public Set<String> enabledWarnings;
    public Set<String> disabledWarnings;
    public Set<String> enabledFeatures;     // say, fc, signatures, etc.
}

public class LabelInfo {
    public String labelName;
    public Node targetNode;                 // Loop or block node
    public boolean isLoopLabel;             // For next/last/redo
}
```

## Transformer Phases

### Phase 1: PragmaResolver

Track `use strict`, `use warnings`, `use feature` across scopes:

```java
public class PragmaResolver extends ASTTransformPass {
    private Deque<PragmaState> pragmaStack = new ArrayDeque<>();
    
    @Override
    public void visit(BlockNode node) {
        // Push inherited pragma state
        pragmaStack.push(currentPragmas().copy());
        
        // Process use/no statements
        for (Node stmt : node.elements) {
            if (isPragmaStatement(stmt)) {
                updatePragmas(stmt);
            }
            // Annotate each node with current pragma state
            stmt.annotation.pragmas = currentPragmas().copy();
            stmt.accept(this);
        }
        
        pragmaStack.pop();
    }
}
```

### Phase 2: VariableResolver

Link variable uses to declarations, detect closures:

```java
public class VariableResolver extends ASTTransformPass {
    private Deque<Map<String, VariableBinding>> scopeStack = new ArrayDeque<>();
    private int nextBindingId = 0;
    private int currentClosureDepth = 0;
    
    @Override
    public void visit(OperatorNode node) {
        if (node.operator.equals("my") || node.operator.equals("our") ||
            node.operator.equals("local") || node.operator.equals("state")) {
            // Create new binding
            VariableBinding binding = new VariableBinding();
            binding.declarationId = nextBindingId++;
            binding.declarationNode = node;
            binding.name = extractVariableName(node);
            binding.scopeType = getScopeType(node.operator);
            binding.isState = node.operator.equals("state");
            
            currentScope().put(binding.name, binding);
            node.annotation.binding = binding;
        }
    }
    
    @Override
    public void visit(IdentifierNode node) {
        // Look up variable in scope chain
        String name = node.name;
        for (int depth = 0; depth < scopeStack.size(); depth++) {
            Map<String, VariableBinding> scope = scopeStack.get(depth);
            if (scope.containsKey(name)) {
                VariableBinding binding = scope.get(name);
                node.annotation.binding = binding;
                
                // Check if this is a closure capture
                if (depth > 0 && isInSubroutine()) {
                    binding.isCaptured = true;
                    node.annotation.isCaptured = true;
                    node.annotation.closureDepth = depth;
                }
                return;
            }
        }
        // Not found - package variable or error (checked by strict)
    }
    
    @Override
    public void visit(SubroutineNode node) {
        currentClosureDepth++;
        scopeStack.push(new HashMap<>());
        
        // Process parameters and body
        visitChildren(node);
        
        // Collect captured variables for closure
        node.annotation.capturedVariables = collectCaptures(node);
        
        scopeStack.pop();
        currentClosureDepth--;
    }
}
```

### Phase 3: LabelCollector

Collect labels and link control flow:

```java
public class LabelCollector extends ASTTransformPass {
    private Map<String, Node> labelMap = new HashMap<>();
    private Deque<Node> loopStack = new ArrayDeque<>();
    
    @Override
    public void visit(ForNode node) {
        if (node.label != null) {
            labelMap.put(node.label, node);
        }
        loopStack.push(node);
        visitChildren(node);
        loopStack.pop();
    }
    
    @Override
    public void visit(OperatorNode node) {
        if (isControlFlow(node.operator)) {  // next, last, redo, goto
            String targetLabel = extractLabel(node);
            
            if (targetLabel != null) {
                // Explicit label
                Node target = labelMap.get(targetLabel);
                if (target == null) {
                    emitError("Label not found: " + targetLabel, node);
                }
                node.annotation.labelTarget = new LabelInfo(targetLabel, target);
            } else if (!node.operator.equals("goto")) {
                // Implicit - use innermost loop
                if (loopStack.isEmpty()) {
                    emitError("Can't \"" + node.operator + "\" outside a loop", node);
                }
                node.annotation.labelTarget = new LabelInfo(null, loopStack.peek());
            }
        }
    }
}
```

### Phase 3.5: BlockAnalyzer

Analyze blocks for `local` declarations and regex usage. Integrates with existing visitors:
- `FindDeclarationVisitor` - Locates `local`/`my` declarations for dynamic scoping
- `RegexUsageDetector` - Detects regex operations for state save/restore optimization

```java
public class BlockAnalyzer extends ASTTransformPass {
    
    @Override
    public void visit(BlockNode node) {
        // Detect if block contains 'local' declarations
        // Used by backends to emit dynamic variable save/restore
        OperatorNode localOp = FindDeclarationVisitor.findOperator(node, "local");
        if (localOp != null) {
            node.annotation.containsLocal = true;
            node.annotation.localDeclarations = collectLocalDeclarations(node);
        }
        
        // Detect if block contains regex operations
        // Used by backends to emit regex state save/restore only when needed
        // (optimization: avoid unnecessary RegexState snapshots)
        node.annotation.containsRegex = RegexUsageDetector.containsRegexOperation(node);
        
        visitChildren(node);
    }
    
    @Override
    public void visit(SubroutineNode node) {
        // Subroutines have their own regex state scope
        // Don't propagate containsRegex from nested subs to outer blocks
        node.annotation.containsRegex = RegexUsageDetector.containsRegexOperation(node.block);
        
        // Collect local declarations for the subroutine body
        OperatorNode localOp = FindDeclarationVisitor.findOperator(node.block, "local");
        if (localOp != null) {
            node.annotation.containsLocal = true;
            node.annotation.localDeclarations = collectLocalDeclarations(node.block);
        }
        
        visitChildren(node);
    }
    
    @Override
    public void visit(For1Node node) {
        // For loops may have implicit local ($_ in for(@list))
        // and explicit local declarations in body
        analyzeLoopBlock(node, node.body);
        visitChildren(node);
    }
    
    @Override
    public void visit(TryNode node) {
        // try/catch/finally blocks each have their own scope
        if (node.tryBlock != null) {
            node.tryBlock.annotation.containsRegex = 
                RegexUsageDetector.containsRegexOperation(node.tryBlock);
        }
        if (node.catchBlock != null) {
            node.catchBlock.annotation.containsRegex = 
                RegexUsageDetector.containsRegexOperation(node.catchBlock);
        }
        visitChildren(node);
    }
    
    private List<LocalDeclaration> collectLocalDeclarations(Node block) {
        List<LocalDeclaration> locals = new ArrayList<>();
        // Walk block to find all 'local' operators
        // Record: variable name, original value location, scope end point
        collectLocalsRecursive(block, locals);
        return locals;
    }
    
    private void analyzeLoopBlock(Node loop, Node body) {
        body.annotation.containsRegex = RegexUsageDetector.containsRegexOperation(body);
        OperatorNode localOp = FindDeclarationVisitor.findOperator(body, "local");
        if (localOp != null) {
            body.annotation.containsLocal = true;
        }
    }
}

public class LocalDeclaration {
    public String variableName;         // e.g., "$foo", "@bar", "%baz"
    public VariableBinding binding;     // Links to the package/global variable
    public Node declarationNode;        // The 'local' operator node
    public boolean needsRestore;        // True if value must be restored at scope end
}
```

**Why this matters:**

1. **`local` operator tracking** enables correct dynamic scoping:
   - Save original value at block entry
   - Restore at block exit (including non-local exits via die/next/last)
   - Both backends must emit identical save/restore logic

2. **Regex usage detection** enables optimization:
   - Only emit `RegexState.save()`/`restore()` for blocks that use regex
   - Avoids unnecessary snapshots of `$1`, `$2`, `@+`, `@-`, etc.
   - Subroutine boundaries reset the optimization (each sub has own scope)

3. **Integration with existing visitors** ensures consistency:
   - `FindDeclarationVisitor` already handles the AST traversal
   - `RegexUsageDetector` already handles subroutine boundary detection
   - Transformer just annotates; backends read annotations

### Phase 4: ContextResolver

Propagate context through the AST, including operator argument contexts and subroutine call contexts:

```java
public class ContextResolver extends ASTTransformPass {
    
    // Built-in operator argument context specifications
    private static final Map<String, ArgumentContexts> BUILTIN_CONTEXTS = Map.of(
        // Array/hash operations
        "push",    new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // push @arr, LIST
        "unshift", new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // unshift @arr, LIST
        "pop",     new ArgumentContexts(new int[]{SCALAR}),               // pop @arr
        "shift",   new ArgumentContexts(new int[]{SCALAR}),               // shift @arr
        "splice",  new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR, LIST}, true),
        "delete",  new ArgumentContexts(new int[]{SCALAR}),               // delete $h{k}
        "exists",  new ArgumentContexts(new int[]{SCALAR}),               // exists $h{k}
        "keys",    new ArgumentContexts(new int[]{SCALAR}),               // keys %h (hash in scalar ctx)
        "values",  new ArgumentContexts(new int[]{SCALAR}),               // values %h
        "each",    new ArgumentContexts(new int[]{SCALAR}),               // each %h
        
        // String operations
        "substr",  new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR, SCALAR}),
        "index",   new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR}),
        "rindex",  new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR}),
        "sprintf", new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // sprintf FMT, LIST
        "pack",    new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // pack TEMPLATE, LIST
        "unpack",  new ArgumentContexts(new int[]{SCALAR, SCALAR}),
        "split",   new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR}),
        "join",    new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // join SEP, LIST
        
        // I/O operations  
        "print",   new ArgumentContexts(new int[]{LIST}, true),           // print LIST
        "printf",  new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // printf FMT, LIST
        "say",     new ArgumentContexts(new int[]{LIST}, true),           // say LIST
        "read",    new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR, SCALAR}),
        "sysread", new ArgumentContexts(new int[]{SCALAR, SCALAR, SCALAR, SCALAR}),
        "open",    new ArgumentContexts(new int[]{SCALAR, SCALAR, LIST}, true),
        
        // List operations
        "map",     new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // map BLOCK LIST
        "grep",    new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // grep BLOCK LIST
        "sort",    new ArgumentContexts(new int[]{SCALAR, LIST}, true),   // sort BLOCK LIST
        "reverse", new ArgumentContexts(new int[]{LIST}, true),           // reverse LIST
        
        // Misc
        "scalar",  new ArgumentContexts(new int[]{SCALAR}),               // force scalar context
        "wantarray", new ArgumentContexts(new int[]{}),                   // no args
        "caller",  new ArgumentContexts(new int[]{SCALAR}),
        "die",     new ArgumentContexts(new int[]{LIST}, true),
        "warn",    new ArgumentContexts(new int[]{LIST}, true),
        "return",  new ArgumentContexts(new int[]{LIST}, true)            // return LIST
    );
    
    @Override
    public void visit(BinaryOperatorNode node) {
        switch (node.operator) {
            case "=" -> {
                // RHS gets context from assignment target
                node.left.annotation.context = RuntimeContextType.SCALAR;
                // For list assignment: @a = expr, RHS is LIST
                if (isListTarget(node.left)) {
                    node.right.annotation.context = RuntimeContextType.LIST;
                } else {
                    node.right.annotation.context = RuntimeContextType.SCALAR;
                }
            }
            case "=~", "!~" -> {
                // LHS always scalar, RHS is the regex
                node.left.annotation.context = RuntimeContextType.SCALAR;
                node.right.annotation.context = RuntimeContextType.SCALAR;
            }
            case "||", "&&", "//" -> {
                // LHS scalar for boolean test, RHS inherits outer context
                node.left.annotation.context = RuntimeContextType.SCALAR;
                node.right.annotation.context = node.annotation.context;
                // Call context propagates to RHS for wantarray
                node.right.annotation.callContext = node.annotation.callContext;
            }
            case "," -> {
                // In list context, both sides are list
                // In scalar context, LHS is void, RHS is scalar
                if (node.annotation.context == RuntimeContextType.LIST) {
                    node.left.annotation.context = RuntimeContextType.LIST;
                    node.right.annotation.context = RuntimeContextType.LIST;
                } else {
                    node.left.annotation.context = RuntimeContextType.VOID;
                    node.right.annotation.context = RuntimeContextType.SCALAR;
                }
            }
            case "?" -> {
                // Ternary: condition is scalar, branches inherit outer context
                // $cond ? $true : $false
                node.left.annotation.context = RuntimeContextType.SCALAR;
                // true/false branches get outer context and call context
            }
        }
        visitChildren(node);
    }
    
    @Override
    public void visit(OperatorNode node) {
        // Look up built-in operator context requirements
        ArgumentContexts argCtx = BUILTIN_CONTEXTS.get(node.operator);
        if (argCtx != null) {
            node.annotation.argContexts = argCtx;
            applyArgumentContexts(node, argCtx);
        }
        
        // Special cases
        switch (node.operator) {
            case "map", "grep" -> {
                // Block executes in scalar context (returns one value per iteration)
                // List argument is in list context
                if (node.operand instanceof ListNode args && args.elements.size() >= 2) {
                    args.elements.get(0).annotation.context = RuntimeContextType.SCALAR;
                    for (int i = 1; i < args.elements.size(); i++) {
                        args.elements.get(i).annotation.context = RuntimeContextType.LIST;
                    }
                }
            }
            case "sort" -> {
                // Comparator block returns scalar (-1, 0, 1)
                // List argument is in list context
                if (node.operand instanceof ListNode args && args.elements.size() >= 2) {
                    if (args.elements.get(0) instanceof BlockNode) {
                        args.elements.get(0).annotation.context = RuntimeContextType.SCALAR;
                    }
                    for (int i = 1; i < args.elements.size(); i++) {
                        args.elements.get(i).annotation.context = RuntimeContextType.LIST;
                    }
                }
            }
            case "scalar" -> {
                // Forces scalar context on argument
                if (node.operand != null) {
                    node.operand.annotation.context = RuntimeContextType.SCALAR;
                }
            }
        }
        
        visitChildren(node);
    }
    
    @Override
    public void visit(SubroutineCallNode node) {
        // Subroutine call: pass outer context as call context (for wantarray)
        node.annotation.callContext = node.annotation.context;
        
        // Arguments are evaluated in list context by default
        if (node.arguments != null) {
            node.arguments.annotation.context = RuntimeContextType.LIST;
        }
        
        visitChildren(node);
    }
    
    @Override
    public void visit(HashLiteralNode node) {
        // Hash literal elements are always in LIST context
        // so @array expands to elements, not count
        for (Node element : node.elements) {
            element.annotation.context = RuntimeContextType.LIST;
        }
        visitChildren(node);
    }
    
    @Override
    public void visit(ArrayLiteralNode node) {
        // Array literal elements are always in LIST context
        for (Node element : node.elements) {
            element.annotation.context = RuntimeContextType.LIST;
        }
        visitChildren(node);
    }
    
    @Override
    public void visit(TernaryOperatorNode node) {
        // Condition is scalar, branches inherit outer context and call context
        node.condition.annotation.context = RuntimeContextType.SCALAR;
        node.trueExpr.annotation.context = node.annotation.context;
        node.trueExpr.annotation.callContext = node.annotation.callContext;
        node.falseExpr.annotation.context = node.annotation.context;
        node.falseExpr.annotation.callContext = node.annotation.callContext;
        visitChildren(node);
    }
    
    @Override
    public void visit(BlockNode node) {
        // Last statement of block inherits block's context
        if (!node.elements.isEmpty()) {
            int last = node.elements.size() - 1;
            for (int i = 0; i < last; i++) {
                node.elements.get(i).annotation.context = RuntimeContextType.VOID;
            }
            node.elements.get(last).annotation.context = node.annotation.context;
            node.elements.get(last).annotation.callContext = node.annotation.callContext;
        }
        visitChildren(node);
    }
    
    private void applyArgumentContexts(OperatorNode node, ArgumentContexts argCtx) {
        if (node.operand instanceof ListNode args) {
            for (int i = 0; i < args.elements.size(); i++) {
                if (i < argCtx.contexts.length) {
                    args.elements.get(i).annotation.context = 
                        RuntimeContextType.fromInt(argCtx.contexts[i]);
                } else if (argCtx.lastArgTakesRemainder && argCtx.contexts.length > 0) {
                    // Remaining args get same context as last specified
                    args.elements.get(i).annotation.context = 
                        RuntimeContextType.fromInt(argCtx.contexts[argCtx.contexts.length - 1]);
                }
            }
        }
    }
}
```

### Phase 5: LvalueResolver

Mark nodes that must return lvalues:

```java
public class LvalueResolver extends ASTTransformPass {
    
    @Override
    public void visit(BinaryOperatorNode node) {
        if (node.operator.equals("=~") && isModifyingRegex(node.right)) {
            // s///, tr/// modify their target
            markAsLvalue(node.left);
        }
        
        if (node.operator.equals("=")) {
            // Assignment result is lvalue
            node.annotation.isLvalue = true;
            markAsLvalue(node.left);
        }
        
        if (isCompoundAssignment(node.operator)) {
            // +=, -=, etc.
            markAsLvalue(node.left);
        }
        
        visitChildren(node);
    }
    
    @Override
    public void visit(OperatorNode node) {
        if (node.operator.equals("++") || node.operator.equals("--")) {
            markAsLvalue(node.operand);
        }
        
        // substr, vec in lvalue context
        if (node.annotation.isLvalue && 
            (node.operator.equals("substr") || node.operator.equals("vec"))) {
            // Already marked, propagate to arguments as needed
        }
        
        visitChildren(node);
    }
    
    private void markAsLvalue(Node node) {
        node.annotation.isLvalue = true;
        
        // Propagate through transparent nodes
        if (node instanceof BinaryOperatorNode bin) {
            if (bin.operator.equals("=")) {
                markAsLvalue(bin.left);
            }
        } else if (node instanceof ListNode list && list.elements.size() == 1) {
            markAsLvalue(list.elements.get(0));
        }
    }
}
```

### Phase 6: ConstantFolder

Integrate existing `ConstantFoldingVisitor`:

```java
public class ConstantFolderPass extends ASTTransformPass {
    
    @Override
    public Node transform(Node node) {
        // Use existing implementation
        Node folded = ConstantFoldingVisitor.foldConstants(node);
        
        // Add annotation
        RuntimeScalar value = ConstantFoldingVisitor.getConstantValue(folded);
        if (value != null) {
            folded.annotation.isConstant = true;
            folded.annotation.constantValue = value;
        }
        
        return folded;
    }
}
```

### Phase 7: WarningEmitter

Emit compile-time warnings and errors:

```java
public class WarningEmitter extends ASTTransformPass {
    
    @Override
    public void visit(IdentifierNode node) {
        if (node.annotation.binding == null && 
            node.annotation.pragmas.strictVars) {
            emitError("Global symbol \"" + node.name + 
                     "\" requires explicit package name", node);
        }
    }
    
    @Override
    public void visit(BinaryOperatorNode node) {
        // Warn about useless use of constant in void context
        if (node.annotation.context == RuntimeContextType.VOID &&
            node.annotation.isConstant &&
            node.annotation.pragmas.enabledWarnings.contains("void")) {
            emitWarning("Useless use of constant in void context", node);
        }
        
        // Warn about $x = $x (self-assignment)
        if (node.operator.equals("=") && 
            isSameVariable(node.left, node.right) &&
            node.annotation.pragmas.enabledWarnings.contains("misc")) {
            emitWarning("Useless assignment to " + 
                       getVariableName(node.left), node);
        }
        
        visitChildren(node);
    }
    
    @Override
    public void visit(OperatorNode node) {
        // Warn about deprecated syntax
        if (isDeprecated(node) &&
            node.annotation.pragmas.enabledWarnings.contains("deprecated")) {
            emitWarning("Use of " + node.operator + " is deprecated", node);
        }
        
        visitChildren(node);
    }
}
```

## Implementation Plan

### Milestone 1: Infrastructure (Week 1)

**Goal**: Create the transformer framework without changing behavior

1. Create `ASTAnnotation` class with all fields
2. Add `annotation` field to base `Node` class  
3. Create `ASTTransformPass` base class
4. Create `ASTTransformer` orchestrator that runs passes in sequence
5. Add transformer invocation point (disabled by default)

**Testing**: All existing tests pass (no behavioral change)

### Milestone 2: Variable Resolution (Week 2)

**Goal**: Track variable identity across both backends

1. Implement `VariableResolver` pass
2. Add variable binding annotations
3. Update one backend (bytecode) to use annotations for variable lookup
4. Add parity tests comparing variable resolution

**Testing**:
```bash
# Test script that exercises variable scoping
./jperl test_vars.pl
./jperl --interpreter test_vars.pl
perl test_vars.pl
# All three must produce identical output
```

### Milestone 3: Closure Capture (Week 3)

**Goal**: Unified closure variable capture

1. Extend `VariableResolver` with capture detection
2. Add `capturedVariables` annotation to subroutine nodes
3. Update both backends to use captured variable list
4. Add closure parity tests

**Testing**:
```perl
# test_closures.pl
my $x = 1;
my $f = sub { $x++ };
$f->(); $f->();
print $x;  # Must print 3
```

### Milestone 4: Label Resolution (Week 4)

**Goal**: Unified label handling for control flow

1. Implement `LabelCollector` pass
2. Add label annotations to loops and control flow
3. Update both backends to use label annotations
4. Add control flow parity tests

**Testing**:
```perl
# test_labels.pl
OUTER: for my $i (1..3) {
    INNER: for my $j (1..3) {
        next OUTER if $j == 2;
        print "$i,$j ";
    }
}
```

### Milestone 5: Context Resolution (Week 5-6)

**Goal**: Unified context propagation

1. Implement `ContextResolver` pass
2. Add context annotations
3. Update bytecode compiler to use context annotations
4. Remove duplicated context logic from bytecode compiler
5. Extensive parity testing

**Testing**:
```perl
# test_context.pl - comprehensive context tests
my @a = (1, 2, 3);
my %h = (@a, 4);           # Array in list context
my $x = @a;                # Array in scalar context
my @b = ($x || @a);        # || context propagation
($h{k} = "abc") =~ s/a/X/; # Lvalue context
```

### Milestone 6: Lvalue Resolution (Week 7)

**Goal**: Unified lvalue handling

1. Implement `LvalueResolver` pass
2. Add lvalue annotations
3. Update both backends to use lvalue annotations
4. Simplify backend lvalue logic

**Testing**: ExifTool.t should pass on both backends

### Milestone 7: Pragma Tracking (Week 8)

**Goal**: Unified strict/warnings/features

1. Implement `PragmaResolver` pass
2. Add pragma state annotations
3. Update both backends to use pragma annotations
4. Add pragma parity tests

**Testing**:
```perl
# test_pragmas.pl
use strict;
{
    no strict 'refs';
    ${"foo"} = 1;  # OK here
}
${"bar"} = 2;  # Error here
```

### Milestone 8: Warning Emitter (Week 9)

**Goal**: Unified compile-time diagnostics

1. Implement `WarningEmitter` pass
2. Integrate with existing error handling
3. Add warning parity tests

### Milestone 9: Constant Folding Integration (Week 10)

**Goal**: Integrate existing `ConstantFoldingVisitor`

1. Create `ConstantFolderPass` wrapper
2. Add constant annotations
3. Both backends use constant annotations for optimization

### Milestone 10: Cleanup and Validation (Week 11-12)

**Goal**: Remove duplicated logic, comprehensive testing

1. Remove duplicated context/lvalue logic from backends
2. Run full Perl test suite on both backends
3. Set up differential testing infrastructure
4. Document the transformer architecture

## Differential Testing Infrastructure

```java
public class ParityTest {
    
    public static void assertParity(String perlCode) {
        // Run with JVM backend
        String jvmOutput = runWithJVM(perlCode);
        
        // Run with interpreter
        String interpOutput = runWithInterpreter(perlCode);
        
        // Run with native Perl (reference)
        String perlOutput = runWithPerl(perlCode);
        
        assertEquals(perlOutput, jvmOutput, "JVM backend differs from Perl");
        assertEquals(perlOutput, interpOutput, "Interpreter differs from Perl");
    }
}
```

```bash
# Automated parity testing script
#!/bin/bash
for test in tests/parity/*.pl; do
    echo "Testing $test..."
    EXPECTED=$(perl "$test" 2>&1)
    JVM_OUT=$(./jperl "$test" 2>&1)
    INTERP_OUT=$(./jperl --interpreter "$test" 2>&1)
    
    if [ "$EXPECTED" != "$JVM_OUT" ]; then
        echo "FAIL: JVM backend differs"
        diff <(echo "$EXPECTED") <(echo "$JVM_OUT")
    fi
    
    if [ "$EXPECTED" != "$INTERP_OUT" ]; then
        echo "FAIL: Interpreter differs"
        diff <(echo "$EXPECTED") <(echo "$INTERP_OUT")
    fi
done
```

## Example: End-to-End Transformation

Input code:
```perl
use strict;
my $x = 1;
my $f = sub { $x + 1 };
print $f->();
```

After transformation:
```
BlockNode {
  annotation: { pragmas: { strictVars: true } }
  
  OperatorNode("my") {
    annotation: { 
      binding: { id: 0, name: "$x", scope: LEXICAL },
      context: SCALAR
    }
    operand: IdentifierNode("$x")
  }
  
  BinaryOperatorNode("=") {
    annotation: { context: SCALAR, isLvalue: false }
    left: IdentifierNode("$x") { 
      annotation: { binding: { id: 0 }, context: SCALAR, isLvalue: true }
    }
    right: NumberNode(1) {
      annotation: { isConstant: true, constantValue: 1, context: SCALAR }
    }
  }
  
  SubroutineNode {
    annotation: { 
      capturedVariables: [{ id: 0, name: "$x" }],
      context: SCALAR
    }
    body: BinaryOperatorNode("+") {
      annotation: { context: SCALAR }
      left: IdentifierNode("$x") {
        annotation: { 
          binding: { id: 0 }, 
          isCaptured: true, 
          closureDepth: 1,
          context: SCALAR
        }
      }
      right: NumberNode(1) { annotation: { isConstant: true } }
    }
  }
}
```

## Benefits

1. **Single source of truth** - All semantic decisions made once
2. **Guaranteed parity** - Both backends see identical annotated AST
3. **Easier debugging** - Can dump annotated AST to see all decisions
4. **Simpler backends** - Less decision logic, more mechanical translation
5. **Better testing** - Can unit test each transformer pass independently
6. **Incremental rollout** - Each pass can be enabled/tested separately
7. **Perl compatibility** - Differential testing catches divergence early

## Risks and Mitigations

### Risk: Performance overhead from extra passes
**Mitigation**: Each pass is O(n) tree walk; total overhead < 5% of parse time

### Risk: Breaking existing behavior
**Mitigation**: Incremental rollout with feature flags; extensive parity testing

### Risk: Complexity in transformer passes
**Mitigation**: Well-defined single-responsibility passes; comprehensive unit tests

### Risk: Annotation memory overhead
**Mitigation**: Annotations are lightweight; can be cleared after codegen

## Success Metrics

1. ExifTool.t passes 35/35 on both backends
2. No context/lvalue bug fixes needed in individual backends
3. Differential testing shows 100% parity on test suite
4. Backend code reduced by ~30% (removed duplicated logic)
5. New semantic features only need implementation in transformer

## Related Documents

- `dev/design/interpreter.md` - Interpreter architecture
- `dev/design/logical_or_list_context.md` - Context propagation challenges
- `dev/custom_bytecode/BYTECODE_DOCUMENTATION.md` - Bytecode format

## Existing Analysis Visitors (to integrate)

These visitors already exist and should be integrated into the transformer:

### Core Semantic Analysis (required for parity)

- `src/main/java/org/perlonjava/frontend/analysis/ConstantFoldingVisitor.java` - Constant folding optimization
- `src/main/java/org/perlonjava/frontend/analysis/FindDeclarationVisitor.java` - Locates `local`/`my` declarations
- `src/main/java/org/perlonjava/frontend/analysis/RegexUsageDetector.java` - Detects regex operations in blocks

### Lvalue Analysis (relevant to LvalueResolver)

- `src/main/java/org/perlonjava/frontend/analysis/LValueVisitor.java` - Determines if a node is assignable (lvalue) and its type:
  - Returns `SCALAR` - scalar lvalue ($a, $a[0], $$ref, pos(), substr(), etc.)
  - Returns `LIST` - list lvalue (@a, %h, ($a, $b))
  - Returns `VOID` - not an lvalue (literals, most operators)
  
  **Integration**: This visitor implements the core logic for `LvalueResolver` phase. Can be used directly or its logic integrated into the transformer.

### Return Type Analysis (for JVM optimization)

- `src/main/java/org/perlonjava/frontend/analysis/ReturnTypeVisitor.java` - Determines JVM return type descriptor:
  - `RuntimeScalar` for scalar operations ($, numbers, strings)
  - `RuntimeArray` for @ dereference
  - `RuntimeHash` for % dereference  
  - `RuntimeList` for list operations, subroutine calls
  - `null` if type cannot be determined statically

  **Note**: Currently JVM-specific (returns JVM descriptors). For shared transformer, could be generalized to semantic types.

### Temp Local Count (JVM-specific)

- `src/main/java/org/perlonjava/frontend/analysis/TempLocalCountVisitor.java` - Counts temporary locals needed for JVM bytecode. Not needed for interpreter.

### Value Extraction (for compile-time evaluation)

- `src/main/java/org/perlonjava/frontend/analysis/ExtractValueVisitor.java` - Extracts literal values (strings, numbers) from an AST into a RuntimeList. Useful for compile-time constant evaluation, `use VERSION` checks, etc.

### Large Literal Refactoring (OBSOLETE)

- `src/main/java/org/perlonjava/frontend/analysis/DepthFirstLiteralRefactorVisitor.java` - **OBSOLETE**: The compiler now falls back to interpreter for large literals instead of refactoring them. This visitor is no longer used.

### Utility Visitors

- `src/main/java/org/perlonjava/frontend/analysis/PrintVisitor.java` - Prints AST as indented string for debugging. Could be extended to print annotations.

- `src/main/java/org/perlonjava/frontend/analysis/EmitterVisitor.java` - JVM bytecode generation visitor. Backend-specific, not for shared transformer.

### Summary: Shared vs Backend-Specific

| Visitor | Shared Transformer? | Notes |
|---------|---------------------|-------|
| ConstantFoldingVisitor | YES | Integrate into transformer |
| FindDeclarationVisitor | YES | Integrate into transformer |
| RegexUsageDetector | YES | Integrate into transformer |
| LValueVisitor | YES | Core of LvalueResolver phase |
| ExtractValueVisitor | YES | Compile-time evaluation |
| ControlFlowDetectorVisitor | Optional | Optimization hints |
| ControlFlowFinder | Optional | Optimization hints |
| ReturnTypeVisitor | Generalize | JVM-specific types → semantic types |
| TempLocalCountVisitor | NO | JVM-specific |
| DepthFirstLiteralRefactorVisitor | NO | OBSOLETE (compiler falls back to interpreter) |
| EmitterVisitor | NO | JVM backend |
| PrintVisitor | Utility | Debug only |

### Control Flow Analysis (for optimizations)

- `src/main/java/org/perlonjava/frontend/analysis/ControlFlowDetectorVisitor.java` - Detects "unsafe" control flow that could jump outside a block:
  - `return` - always unsafe for block extraction
  - `goto LABEL` - unsafe unless label is within allowed set
  - `next`/`last`/`redo` with label - unsafe (jumps to outer loop)
  - `next`/`last`/`redo` without label but outside loop - unsafe
  
- `src/main/java/org/perlonjava/frontend/analysis/ControlFlowFinder.java` - Simple check for ANY control flow (`next`/`last`/`redo`/`goto`), ignoring loop depth. Results cached on `AbstractNode.cachedHasAnyControlFlow`.

**Current usage**: These are used by `LargeBlockRefactorer.java` for the JVM backend to split large blocks that would exceed JVM's 64KB method limit. The interpreter doesn't have this limit, but the annotations could still be useful for:

1. **Optimization hints** - Blocks without control flow can use simpler codegen
2. **Inlining decisions** - Safe to inline blocks without non-local control flow
3. **Caching** - Results are cached on AST nodes to avoid repeated scans

**Recommended annotation**:
```java
public class ASTAnnotation {
    // ... existing fields ...
    
    // Control flow analysis (optional, for optimization)
    public Boolean hasAnyControlFlow;      // Cached: contains next/last/redo/goto
    public Boolean hasUnsafeControlFlow;   // Cached: control flow escapes block
}
```

## Devin Skills

The following Devin skills are available to assist with implementation and debugging:

### `.agents/skills/interpreter-parity/`
**Primary skill for this work.** Debugging interpreter vs JVM backend parity issues. Includes:
- Comparing backend outputs (`./jperl` vs `./jperl --interpreter`)
- Disassembling bytecode (`--disassemble`)
- Common parity bug patterns (context, lvalue, closure captures)
- Bytecode compiler architecture overview

### `.agents/skills/debug-perlonjava/`
General debugging for test failures and regressions. Covers:
- Running unit tests and Perl5 core tests
- Debugging workflows for compiler and runtime issues
- Disassembly and tracing techniques

### `.agents/skills/debug-exiftool/`
Debugging Image::ExifTool test failures. Useful for validating real-world Perl module compatibility after transformer changes.

### `.agents/skills/profile-perlonjava/`
Performance profiling. Use to verify transformer passes don't introduce performance regressions.

### Invoking Skills

```
# In Devin CLI
/skill interpreter-parity "context bug in hash assignment"
/skill debug-perlonjava "unit test failure"
```

Or mention the skill name in conversation to auto-invoke.

---

## Progress Tracking

### Current Status: Design Phase Complete

The design document is complete. Implementation has not started.

### Completed Phases

- [x] **Design Document Creation** (2024-03-09)
  - Problem statement and architecture diagrams
  - AST annotation structure with existing infrastructure analysis
  - 8 transformer phases detailed (PragmaResolver through WarningEmitter)
  - 12-milestone implementation plan
  - Differential testing infrastructure spec
  - Inventory of existing visitors (12 total, categorized as shared vs JVM-specific)
  - Files: `dev/design/shared_ast_transformer.md` (1210+ lines)

### Next Steps

1. **Start Milestone 1: Infrastructure**
   - Create `ASTAnnotation` class in `src/main/java/org/perlonjava/frontend/analysis/`
   - Add typed fields to `AbstractNode` for context and lvalue caching
   - Create `ASTTransformer` base class with phase orchestration

2. **Set up differential testing**
   - Create test harness that runs same code on both backends
   - Add to CI pipeline

3. **Review existing visitors for integration**
   - `LValueVisitor` - can be directly integrated into LvalueResolver
   - `ConstantFoldingVisitor` - integrate into ConstantFolder phase
   - `FindDeclarationVisitor` - integrate into VariableResolver

### Open Questions

1. Should we use Option A (typed fields) or Option B (annotation map) for frequently-accessed annotations? **Recommendation: Option A for performance**

2. Should control flow analysis (`ControlFlowDetectorVisitor`) be included in shared transformer or remain JVM-specific optimization?

3. How to handle the transition period where both old and new code paths exist?

### Key Files to Modify

| File | Changes Needed |
|------|----------------|
| `AbstractNode.java` | Add context/lvalue cached fields |
| `EmitterVisitor.java` | Read annotations instead of computing |
| `CompileAssignment.java` | Read lvalue annotations |
| `CompileContext.java` | Read context annotations |
| `Compile*.java` (interpreter) | Read same annotations |

### Dependencies

- No external dependencies needed
- Builds on existing visitor infrastructure
- Compatible with current AST node hierarchy
