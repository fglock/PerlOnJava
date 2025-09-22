# Regex Parser Integration Strategy for PerlOnJava

## Overview

This document outlines a strategy for integrating an existing, mature regex parser into PerlOnJava to replace the current string-manipulation-based regex preprocessing approach with proper semantic parsing and validation.

## Current State Analysis

### Problems with Current Approach

1. **"Cheating" with hardcoded string matching**: Current validation uses `s.equals()` and `s.contains()` for pattern detection
2. **Fragile preprocessing**: String manipulation approach is error-prone and hard to maintain
3. **Limited semantic understanding**: No proper AST representation of regex structure
4. **Incomplete validation**: Edge cases in backreferences, character classes, and complex constructs
5. **Architectural inconsistency**: Mix of pre-validation and post-processing creates conflicts

### Current Architecture

```
Perl Regex String → RegexPreprocessor → Java-compatible String → Java Pattern
                    ↓
                    String manipulation + hardcoded validation
```

## Proposed Integration Strategy

### Phase 1: ANTLR-based PCRE Parser Integration

#### Recommended Parser: bkiers/pcre-parser

- **Repository**: https://github.com/bkiers/pcre-parser
- **Technology**: ANTLR 4 grammar for PCRE (Perl Compatible Regular Expressions)
- **License**: MIT (compatible with PerlOnJava)
- **Maturity**: Active project with comprehensive PCRE support

#### Integration Architecture

```
Perl Regex String → PCRE Parser → AST → Semantic Validator → AST Transformer → Java Pattern
                    ↓             ↓     ↓                   ↓
                    ANTLR 4       Tree  Proper validation   Java-compatible AST
```

### Phase 2: Implementation Plan

#### Step 1: Dependency Integration

Add ANTLR PCRE parser dependency to `build.gradle`:

```gradle
dependencies {
    implementation 'nl.bigo:pcre-parser:1.0.0' // Version TBD
    implementation 'org.antlr:antlr4-runtime:4.9.3'
}
```

#### Step 2: Core Integration Classes

1. **EnhancedRegexPreprocessor**: Main entry point
2. **RegexASTValidator**: Semantic validation using AST
3. **RegexASTTransformer**: Transform Perl AST to Java-compatible form
4. **RegexErrorReporter**: Convert ANTLR errors to PerlCompilerException

#### Step 3: Fallback Strategy

Maintain current preprocessing as fallback for:
- Parser failures
- Unsupported constructs
- Performance-critical paths (if needed)

### Phase 3: Detailed Implementation

#### Core Classes Structure

```java
public class EnhancedRegexPreprocessor {
    public static String preProcessRegex(String regex, RegexFlags flags) {
        try {
            // Parse regex into AST
            ParseTree ast = parseRegex(regex);
            
            // Semantic validation
            validateRegexAST(ast, flags);
            
            // Transform for Java compatibility
            return transformAST(ast, flags);
            
        } catch (RegexParseException e) {
            // Fallback to current approach
            return RegexPreprocessor.preProcessRegex(regex, flags);
        }
    }
    
    private static ParseTree parseRegex(String regex) throws RegexParseException {
        PCRELexer lexer = new PCRELexer(CharStreams.fromString(regex));
        PCREParser parser = new PCREParser(new CommonTokenStream(lexer));
        
        // Configure error handling
        parser.removeErrorListeners();
        parser.addErrorListener(new RegexErrorListener());
        
        return parser.pcre();
    }
}
```

#### Semantic Validation

```java
public class RegexASTValidator extends PCREBaseVisitor<Void> {
    private int captureGroupCount = 0;
    private Set<String> namedGroups = new HashSet<>();
    private RegexFlags flags;
    
    public void validate(ParseTree tree, RegexFlags flags) {
        this.flags = flags;
        visit(tree);
    }
    
    @Override
    public Void visitBackreference(BackreferenceContext ctx) {
        int groupNum = Integer.parseInt(ctx.NUMBER().getText());
        if (groupNum > captureGroupCount) {
            throw new PerlCompilerException("Reference to nonexistent group");
        }
        return super.visitBackreference(ctx);
    }
    
    @Override
    public Void visitNamedBackreference(NamedBackreferenceContext ctx) {
        String groupName = ctx.NAME().getText();
        if (!namedGroups.contains(groupName)) {
            throw new PerlCompilerException("Reference to nonexistent named group");
        }
        return super.visitNamedBackreference(ctx);
    }
}
```

#### AST Transformation

```java
public class RegexASTTransformer extends PCREBaseVisitor<String> {
    private StringBuilder result = new StringBuilder();
    private RegexFlags flags;
    
    public String transform(ParseTree tree, RegexFlags flags) {
        this.flags = flags;
        visit(tree);
        return result.toString();
    }
    
    @Override
    public String visitCharacterClass(CharacterClassContext ctx) {
        // Transform Perl character class to Java equivalent
        // Handle POSIX classes, Unicode properties, etc.
        return super.visitCharacterClass(ctx);
    }
    
    @Override
    public String visitQuantifier(QuantifierContext ctx) {
        // Handle possessive quantifiers, etc.
        return super.visitQuantifier(ctx);
    }
}
```

## Benefits of Integration

### 1. Architectural Improvements

- **Clean separation**: Parsing, validation, and transformation as distinct phases
- **Semantic understanding**: True AST-based processing instead of string manipulation
- **Extensibility**: Easy to add new Perl regex features
- **Maintainability**: Grammar-based approach is self-documenting

### 2. Validation Improvements

- **Proper backreference validation**: Check against actual capture group structure
- **Character class validation**: Parse and validate character classes semantically
- **Quantifier validation**: Understand quantifier context and nesting
- **Named group validation**: Track and validate named capture groups

### 3. Error Reporting

- **Precise error locations**: ANTLR provides exact position information
- **Semantic error messages**: Context-aware error reporting
- **Perl-compatible messages**: Transform ANTLR errors to Perl-style messages

## Migration Strategy

### Phase A: Parallel Implementation (Low Risk)

1. Implement enhanced preprocessor alongside current one
2. Add feature flag to enable/disable new parser
3. Run both parsers in test suite, compare results
4. Gradually enable for specific regex patterns

### Phase B: Gradual Replacement (Medium Risk)

1. Replace preprocessing for simple patterns first
2. Use fallback for complex patterns initially
3. Expand coverage as confidence grows
4. Monitor performance and correctness

### Phase C: Full Migration (High Confidence)

1. Make enhanced preprocessor the default
2. Keep current preprocessor as emergency fallback
3. Remove fallback after extensive testing
4. Clean up deprecated code

## Performance Considerations

### Potential Concerns

1. **Parsing overhead**: ANTLR parsing may be slower than string manipulation
2. **Memory usage**: AST creation requires additional memory
3. **Startup time**: ANTLR parser initialization cost

### Mitigation Strategies

1. **Caching**: Cache parsed ASTs for frequently used patterns
2. **Lazy initialization**: Initialize parser only when needed
3. **Pattern analysis**: Use fast path for simple patterns
4. **Benchmarking**: Measure and optimize critical paths

## Testing Strategy

### Unit Tests

1. **Parser integration tests**: Verify ANTLR parser works correctly
2. **Validation tests**: Test semantic validation logic
3. **Transformation tests**: Verify AST transformation correctness
4. **Error handling tests**: Test error reporting and fallback

### Integration Tests

1. **Regex test suite**: Run existing `t/re/regexp.t` with new parser
2. **Performance tests**: Compare performance with current approach
3. **Compatibility tests**: Ensure Perl compatibility maintained

### Regression Tests

1. **Fallback tests**: Verify fallback mechanism works
2. **Edge case tests**: Test complex regex patterns
3. **Error message tests**: Verify error message compatibility

## Implementation Timeline

### Week 1-2: Research and Setup
- Evaluate ANTLR PCRE parser options
- Set up build integration
- Create basic integration framework

### Week 3-4: Core Implementation
- Implement EnhancedRegexPreprocessor
- Basic AST validation and transformation
- Fallback mechanism

### Week 5-6: Testing and Refinement
- Comprehensive test suite
- Performance optimization
- Error handling improvements

### Week 7-8: Integration and Deployment
- Gradual rollout with feature flags
- Monitor test results and performance
- Documentation and cleanup

## Alternative Approaches Considered

### 1. Custom Regex Parser

**Pros**: Full control, optimized for PerlOnJava needs
**Cons**: Significant development effort, maintenance burden

### 2. Perl Regex Engine Integration (JNI)

**Pros**: Perfect Perl compatibility
**Cons**: JNI complexity, deployment challenges, performance overhead

### 3. Regex Engine Libraries (RE2, etc.)

**Pros**: High performance, proven reliability
**Cons**: Limited Perl compatibility, different semantics

## Conclusion

Integrating an ANTLR-based PCRE parser represents the best balance of:
- **Architectural cleanliness**: Proper separation of concerns
- **Implementation effort**: Leverage existing, mature parser
- **Perl compatibility**: PCRE provides good Perl regex coverage
- **Maintainability**: Grammar-based approach is sustainable

This approach eliminates the "cheating" problem by providing true semantic understanding of regex patterns while maintaining compatibility and performance.

## References

- [bkiers/pcre-parser](https://github.com/bkiers/pcre-parser) - ANTLR 4 PCRE Parser
- [ANTLR 4 Documentation](https://github.com/antlr/antlr4/blob/master/doc/index.md)
- [PCRE Specification](https://www.pcre.org/current/doc/html/pcre2syntax.html)
- [Perl Regular Expressions](https://perldoc.perl.org/perlre)
