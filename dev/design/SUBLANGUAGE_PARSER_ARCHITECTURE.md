# Sublanguage Parser Architecture: Unified Lexer→Parser→AST Framework

## Overview

This document defines the comprehensive sublanguage parser architecture for PerlOnJava, designed to provide **proper semantic parsing** for domain-specific languages embedded within Perl using a **unified lexer→parser→AST framework**.

## Why This Architecture? (Critical Understanding)

### The Problem: Perl's Embedded Sublanguages

Perl contains multiple "sublanguages" - specialized syntaxes within string literals that have their own complex parsing rules:

- **Regex patterns**: `/(?:pattern){3,5}/flags` - complex syntax with quantifiers, groups, escapes
- **Pack/unpack templates**: `pack("A10i4l<*", ...)` - binary data formatting with endianness and counts  
- **Sprintf formats**: `sprintf("%*.*f", width, precision, value)` - printf-style formatting with dynamic width
- **Transliteration**: `tr/\x00-\xFF/a-z/` - character mapping with ranges and escapes
- **Double-quoted strings**: `"Hello \x41\n$var"` - interpolation with escapes and variables

### Current Problems (Why We Need This Architecture)

1. **Manual String Manipulation**: Current parsing uses character-by-character loops
   - *Result*: Parsing bugs, poor maintainability, no semantic understanding
   
2. **Runtime Parsing Overhead**: Patterns parsed every time code executes
   - *Result*: Performance penalty, especially in loops
   
3. **Inconsistent Error Handling**: Each sublanguage has different error reporting
   - *Result*: Poor developer experience, inconsistent error messages
   
4. **No Semantic Understanding**: Direct string manipulation without AST
   - *Result*: Cannot validate semantics, transform patterns, or optimize

### The Solution: Unified Lexer→Parser→AST Architecture

**Core Principle**: All sublanguages follow the same **lexer→parser→AST** pattern for consistency, maintainability, and semantic understanding.

**Architecture Flow:**
```
Input String → SublanguageLexer → SublanguageToken[] → SublanguageParser → SublanguageASTNode → Validation/Transformation
```

**Why This Approach:**

1. **Compile-Time Parsing**: Parse once during compilation, use optimized structures at runtime
2. **Semantic Understanding**: AST enables validation, transformation, and optimization  
3. **Consistent Error Reporting**: All sublanguages use same position tracking and error formatting
4. **Maintainable Code**: Unified patterns across all sublanguages
5. **Extensible**: Easy to add new sublanguages following the same pattern

## Architecture Principles (CRITICAL - Read This First!)

### Principle 1: Mandatory Lexer→Parser→AST Flow

**RULE**: Every sublanguage MUST follow this exact flow:

```java
// CORRECT: Follow the architecture
String input = "\\x41\\n";
SublanguageLexer lexer = new RegexLexer(input, sourceOffset);
List<SublanguageToken> tokens = lexer.tokenize();
SublanguageParser parser = new RegexParser(tokens);
SublanguageASTNode ast = parser.parse();

// WRONG: Utility functions that bypass architecture  
String result = EscapeSequenceParser.parseEscape(input, 0); // ❌ VIOLATES ARCHITECTURE
```

**Why**: Bypassing the lexer→parser→AST flow breaks consistency, error reporting, and semantic understanding.

### Principle 2: No Utility Shortcuts

**RULE**: Reusable components MUST work within the lexer→parser→AST framework.

```java
// CORRECT: Reusable components within architecture
public class RegexLexer extends SublanguageLexer {
    protected boolean tokenizeEscape() {
        // Tokenize escape sequence into SublanguageToken
        // This can be shared across sublanguage lexers
        return tokenizeCommonEscape(); // ✅ FOLLOWS ARCHITECTURE
    }
}

// WRONG: Utility functions outside architecture
public class EscapeUtils {
    public static String parseEscape(String input, int pos) { // ❌ BYPASSES ARCHITECTURE
        // Direct string manipulation
    }
}
```

**Why**: Utility shortcuts bypass position tracking, error handling, and AST generation.

### Principle 3: AST-First Design

**RULE**: All parsing MUST produce `SublanguageASTNode` objects for semantic analysis.

```java
// CORRECT: AST-first design
SublanguageASTNode escapeNode = new SublanguageASTNode("escape", "\\n");
escapeNode.addAnnotation("resolvedValue", "\n");
escapeNode.addAnnotation("escapeType", "newline");

// WRONG: Direct string results
String result = "\\n"; // ❌ NO SEMANTIC INFORMATION
```

**Why**: AST enables semantic validation, transformation, and optimization that strings cannot provide.

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

## Sublanguage Implementation Phases

### CRITICAL SUCCESS FACTORS (Learned from Failed Implementation)

**⚠️ MUST FOLLOW ARCHITECTURE PRINCIPLES - NO SHORTCUTS!**

The phases below implement sublanguages following the **mandatory lexer→parser→AST architecture**. Violating these principles will result in:

1. **Broken architecture** - Utility shortcuts bypass lexer/parser/AST flow
2. **No semantic understanding** - Direct string manipulation loses AST benefits  
3. **Inconsistent error handling** - Position tracking and validation broken
4. **Maintenance nightmare** - Code scattered across utility classes instead of unified architecture

### Phase 1: Foundation Infrastructure ✅ COMPLETED
**Status**: Base architecture implemented and verified

**What Was Built:**
- `SublanguageLexer` - Base class with tokenization patterns and position tracking
- `SublanguageParser` - Base class returning `SublanguageASTNode` objects
- `SublanguageToken` - Position-aware tokens with source offset mapping
- `SublanguageASTNode` - Generic AST nodes with annotations for all sublanguages
- `SublanguageValidationResult` - Two-error-type system (SYNTAX_ERROR vs UNIMPLEMENTED_ERROR)

**Architecture Verification:**
- ✅ All base classes follow lexer→parser→AST flow
- ✅ No utility shortcuts that bypass architecture
- ✅ AST-first design with semantic annotations
- ✅ Consistent error handling with position tracking

### Phase 2: Double-Quoted String Parser (Week 1)
**Priority**: HIGH - Foundation for other sublanguages
**Prerequisites**: Phase 1 complete

**Why Double-Quoted Strings First:**
- Simpler sublanguage to establish the pattern
- Contains common elements (escapes, interpolation) used by other sublanguages
- Validates the architecture before more complex sublanguages

**Implementation:**
1. **DoubleQuotedLexer extends SublanguageLexer**
   ```java
   // Tokenize: "Hello \x41\n$var" 
   // → [LITERAL:"Hello ", ESCAPE:"\x41", ESCAPE:"\n", VARIABLE:"$var"]
   ```

2. **DoubleQuotedParser extends SublanguageParser**
   ```java
   // Parse tokens → SublanguageASTNode tree
   // Enable semantic validation of escapes and interpolation
   ```

3. **Integration via static method**
   ```java
   // In DoubleQuotedParser.java
   public static SublanguageValidationResult validateString(String content, int sourceOffset)
   ```

**Verification:**
- Lexer produces proper `SublanguageToken` objects with position tracking
- Parser produces `SublanguageASTNode` AST (not strings)
- Integration works via static method (no StringParser pollution)
- Escape sequences tokenized properly (foundation for other sublanguages)

### Phase 3: Regex Parser (Week 2)
**Prerequisites**: Phase 2 complete (establishes escape handling pattern)

**Implementation follows same lexer→parser→AST pattern:**
1. **RegexLexer extends SublanguageLexer** - Tokenize regex syntax
2. **RegexParser extends SublanguageParser** - Build AST for semantic validation
3. **Static integration method** - `RegexParser.validatePattern()`

### Phase 4: Pack/Unpack Parser (Week 3)  
**Priority**: HIGH - Serious test failures to resolve
**Prerequisites**: Phase 2 complete (establishes number/count tokenization pattern)

### Phase 5: Sprintf Parser (Week 4)
**Prerequisites**: Phase 2 complete (establishes format tokenization pattern)

### Phase 6: Transliteration Parser (Week 5)
**Prerequisites**: Phase 2 complete (establishes escape and range tokenization pattern)

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
