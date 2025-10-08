# TODO: Refactor Unpack Groups to Use Subroutine Approach

## Status
**In Progress** - Interface created, implementation pending

## Objective
Refactor unpack group processing to use recursive unpack calls (subroutine approach) instead of custom `processGroupContent` parser, matching the architecture that pack already uses.

## Problem Statement

Currently, unpack groups are processed using a custom parser (`processGroupContent`) that:
- Duplicates ~300 lines of parsing logic from the main unpack function
- Doesn't handle all edge cases correctly (e.g., `I>2` syntax)
- Causes group endianness transformations to fail
- Creates maintenance burden with two separate parsers

Pack already uses the correct "subroutine" approach where groups recursively call the main pack function.

## Solution Architecture

### Current Pack Approach (Working Correctly)
```java
// Pack has PackFunction interface
@FunctionalInterface
public interface PackFunction {
    RuntimeScalar pack(RuntimeList args);
}

// Groups call pack recursively
RuntimeList groupArgs = new RuntimeList();
groupArgs.add(new RuntimeScalar(effectiveContent));
// ... add values ...
RuntimeScalar groupResult = packFunction.pack(groupArgs);
```

### Proposed Unpack Approach (To Implement)
```java
// Create UnpackFunction interface (DONE)
@FunctionalInterface
public interface UnpackFunction {
    RuntimeList unpack(String template, RuntimeScalar data);
}

// Groups should call unpack recursively (TODO)
String effectiveTemplate = applyGroupEndianness(groupContent, groupEndian);
RuntimeScalar groupData = extractGroupData(state, ...);
RuntimeList groupResult = unpackFunction.unpack(effectiveTemplate, groupData);
// ... add results to values ...
```

## Implementation Plan

### Step 1: Create Interface ✅ COMPLETED
- [x] Add `UnpackFunction` interface to `UnpackGroupProcessor`
- [x] Add `RuntimeList` import

### Step 2: Refactor parseGroupSyntax (IN PROGRESS)
- [ ] Add `UnpackFunction unpackFunction` parameter to `parseGroupSyntax`
- [ ] Extract group data from state into a RuntimeScalar
- [ ] Build template with endianness applied
- [ ] Call `unpackFunction.unpack(template, data)` for each repeat
- [ ] Add unpacked values to the output list
- [ ] Handle mode stack correctly

### Step 3: Update Unpack.java
- [ ] Pass lambda/method reference to `parseGroupSyntax`:
  ```java
  UnpackGroupProcessor.parseGroupSyntax(template, i, state, values, startsWithU, modeStack,
      (tmpl, data) -> {
          // Create args and call unpack recursively
          RuntimeList result = unpack(ctx, new RuntimeScalar(tmpl), data);
          return result;
      });
  ```

### Step 4: Testing Strategy
1. Test simple group: `(I2)`
2. Test group with endianness: `(I2)>`
3. Test nested groups: `((I2)2)`
4. Test group with modifiers: `(I!2)>`
5. Test slash constructs: `(n/a*)`
6. Run full pack.t test suite

### Step 5: Cleanup
- [ ] Remove `processGroupContent` method (~300 lines)
- [ ] Remove helper methods only used by `processGroupContent`
- [ ] Update documentation

## Expected Benefits

1. **Bug Fixes**
   - Group endianness will work correctly (+15 tests estimated)
   - Template syntax like `(I>2)` will parse correctly
   - All edge cases handled by main parser automatically

2. **Code Quality**
   - Eliminate ~300 lines of duplicate parsing logic
   - Single source of truth for unpack parsing
   - Pack and unpack architecturally symmetric

3. **Maintainability**
   - Future parser improvements automatically apply to groups
   - Easier to understand and debug
   - Reduced cognitive load

## Risks and Mitigation

### Risk 1: State Management
**Issue**: Groups need to extract data from state and restore position correctly
**Mitigation**: Study how pack handles this with PackBuffer

### Risk 2: Mode Stack
**Issue**: C0/U0 mode changes need to be preserved across recursive calls
**Mitigation**: Pass mode information in the data or template

### Risk 3: Regression
**Issue**: Changing core functionality could break existing tests
**Mitigation**: Incremental implementation with testing at each step

## Current Progress

- ✅ Interface created
- ✅ Plan documented
- ⏳ Implementation pending

## Next Steps

1. Study how pack extracts data for groups (PackBuffer slicing)
2. Implement data extraction from UnpackState
3. Refactor parseGroupSyntax to use recursive calls
4. Test incrementally
5. Remove old code once new code is proven

## Related Files

- `/src/main/java/org/perlonjava/operators/unpack/UnpackGroupProcessor.java` - Main file to refactor
- `/src/main/java/org/perlonjava/operators/Unpack.java` - Needs to pass function reference
- `/src/main/java/org/perlonjava/operators/pack/PackGroupHandler.java` - Reference implementation
- `/src/main/java/org/perlonjava/operators/UnpackState.java` - Data extraction

## Estimated Impact

- **Code reduction**: ~300 lines removed
- **Test improvements**: +15-20 tests (group endianness cluster)
- **Architecture**: Major improvement in symmetry and maintainability
- **Time**: 2-3 hours for careful implementation and testing
