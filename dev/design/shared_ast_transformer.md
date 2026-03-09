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
              │  │ 4. ContextResolver      │  │  ◄── scalar/list/void context
              │  │ 5. LvalueResolver       │  │  ◄── lvalue annotations
              │  │ 6. ConstantFolder       │  │  ◄── existing optimization
              │  │ 7. WarningEmitter       │  │  ◄── compile-time warnings
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
- `src/main/java/org/perlonjava/frontend/analysis/ConstantFoldingVisitor.java` - Existing constant folding
