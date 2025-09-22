# Sublanguage Parser Architecture Using Existing Infrastructure

## Overview

This document outlines the comprehensive sublanguage parser architecture for PerlOnJava, designed to provide proper semantic parsing for domain-specific languages embedded within Perl (regex, pack/unpack, sprintf, tr///).

### Why Sublanguage Parsers?

Perl contains multiple "sublanguages" - specialized syntaxes within string literals that have their own parsing rules:
- **Regex patterns**: `/pattern/flags` with complex syntax for matching
- **Pack/unpack templates**: `pack("A10i4", ...)` with binary data formatting
- **Sprintf formats**: `sprintf("%10.2f", ...)` with printf-style formatting
- **Transliteration**: `tr/abc/xyz/` with character mapping rules

**Current Problems:**
- Manual string manipulation leads to parsing errors
- Poor error messages with no position information
- Inconsistent validation across sublanguages
- Runtime parsing overhead instead of compile-time optimization

**Solution:**
A unified architecture that parses these sublanguages at **compile time**, generates **Abstract Syntax Trees (ASTs)**, provides **semantic validation**, and produces **better error messages** with precise location information.

## Current Architecture Analysis

### Existing Patterns We Can Leverage

1. **Lexer/Token System**: 
   - `LexerToken` with `type` and `text`
   - `LexerTokenType` enum for categorization
   - `Lexer.java` for tokenization

2. **Parser Infrastructure**:
   - `Parser.java` base class with `EmitterContext`
   - Specialized parsers: `StringParser`, `NumberParser`, `IdentifierParser`
   - `TokenUtils` for token manipulation
   - Consistent error handling with `PerlCompilerException`

3. **AST Generation**:
   - `Node` hierarchy for AST representation
   - Context-aware compilation with `EmitterContext`

### Current Sublanguage Parsing Issues

1. **Inconsistent Architecture**: Each sublanguage uses different parsing approaches
   - *Why this matters*: Developers must learn different patterns for each sublanguage
   - *Impact*: Higher maintenance cost, more bugs, inconsistent user experience

2. **Manual String Manipulation**: Direct character-by-character parsing
   - *Why this matters*: Error-prone, hard to extend, no semantic understanding
   - *Impact*: Parsing bugs, poor error messages, difficulty adding new features

3. **Poor Error Reporting**: Limited location information
   - *Why this matters*: Users can't easily find and fix their mistakes
   - *Impact*: Poor developer experience, harder debugging

4. **Runtime Parsing Overhead**: Parsing happens every time code runs
   - *Why this matters*: Performance penalty for repeated pattern parsing
   - *Impact*: Slower execution, especially for patterns in loops

## Implemented Sublanguage Architecture (Phase 1 Complete)

### Why This Architecture?

**Design Philosophy:**
1. **Compile-Time Parsing**: Parse sublanguages during compilation, not runtime
   - *Why*: Better performance, early error detection, semantic understanding
   - *How*: Integration points in `StringParser.java` call sublanguage parsers

2. **Generic AST Approach**: Single `SublanguageASTNode` class with annotations
   - *Why*: Simpler than many specific subclasses, easier to extend
   - *How*: Node type and annotations describe structure, children hold sub-nodes

3. **Two-Error-Type System**: Distinguish user mistakes from unimplemented features
   - *Why*: PerlOnJava implements Perl incrementally, need graceful degradation
   - *How*: `SYNTAX_ERROR` (user mistake) vs `UNIMPLEMENTED_ERROR` (valid Perl, not supported yet)

### Core Components (Implemented)

#### 1. Token System

```java
// Comprehensive token types for all sublanguages (IMPLEMENTED)
public enum SublanguageTokenType {
    // Common types
    LITERAL, ESCAPE, DELIMITER, MODIFIER, COMMENT, WHITESPACE,
    
    // Regex-specific  
    REGEX_ANCHOR, REGEX_QUANTIFIER, REGEX_GROUP, REGEX_CLASS,
    
    // Pack-specific
    PACK_FORMAT, PACK_COUNT, PACK_ENDIAN,
    
    // Sprintf-specific
    SPRINTF_FLAG, SPRINTF_WIDTH, SPRINTF_PRECISION, SPRINTF_CONVERSION,
    
    // Transliteration-specific
    TR_RANGE, TR_CLASS, TR_MODIFIER
}

// Base sublanguage token
public class SublanguageToken extends LexerToken {
    public final int position;
    public final int length;
    
    public SublanguageToken(SublanguageTokenType type, String text, int position, int length) {
        super(LexerTokenType.IDENTIFIER, text); // Map to base type
        this.sublanguageType = type;
        this.position = position;
        this.length = length;
    }
    
    public SublanguageTokenType sublanguageType;
}

// Base sublanguage lexer
public abstract class SublanguageLexer {
    protected final String input;
    protected int pos = 0;
    protected final List<SublanguageToken> tokens = new ArrayList<>();
    
    public SublanguageLexer(String input) {
        this.input = input;
    }
    
    public List<SublanguageToken> tokenize() {
        while (pos < input.length()) {
            if (!tokenizeNext()) {
                throw new PerlCompilerException("Unexpected character at position " + pos);
            }
        }
        return tokens;
    }
    
    protected abstract boolean tokenizeNext();
    
    protected void addToken(SublanguageTokenType type, String text) {
        tokens.add(new SublanguageToken(type, text, pos - text.length(), text.length()));
    }
    
    protected char peek() { return pos < input.length() ? input.charAt(pos) : '\0'; }
    protected char advance() { return pos < input.length() ? input.charAt(pos++) : '\0'; }
    protected boolean match(String expected) {
        if (input.startsWith(expected, pos)) {
            pos += expected.length();
            return true;
        }
        return false;
    }
}

// Base sublanguage parser
public abstract class SublanguageParser<T> {
    protected final List<SublanguageToken> tokens;
    protected int tokenIndex = 0;
    protected final EmitterContext ctx;
    
    public SublanguageParser(List<SublanguageToken> tokens, EmitterContext ctx) {
        this.tokens = tokens;
        this.ctx = ctx;
    }
    
    public abstract T parse();
    
    protected SublanguageToken peek() {
        return tokenIndex < tokens.size() ? tokens.get(tokenIndex) : null;
    }
    
    protected SublanguageToken advance() {
        return tokenIndex < tokens.size() ? tokens.get(tokenIndex++) : null;
    }
    
    protected boolean match(SublanguageTokenType type) {
        SublanguageToken token = peek();
        if (token != null && token.sublanguageType == type) {
            advance();
            return true;
        }
        return false;
    }
    
    protected void error(String message) {
        SublanguageToken token = peek();
        int position = token != null ? token.position : input.length();
        throw new PerlCompilerException(message + " at position " + position);
    }
}
```

#### 2. Regex Sublanguage Implementation

```java
// Regex-specific lexer
public class RegexLexer extends SublanguageLexer {
    public RegexLexer(String input) {
        super(input);
    }
    
    @Override
    protected boolean tokenizeNext() {
        char c = peek();
        
        switch (c) {
            case '\\':
                return tokenizeEscape();
            case '[':
                return tokenizeCharacterClass();
            case '(':
                return tokenizeGroup();
            case '*':
            case '+':
            case '?':
                return tokenizeQuantifier();
            case '^':
            case '$':
                return tokenizeAnchor();
            default:
                return tokenizeLiteral();
        }
    }
    
    private boolean tokenizeEscape() {
        int start = pos;
        advance(); // consume '\'
        
        char next = peek();
        if (next == 'g') {
            advance();
            if (peek() == '{') {
                // \g{...} backreference
                while (peek() != '}' && peek() != '\0') advance();
                if (peek() == '}') advance();
            } else if (Character.isDigit(peek()) || peek() == '-') {
                // \g1 or \g-1
                if (peek() == '-') advance();
                while (Character.isDigit(peek())) advance();
            }
        } else if (Character.isDigit(next)) {
            // \1, \2, etc.
            while (Character.isDigit(peek())) advance();
        } else {
            advance(); // single character escape
        }
        
        addToken(SublanguageTokenType.ESCAPE, input.substring(start, pos));
        return true;
    }
    
    // ... other tokenize methods
}

// Regex-specific parser
public class RegexParser extends SublanguageParser<RegexAST> {
    public RegexParser(List<SublanguageToken> tokens, EmitterContext ctx) {
        super(tokens, ctx);
    }
    
    @Override
    public RegexAST parse() {
        return parseAlternation();
    }
    
    private RegexAST parseAlternation() {
        RegexAST left = parseSequence();
        
        while (match(SublanguageTokenType.DELIMITER) && 
               peek().text.equals("|")) {
            RegexAST right = parseSequence();
            left = new AlternationNode(left, right);
        }
        
        return left;
    }
    
    private RegexAST parseSequence() {
        List<RegexAST> elements = new ArrayList<>();
        
        while (peek() != null && !peek().text.equals("|")) {
            elements.add(parseElement());
        }
        
        return new SequenceNode(elements);
    }
    
    // ... other parse methods with proper semantic validation
}
```

#### 3. Pack/Unpack Sublanguage Implementation

```java
// Pack-specific lexer
public class PackLexer extends SublanguageLexer {
    @Override
    protected boolean tokenizeNext() {
        char c = peek();
        
        if (isPackFormat(c)) {
            advance();
            addToken(SublanguageTokenType.PACK_FORMAT, String.valueOf(c));
            return true;
        } else if (c == '<' || c == '>') {
            advance();
            addToken(SublanguageTokenType.PACK_ENDIAN, String.valueOf(c));
            return true;
        } else if (c == '!') {
            advance();
            addToken(SublanguageTokenType.MODIFIER, "!");
            return true;
        } else if (Character.isDigit(c) || c == '*') {
            return tokenizeCount();
        } else if (c == '[') {
            return tokenizeBracketExpression();
        } else if (c == '(') {
            advance();
            addToken(SublanguageTokenType.DELIMITER, "(");
            return true;
        }
        // ... handle other cases
        
        return false;
    }
    
    private boolean isPackFormat(char c) {
        return "aAZbBhHcCsSiIlLnNvVqQjJfdFDpPuUwWxX@".indexOf(c) >= 0;
    }
    
    // ... other methods
}

// Pack-specific parser with semantic validation
public class PackParser extends SublanguageParser<PackTemplate> {
    @Override
    public PackTemplate parse() {
        List<PackItem> items = new ArrayList<>();
        
        while (peek() != null) {
            items.add(parsePackItem());
        }
        
        return new PackTemplate(items);
    }
    
    private PackItem parsePackItem() {
        // Parse format character
        if (!match(SublanguageTokenType.PACK_FORMAT)) {
            error("Expected pack format character");
        }
        
        String format = tokens.get(tokenIndex - 1).text;
        
        // Parse modifiers
        List<String> modifiers = new ArrayList<>();
        while (match(SublanguageTokenType.PACK_ENDIAN) || 
               match(SublanguageTokenType.MODIFIER)) {
            modifiers.add(tokens.get(tokenIndex - 1).text);
        }
        
        // Validate modifier combinations
        validateModifiers(format, modifiers);
        
        // Parse count
        PackCount count = parseCount();
        
        return new PackItem(format, modifiers, count);
    }
    
    private void validateModifiers(String format, List<String> modifiers) {
        boolean hasLittleEndian = modifiers.contains("<");
        boolean hasBigEndian = modifiers.contains(">");
        
        if (hasLittleEndian && hasBigEndian) {
            error("Cannot use both '<' and '>' modifiers");
        }
        
        // Format-specific validation
        if ("aAZ".contains(format) && (hasLittleEndian || hasBigEndian)) {
            error("Endianness modifiers not valid for string formats");
        }
    }
}
```

#### 4. Sprintf Sublanguage Implementation

```java
// Sprintf-specific lexer
public class SprintfLexer extends SublanguageLexer {
    @Override
    protected boolean tokenizeNext() {
        char c = peek();
        
        if (c == '%') {
            return tokenizeSpecifier();
        } else {
            return tokenizeLiteral();
        }
    }
    
    private boolean tokenizeSpecifier() {
        int start = pos;
        advance(); // consume '%'
        
        // Parse flags
        while (pos < input.length() && "+-# 0".indexOf(peek()) >= 0) {
            advance();
        }
        
        // Parse width
        while (Character.isDigit(peek()) || peek() == '*') {
            advance();
        }
        
        // Parse precision
        if (peek() == '.') {
            advance();
            while (Character.isDigit(peek()) || peek() == '*') {
                advance();
            }
        }
        
        // Parse vector
        if (peek() == '*') {
            advance();
            while (Character.isDigit(peek())) advance();
            if (peek() == 'v') advance();
        }
        
        // Parse conversion
        if (pos < input.length() && "diouxXeEfFgGaAcspn%".indexOf(peek()) >= 0) {
            advance();
        }
        
        addToken(SublanguageTokenType.SPRINTF_CONVERSION, input.substring(start, pos));
        return true;
    }
}
```

### Integration with Existing Architecture

#### 1. Extend StringParser Pattern

```java
// Add to StringParser.java
public static class SublanguageParseResult {
    public final Object ast;
    public final int endIndex;
    public final String processedString;
    
    public SublanguageParseResult(Object ast, int endIndex, String processedString) {
        this.ast = ast;
        this.endIndex = endIndex;
        this.processedString = processedString;
    }
}

public static SublanguageParseResult parseRegex(EmitterContext ctx, String regex, RegexFlags flags) {
    RegexLexer lexer = new RegexLexer(regex);
    List<SublanguageToken> tokens = lexer.tokenize();
    
    RegexParser parser = new RegexParser(tokens, ctx);
    RegexAST ast = parser.parse();
    
    // Transform AST to Java-compatible string
    RegexTransformer transformer = new RegexTransformer(flags);
    String javaRegex = transformer.transform(ast);
    
    return new SublanguageParseResult(ast, regex.length(), javaRegex);
}
```

#### 2. Error Handling Integration

```java
// Enhanced error reporting using existing patterns
public class SublanguageError extends PerlCompilerException {
    private final String input;
    private final int position;
    private final String sublanguage;
    
    public SublanguageError(String message, String input, int position, String sublanguage) {
        super(formatError(message, input, position, sublanguage));
        this.input = input;
        this.position = position;
        this.sublanguage = sublanguage;
    }
    
    private static String formatError(String message, String input, int position, String sublanguage) {
        String before = input.substring(0, Math.min(position, input.length()));
        String after = input.substring(Math.min(position, input.length()));
        
        return String.format("%s error: %s in %s pattern; marked by <-- HERE in /%s <-- HERE %s/",
            sublanguage, message, sublanguage.toLowerCase(), before, after);
    }
}
```

## Benefits of This Approach

### 1. Consistency with Existing Architecture
- Uses established `LexerToken`/`Parser` patterns
- Integrates with `EmitterContext` and error handling
- Follows existing code organization and naming conventions

### 2. Performance
- No external dependencies (ANTLR overhead)
- Optimized for PerlOnJava's specific needs
- Reuses existing infrastructure

### 3. Maintainability
- Familiar patterns for the development team
- Consistent error handling and reporting
- Easy to extend and modify

### 4. Incremental Adoption
- Can be implemented one sublanguage at a time
- Maintains backward compatibility
- Allows gradual migration from current implementations

## Implementation Plan

### CRITICAL SUCCESS FACTORS (Learned from Failed Implementation)

**⚠️ MUST FOLLOW PHASES IN ORDER - DO NOT SKIP PHASE 1!**

The phases below are **sequential dependencies**. Attempting to implement individual sublanguage parsers (Phase 2+) without completing Phase 1 infrastructure will result in:

1. **Isolated parsers** that aren't integrated with the main compilation pipeline
2. **Runtime parsing** instead of compile-time parsing (wrong execution model)
3. **No debug visibility** because methods aren't called during compilation
4. **Broken architecture** that doesn't follow PerlOnJava patterns

### Phase 1: Infrastructure (Week 1) - **FOUNDATION PHASE**
**⚠️ THIS PHASE IS MANDATORY - ALL OTHER PHASES DEPEND ON IT**

1. **Create base `SublanguageLexer` and `SublanguageParser` classes**
   - These provide the common tokenization and parsing patterns
   - Without these, individual parsers will be inconsistent and isolated
   
2. **Implement `SublanguageToken` and error handling**
   - Proper error reporting with position information
   - Integration with existing `PerlCompilerException` patterns
   
3. **Add integration points to existing `StringParser`**
   - **CRITICAL**: This is where sublanguage parsing gets called during compilation
   - Without this integration, parsers will never be invoked
   - Must add methods like `parseRegex()`, `parsePackTemplate()`, etc. to `StringParser.java`

**Verification for Phase 1 Completion:**
- Base classes exist and compile
- `StringParser.java` has integration methods
- Test that sublanguage parsing is called during compilation (not runtime)

### Phase 2: Regex Implementation (Week 2)
**Prerequisites: Phase 1 must be complete**

1. **Implement `RegexLexer` extending `SublanguageLexer`**
   - Proper tokenization of regex patterns
   - Must use base class patterns, not custom implementation
   
2. **Create `RegexParser` extending `SublanguageParser`**
   - Semantic validation using token stream
   - AST generation for transformation
   
3. **Replace current regex preprocessing gradually**
   - Update `RegexPreprocessor.java` to use new parser
   - Maintain backward compatibility during transition

**Verification for Phase 2 Completion:**
- Regex parsing happens during compilation
- Debug output shows parser methods being called
- Existing regex tests still pass

### Phase 3: Pack/Unpack Implementation (Week 3)
**Prerequisites: Phase 1 must be complete**

1. **Implement `PackLexer` and `PackParser`**
   - Must extend base classes from Phase 1
   - Integration through `StringParser` methods
   
2. **Add semantic validation for modifier combinations**
   - Proper error reporting for invalid combinations
   
3. **Integrate with existing pack/unpack operators**
   - Update `Pack.java` and `Unpack.java` to use new parser
   - Call parsing during compilation, not runtime

### Phase 4: Sprintf Implementation (Week 4)
**Prerequisites: Phase 1 must be complete**

1. **Implement `SprintfLexer` and `SprintfParser`**
   - Must extend base classes from Phase 1
   - Integration through `StringParser` methods
   
2. **Add format specification validation**
   - Semantic validation of format specifiers
   
3. **Replace current sprintf parsing**
   - Update `SprintfOperator.java` to use compile-time parsing
   - Remove runtime parsing logic

### Phase 5: Transliteration Implementation (Week 5)
**Prerequisites: Phase 1 must be complete**

1. **Implement `TrLexer` and `TrParser`**
   - Must extend base classes from Phase 1
   - Integration through `StringParser` methods
   
2. **Add pattern expansion and validation**
   - Handle escape sequences, ranges, character classes
   - Proper octal/hex parsing (learned from failed implementation)
   
3. **Replace current transliteration parsing**
   - Update `RuntimeTransliterate.java` to use compile-time parsing
   - Remove runtime parsing logic

### Phase 6: Additional Sublanguages (Future)
1. Heredoc enhancement
2. Other domain-specific languages as needed

## ERROR HANDLING ARCHITECTURE (Enhanced September 2024)

### Two-Error-Type System

The sublanguage parser architecture supports two distinct types of errors to handle PerlOnJava's incremental implementation approach:

#### 1. Syntax Errors (`SYNTAX_ERROR`)
- **Definition**: User mistakes - invalid syntax, malformed patterns
- **Examples**: 
  - Pack: `"l<>4"` (invalid endianness syntax)
  - Regex: `"/[/"` (unmatched bracket)
  - Sprintf: `"%q"` (invalid conversion specifier)
  - Transliteration: `"tr/a-z-0-9//"` (invalid range)
- **Handling**: Should fail compilation immediately
- **User Experience**: Clear error message pointing to the syntax mistake

#### 2. Unimplemented Errors (`UNIMPLEMENTED_ERROR`)
- **Definition**: Valid Perl syntax but not yet supported in PerlOnJava
- **Examples**:
  - Pack: `"w"` format (BER compressed integer - valid Perl, not implemented)
  - Regex: `"(?{code})"` (code execution in regex - valid Perl, not implemented)
  - Sprintf: `"%v"` vector flag (valid Perl, partially implemented)
- **Handling**: May fall back to existing behavior or provide helpful "not yet implemented" message
- **User Experience**: Informative message about what's not supported yet

### Benefits of Two-Error-Type System

1. **Clear User Feedback**: Users know if they made a mistake vs. hit a limitation
2. **Incremental Implementation**: Unimplemented features can be added gradually
3. **Graceful Degradation**: Valid Perl can fall back to existing behavior
4. **Better Testing**: Can distinguish between bugs and missing features
5. **Development Prioritization**: Unimplemented errors guide feature development

### Implementation

```java
// Syntax error - user mistake
return SublanguageValidationResult.syntaxError("Invalid range 'z-a' in pack template", position);

// Unimplemented error - valid Perl, not supported yet
return SublanguageValidationResult.unimplementedError("BER compressed integer format 'w' not yet implemented");

// Check error types
if (result.hasSyntaxErrors()) {
    // Fail compilation immediately
    throw new PerlCompilerException(result.getFirstError());
} else if (result.hasUnimplementedErrors()) {
    // Log warning and fall back to existing behavior
    System.err.println("Warning: " + result.getFirstError());
    return fallbackBehavior();
}
```

## LESSONS LEARNED FROM FAILED IMPLEMENTATION (September 2024)

### What We Did Wrong

**❌ Skipped Phase 1 Infrastructure**
- We jumped straight to implementing `TrLexer`, `TrParser`, `SprintfLexer`, etc.
- We created individual parsers without the base `SublanguageLexer`/`SublanguageParser` classes
- Result: Inconsistent, isolated parsers that weren't integrated with the main system

**❌ Wrong Execution Model**
- We put parsing logic in runtime operator classes (`RuntimeTransliterate.compile()`)
- We tried to parse patterns at runtime instead of compile time
- Result: Parsers were never called because they weren't in the compilation pipeline

**❌ No StringParser Integration**
- We never added integration points to `StringParser.java`
- Our parsers existed in isolation without connection to the main parser
- Result: No way for the main compilation process to invoke our sublanguage parsers

**❌ Missing AST Generation**
- We did direct string manipulation instead of generating ASTs
- We didn't follow the plan's AST → transformation → output model
- Result: No semantic understanding, just token shuffling

### Symptoms of the Wrong Approach

1. **Debug output never appears** - because methods aren't called during compilation
2. **Runtime parsing fails** - because the execution model is wrong
3. **Isolated functionality** - parsers work in isolation but not integrated
4. **No error integration** - custom error handling instead of `PerlCompilerException`

### Key Insights

**🔑 Phase 1 is Not Optional**
The base infrastructure (Phase 1) is not just a "nice to have" - it's the foundation that makes everything else work. Without it:
- Individual parsers have no way to integrate with the main system
- There's no consistent pattern for tokenization and parsing
- Error handling is fragmented and inconsistent

**🔑 Integration is Everything**
The most important part of the architecture is the integration with `StringParser.java`. This is where:
- Sublanguage parsing gets triggered during compilation
- The main parser delegates to specialized sublanguage parsers
- ASTs get generated and transformed into usable output

**🔑 Compile-Time vs Runtime**
Sublanguage parsing must happen during **compilation**, not runtime:
- Compile-time: Patterns are parsed, validated, and transformed into efficient runtime structures
- Runtime: Pre-compiled structures are used directly without parsing overhead
- Our failed approach tried to parse at runtime, which is the wrong model

### Success Criteria for Proper Implementation

1. **Phase 1 Verification**: Can call sublanguage parsing from `StringParser.java` and see debug output
2. **Compile-Time Parsing**: Sublanguage parsing happens when code is compiled, not when it runs
3. **AST Generation**: Parsers produce ASTs that get transformed, not direct string manipulation
4. **Error Integration**: Sublanguage errors use `PerlCompilerException` with proper location info
5. **Consistent Patterns**: All sublanguage parsers extend the same base classes and follow the same patterns

## Conclusion

This approach leverages PerlOnJava's existing, proven architecture while providing the benefits of proper sublanguage parsing:

- **Eliminates "cheating"** with semantic understanding
- **Maintains performance** without external dependencies  
- **Provides consistency** across all sublanguages
- **Enables incremental adoption** with minimal risk
- **Follows established patterns** familiar to the team

The result is a clean, maintainable architecture that eliminates the parsing issues we've identified while staying true to PerlOnJava's design principles.
