# Regression Investigation for PR #200

## Summary

Investigated 2 reported test failures. **Neither is a regression** from the compound assignment operator changes.

## Test Results

### io/utf8.t

**Master Branch:**
```
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
	at org.objectweb.asm.Frame.merge(Frame.java:1280)
ASM frame compute crash in generated class: org/perlonjava/anon0 (astIndex=0, at io/utf8.t:1)
```

**Feature Branch:**
```
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
	at org.objectweb.asm.Frame.merge(Frame.java:1280)
ASM frame compute crash in generated class: org/perlonjava/anon0 (astIndex=0, at io/utf8.t:1)
```

**Status:** ✅ NOT A REGRESSION - Identical error on both branches

### re/pat_rt_report.t

**Master Branch:**
```
1..2514
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
	at org.objectweb.asm.Frame.merge(Frame.java:1280)
ASM frame compute crash in generated class: org/perlonjava/anon328 (astIndex=12025, at re/pat_rt_report.t:759)
# Looks like you planned 2514 tests but ran 0.
```

**Feature Branch:**
```
1..2514
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
	at org.objectweb.asm.Frame.merge(Frame.java:1280)
ASM frame compute crash in generated class: org/perlonjava/anon328 (astIndex=12025, at re/pat_rt_report.t:759)
# Looks like you planned 2514 tests but ran 0.
```

**Status:** ✅ NOT A REGRESSION - Identical error on both branches

## Root Cause

Both tests crash due to pre-existing ASM frame computation bugs in the bytecode generator. The errors occur during:
1. `Frame.merge()` - ASM's internal stack frame analysis
2. `MethodWriter.computeAllFrames()` - Computing JVM stack frames for methods

This is unrelated to:
- Compound assignment operator overload support
- The new `*Assign()` methods in MathOperators.java
- The new interpreter opcodes (SUBTRACT_ASSIGN, MULTIPLY_ASSIGN, etc.)

## Conclusion

✅ **PR #200 is clear for merge** - No regressions introduced by compound assignment operator implementation.

The failing tests are pre-existing issues that need separate investigation and fixes to the ASM bytecode generation system.
