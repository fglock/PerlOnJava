# Implementing @INC CODE Filter Support

## Problem Statement
The `op/incfilter.t` test file was completely blocked (152 tests) because PerlOnJava didn't support CODE references in `do` statements, which is required for @INC filter functionality.

## Root Cause
`ModuleOperators.doFile()` only handled:
1. File handles (GLOB/GLOBREFERENCE)
2. File names (strings)

It did NOT handle CODE references, which are used as @INC filters in Perl.

## Investigation Process
1. Used `--parse` to see AST structure showing `doFile` operation with `\&generator`
2. Used `--disassemble` to confirm `ModuleOperators.doFile` was being called
3. Examined `ModuleOperators.java` to find missing CODE reference handling

## Solution Implemented

### Code Changes in ModuleOperators.java
Added support for CODE references before the GLOB handling:

```java
// Check if the argument is a CODE reference (for @INC filter support)
if (runtimeScalar.type == RuntimeScalarType.CODE || 
    (runtimeScalar.type == RuntimeScalarType.REFERENCE && 
     runtimeScalar.value instanceof RuntimeCode)) {
    
    RuntimeCode codeRef = null;
    if (runtimeScalar.type == RuntimeScalarType.CODE) {
        codeRef = (RuntimeCode) runtimeScalar.value;
    } else {
        // For REFERENCE type, the value is already the RuntimeCode
        if (runtimeScalar.value instanceof RuntimeCode) {
            codeRef = (RuntimeCode) runtimeScalar.value;
        }
    }
    
    if (codeRef != null) {
        // Save current $_ 
        RuntimeScalar savedDefaultVar = GlobalVariable.getGlobalVariable("main::_");
        GlobalVariable.getGlobalVariable("main::_").set("");
        
        try {
            // Call the CODE reference with no arguments
            RuntimeArray args = new RuntimeArray();
            RuntimeBase result = codeRef.apply(args, RuntimeContextType.SCALAR);
            
            // Get the content from $_
            RuntimeScalar defaultVar = GlobalVariable.getGlobalVariable("main::_");
            code = defaultVar.toString();
            
            // Only set code to null if $_ is actually empty
            if (code.isEmpty()) {
                code = null;
            }
        } finally {
            // Restore $_
            GlobalVariable.getGlobalVariable("main::_").set(savedDefaultVar.toString());
        }
    }
}
```

## Key Implementation Details
1. **CODE Reference Detection**: Check for both direct CODE type and REFERENCE containing CODE
2. **$_ Handling**: Save/restore `$_` around CODE execution, as the filter populates it with content
3. **Return Value Semantics**: Return value of 0 means EOF, but content in `$_` is still valid
4. **Error Handling**: Proper exception handling and cleanup

## Test Results
- Basic `do \&generator` now works
- Test 1 of `op/incfilter.t` passes
- More complex cases like `do [\&generator]` (array ref with CODE ref) still need work

## Impact
- Partially unblocked `op/incfilter.t` (1 test passes)
- Foundation laid for full @INC filter support
- Pattern established for handling CODE references in other contexts

## Future Work
- Support array references containing CODE references and parameters
- Full @INC hook implementation for require/use
- Support for multiple return values from filters

## Files Modified
- `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/operators/ModuleOperators.java`
