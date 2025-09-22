package org.perlonjava.parser.sublanguage;

/**
 * Enumeration of all token types used across different sublanguage parsers.
 * 
 * This comprehensive enum covers tokens for:
 * - Regex patterns (literals, escapes, quantifiers, groups, classes)
 * - Pack/Unpack templates (formats, modifiers, counts, groups)
 * - Sprintf format strings (flags, width, precision, conversion specifiers)
 * - Transliteration patterns (escapes, ranges, character classes)
 * - Common tokens (literals, whitespace, EOF, etc.)
 */
public enum SublanguageTokenType {
    
    // === COMMON TOKENS ===
    /** End of input */
    EOF,
    /** Literal text/characters */
    LITERAL,
    /** Whitespace (spaces, tabs, newlines) */
    WHITESPACE,
    /** Generic escape sequence */
    ESCAPE,
    /** Invalid/unrecognized token */
    INVALID,
    
    // === REGEX TOKENS ===
    /** Regex quantifier: *, +, ?, {n,m} */
    REGEX_QUANTIFIER,
    /** Regex group: (, ), (?:, (?=, etc. */
    REGEX_GROUP,
    /** Regex character class: [abc], [^abc], \d, \w, etc. */
    REGEX_CLASS,
    /** Regex anchor: ^, $, \b, \A, \Z, etc. */
    REGEX_ANCHOR,
    /** Regex alternation: | */
    REGEX_ALTERNATION,
    /** Regex assertion: (?=, (?!, (?<=, (?<!, etc. */
    REGEX_ASSERTION,
    /** Regex backreference: \1, \2, etc. */
    REGEX_BACKREF,
    /** Regex named capture: (?<name>, \k<name>, etc. */
    REGEX_NAMED_CAPTURE,
    /** Regex modifier: i, m, s, x, etc. */
    REGEX_MODIFIER,
    /** Regex escape: \n, \t, \x{}, \N{}, etc. */
    REGEX_ESCAPE,
    
    // === PACK/UNPACK TOKENS ===
    /** Pack format specifier: a, A, c, C, s, S, l, L, etc. */
    PACK_FORMAT,
    /** Pack count: number or * */
    PACK_COUNT,
    /** Pack modifier: < (little-endian), > (big-endian), ! (native) */
    PACK_MODIFIER,
    /** Pack group: (, ) */
    PACK_GROUP,
    /** Pack whitespace/separator */
    PACK_SEPARATOR,
    
    // === SPRINTF TOKENS ===
    /** Sprintf flag: -, +, #, 0, space */
    SPRINTF_FLAG,
    /** Sprintf width specifier: number or * */
    SPRINTF_WIDTH,
    /** Sprintf precision specifier: .number or .* */
    SPRINTF_PRECISION,
    /** Sprintf conversion specifier: d, s, f, x, etc. */
    SPRINTF_CONVERSION,
    /** Sprintf vector flag: v */
    SPRINTF_VECTOR,
    /** Sprintf position specifier: %1$, %2$, etc. */
    SPRINTF_POSITION,
    /** Sprintf percent literal: %% */
    SPRINTF_PERCENT,
    
    // === TRANSLITERATION (tr///) TOKENS ===
    /** Transliteration range: a-z, 0-9, etc. */
    TR_RANGE,
    /** Transliteration character class: [:alpha:], [:digit:], etc. */
    TR_CLASS,
    /** Transliteration escape: \n, \t, \x{}, \N{}, etc. */
    TR_ESCAPE,
    /** Transliteration modifier: c, d, s */
    TR_MODIFIER,
    
    // === NUMERIC LITERALS ===
    /** Decimal number: 123, 456 */
    NUMBER_DECIMAL,
    /** Hexadecimal number: 0x1A, 0xFF */
    NUMBER_HEX,
    /** Octal number: 0123, 0777 */
    NUMBER_OCTAL,
    /** Binary number: 0b1010, 0b1111 */
    NUMBER_BINARY,
    
    // === DELIMITERS AND OPERATORS ===
    /** Left parenthesis: ( */
    LPAREN,
    /** Right parenthesis: ) */
    RPAREN,
    /** Left bracket: [ */
    LBRACKET,
    /** Right bracket: ] */
    RBRACKET,
    /** Left brace: { */
    LBRACE,
    /** Right brace: } */
    RBRACE,
    /** Comma: , */
    COMMA,
    /** Dot/period: . */
    DOT,
    /** Dash/hyphen: - (used in ranges) */
    DASH,
    /** Plus: + */
    PLUS,
    /** Star/asterisk: * */
    STAR,
    /** Question mark: ? */
    QUESTION,
    /** Pipe/bar: | */
    PIPE,
    /** Caret: ^ */
    CARET,
    /** Dollar: $ */
    DOLLAR,
    /** Backslash: \ */
    BACKSLASH,
    /** Forward slash: / */
    SLASH,
    /** Percent: % */
    PERCENT,
    /** Hash/pound: # */
    HASH,
    /** Exclamation: ! */
    EXCLAMATION,
    /** Less than: < */
    LESS_THAN,
    /** Greater than: > */
    GREATER_THAN,
    /** Equals: = */
    EQUALS,
    /** Colon: : */
    COLON,
    /** Semicolon: ; */
    SEMICOLON,
    
    // === SPECIAL SEQUENCES ===
    /** Unicode escape: \x{1234}, \N{NAME} */
    UNICODE_ESCAPE,
    /** Control character: \c[, \cA, etc. */
    CONTROL_CHAR,
    /** Named character class: \d, \w, \s, etc. */
    NAMED_CLASS,
    /** Octal escape: \123, \377, etc. */
    OCTAL_ESCAPE,
    /** Hex escape: \x1A, \xFF, etc. */
    HEX_ESCAPE,
    /** Simple escape: \n, \t, \r, etc. */
    SIMPLE_ESCAPE
}
