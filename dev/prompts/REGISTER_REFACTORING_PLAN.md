# Register Management Refactoring Plan

## Problem
BytecodeCompiler manually manages registers with:
- `nextRegister` - current allocation pointer
- `baseRegisterForStatement` - recycling reset point  
- `REGISTER_RECYCLING_THRESHOLD` - magic number hack
- `variableScopes` - manual scope stack
- `savedNextRegister`/`savedBaseRegister` - manual save/restore

This causes issues because:
1. Register recycling is imprecise (uses threshold instead of proper liveness)
2. Scope management is duplicated from ScopedSymbolTable
3. No visibility into which registers hold live values

## Solution
Use existing `ScopedSymbolTable` infrastructure:

### Replace
```java
private int nextRegister = 3;
private int baseRegisterForStatement = 3;
private int maxRegisterEverUsed = 2;
private Stack<Map<String, Integer>> variableScopes;
private Stack<Integer> savedNextRegister;
private Stack<Integer> savedBaseRegister;
```

### With
```java
private ScopedSymbolTable symbolTable = new ScopedSymbolTable();
```

### Key Changes

1. **Register Allocation**
   ```java
   // OLD:
   private int allocateRegister() {
       int reg = nextRegister++;
       ...
   }
   
   // NEW:
   private int allocateRegister() {
       return symbolTable.allocateLocalVariable();
   }
   ```

2. **Scope Entry**
   ```java
   // OLD:
   private void enterScope() {
       variableScopes.push(new HashMap<>());
       savedNextRegister.push(nextRegister);
       savedBaseRegister.push(baseRegisterForStatement);
   }
   
   // NEW:
   private void enterScope() {
       symbolTable.enterScope();
   }
   ```

3. **Scope Exit**
   ```java
   // OLD:
   private void exitScope() {
       variableScopes.pop();
       nextRegister = savedNextRegister.pop();
       baseRegisterForStatement = savedBaseRegister.pop();
   }
   
   // NEW:
   private void exitScope() {
       symbolTable.exitScope(scopeIndex);
   }
   ```

4. **Variable Registration**
   ```java
   // OLD:
   variableScopes.peek().put(varName, reg);
   
   // NEW:
   symbolTable.addVariable(varName, "my", astNode);
   ```

5. **Register Recycling** - AUTOMATIC!
   - No manual recycling needed
   - ScopedSymbolTable already handles scope cleanup
   - exitScope() automatically frees registers from that scope

## Benefits

✅ **Eliminates threshold hack** - proper scope-based recycling
✅ **Reuses battle-tested code** - ScopedSymbolTable is used by JVM compiler
✅ **Automatic lifetime tracking** - registers freed when scope exits
✅ **Consistent semantics** - interpreter matches compiler behavior
✅ **Simpler code** - removes ~100 lines of manual management

## Migration Strategy

1. Add `symbolTable` field to BytecodeCompiler
2. Replace `allocateRegister()` implementation
3. Replace `enterScope()`/`exitScope()` implementations  
4. Replace variable registration calls
5. Remove `recycleTemporaryRegisters()` - no longer needed
6. Remove threshold constant and related tracking fields
7. Test thoroughly

## Expected Behavior

- **code_too_large.t**: Still passes (scopes auto-recycle)
- **demo.t**: Still passes (proper lifetime management)
- **All tests**: Pass with cleaner semantics
