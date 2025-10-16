# Revert Notes - 2025-10-16

## Reverted Commits

Reverted the following commits due to Ubuntu CI/CD failures:

1. **fc2865b5** - Fix #line directives in string interpolation ${...}
2. **a3186726** - Process #line directives before parsing statements in blocks
3. **d4da4b6e** - Fix StringDoubleQuoted to only affect strings with ${...} interpolation
4. **287392ac** - Document Ubuntu Base64 issue investigation
5. **de3c3401** - Document remaining #line directive issue for future work
6. **b8d3c9b6** - Update LLM rewards: +99.5 GPU hours
7. **6955ea5f** - Update LLM rewards: +7.0 GPU hours

## Reason for Revert

Ubuntu CI/CD consistently failed with:
```
java.lang.IllegalArgumentException: Last unit does not have enough valid bits
at java.base/java.util.Base64$Decoder.decode0(Base64.java:872)
at org.perlonjava.perlmodule.MIMEBase64.decode_base64(MIMEBase64.java:109)
```

While all tests passed on macOS, the Ubuntu CI/CD environment showed platform-specific issues.

## What Was Kept

- **f5852853** - Fix caller() to return correct line numbers (token-based deduplication) ✓
  - This is a fundamental fix that works correctly across platforms

## Investigation Findings

1. The #line directive fixes worked correctly on macOS
2. Creating separate ErrorMessageUtil for string interpolation caused side effects
3. The Base64 issue appears to be platform-specific (Java version or line endings)
4. More investigation needed with actual CI/CD environment access

## Next Steps

1. Investigate #line directives with proper cross-platform testing
2. Consider alternative approaches that don't modify ErrorMessageUtil lifecycle
3. Add platform-specific tests before committing parser changes

## Lessons Learned

- Parser changes affecting ErrorMessageUtil need thorough cross-platform validation
- CI/CD failures on specific platforms require environment-specific debugging
- Sometimes the right move is to revert and investigate rather than debug in production
