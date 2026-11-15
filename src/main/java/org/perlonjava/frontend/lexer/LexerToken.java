package org.perlonjava.frontend.lexer;

/**
 * The LexerToken class represents a lexical token in a programming language or text.
 * A token is a basic unit of meaningful data, such as a keyword, identifier, operator,
 * or literal, that is produced by a lexical analyzer (lexer) during the process of
 * lexical analysis.
 *
 * <p>This class encapsulates the type and text of a token. The type is represented
 * by an instance of the LexerTokenType enum, which categorizes the token (e.g., keyword,
 * identifier, operator). The text is the actual string of characters that make up
 * the token.</p>
 */
public class LexerToken {
    /**
     * The type of the token, represented by an instance of the LexerTokenType enum.
     * This field categorizes the token (e.g., keyword, identifier, operator).
     */
    public final LexerTokenType type;

    /**
     * The text of the token, represented as a string.
     * This field contains the actual string of characters that make up the token.
     */
    public final String text;

    // Flyweight cache: single-char OPERATOR tokens and common constant tokens.
    // Fields are final so sharing instances across parses is safe.
    private static final LexerToken[] OPERATOR_CACHE = new LexerToken[128];
    public static final LexerToken TOKEN_EOF      = new LexerToken(LexerTokenType.EOF, "<EOF>");
    public static final LexerToken TOKEN_NEWLINE  = new LexerToken(LexerTokenType.NEWLINE, "\n");
    public static final LexerToken TOKEN_SPACE    = new LexerToken(LexerTokenType.WHITESPACE, " ");

    // Cache pure-space WHITESPACE tokens up to this length (covers 1–4 level indentation
    // at 2/4/8-space increments; eval STRING tokenizes indented source on every call).
    private static final int MAX_CACHED_SPACES = 20;
    private static final LexerToken[] SPACE_CACHE = new LexerToken[MAX_CACHED_SPACES + 1];

    // Cache NUMBER tokens for small non-negative integers (0–99). eval STRING tokenizes
    // its source on every call; loop counters and small literals like 1 and 40 recur
    // constantly. Consumed by Lexer.consumeNumber() which avoids substring() for these.
    static final int MAX_CACHED_INT = 99;
    static final LexerToken[] INT_CACHE = new LexerToken[MAX_CACHED_INT + 1];

    // Round 36: cached IDENTIFIER tokens for common Perl keywords — avoids both
    // String.substring() and LexerToken allocation in Lexer.consumeIdentifier().
    // Texts are interned so any == comparisons on token.text continue to work.
    static final LexerToken KW_MY      = new LexerToken(LexerTokenType.IDENTIFIER, "my");
    static final LexerToken KW_IF      = new LexerToken(LexerTokenType.IDENTIFIER, "if");
    static final LexerToken KW_OR      = new LexerToken(LexerTokenType.IDENTIFIER, "or");
    static final LexerToken KW_DO      = new LexerToken(LexerTokenType.IDENTIFIER, "do");
    static final LexerToken KW_NO      = new LexerToken(LexerTokenType.IDENTIFIER, "no");
    static final LexerToken KW_SUB     = new LexerToken(LexerTokenType.IDENTIFIER, "sub");
    static final LexerToken KW_USE     = new LexerToken(LexerTokenType.IDENTIFIER, "use");
    static final LexerToken KW_FOR     = new LexerToken(LexerTokenType.IDENTIFIER, "for");
    static final LexerToken KW_OUR     = new LexerToken(LexerTokenType.IDENTIFIER, "our");
    static final LexerToken KW_NOT     = new LexerToken(LexerTokenType.IDENTIFIER, "not");
    static final LexerToken KW_SAY     = new LexerToken(LexerTokenType.IDENTIFIER, "say");
    static final LexerToken KW_DIE     = new LexerToken(LexerTokenType.IDENTIFIER, "die");
    static final LexerToken KW_REF     = new LexerToken(LexerTokenType.IDENTIFIER, "ref");
    static final LexerToken KW_POP     = new LexerToken(LexerTokenType.IDENTIFIER, "pop");
    static final LexerToken KW_ELSE    = new LexerToken(LexerTokenType.IDENTIFIER, "else");
    static final LexerToken KW_LAST    = new LexerToken(LexerTokenType.IDENTIFIER, "last");
    static final LexerToken KW_NEXT    = new LexerToken(LexerTokenType.IDENTIFIER, "next");
    static final LexerToken KW_REDO    = new LexerToken(LexerTokenType.IDENTIFIER, "redo");
    static final LexerToken KW_PUSH    = new LexerToken(LexerTokenType.IDENTIFIER, "push");
    static final LexerToken KW_SORT    = new LexerToken(LexerTokenType.IDENTIFIER, "sort");
    static final LexerToken KW_EVAL    = new LexerToken(LexerTokenType.IDENTIFIER, "eval");
    static final LexerToken KW_WARN    = new LexerToken(LexerTokenType.IDENTIFIER, "warn");
    static final LexerToken KW_EACH    = new LexerToken(LexerTokenType.IDENTIFIER, "each");
    static final LexerToken KW_KEYS    = new LexerToken(LexerTokenType.IDENTIFIER, "keys");
    static final LexerToken KW_JOIN    = new LexerToken(LexerTokenType.IDENTIFIER, "join");
    static final LexerToken KW_WHILE   = new LexerToken(LexerTokenType.IDENTIFIER, "while");
    static final LexerToken KW_UNTIL   = new LexerToken(LexerTokenType.IDENTIFIER, "until");
    static final LexerToken KW_PRINT   = new LexerToken(LexerTokenType.IDENTIFIER, "print");
    static final LexerToken KW_ELSIF   = new LexerToken(LexerTokenType.IDENTIFIER, "elsif");
    static final LexerToken KW_LOCAL   = new LexerToken(LexerTokenType.IDENTIFIER, "local");
    static final LexerToken KW_BEGIN   = new LexerToken(LexerTokenType.IDENTIFIER, "BEGIN");
    static final LexerToken KW_SPLIT   = new LexerToken(LexerTokenType.IDENTIFIER, "split");
    static final LexerToken KW_RETURN  = new LexerToken(LexerTokenType.IDENTIFIER, "return");
    static final LexerToken KW_UNLESS  = new LexerToken(LexerTokenType.IDENTIFIER, "unless");
    static final LexerToken KW_SCALAR  = new LexerToken(LexerTokenType.IDENTIFIER, "scalar");
    static final LexerToken KW_FOREACH = new LexerToken(LexerTokenType.IDENTIFIER, "foreach");
    static final LexerToken KW_DEFINED = new LexerToken(LexerTokenType.IDENTIFIER, "defined");

    static {
        for (char c : "!\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~".toCharArray()) {
            // Round 28: intern single-char text so callers can use == instead of .equals()
            OPERATOR_CACHE[c] = new LexerToken(LexerTokenType.OPERATOR, String.valueOf(c).intern());
        }
        SPACE_CACHE[1] = TOKEN_SPACE;
        for (int i = 2; i <= MAX_CACHED_SPACES; i++) {
            SPACE_CACHE[i] = new LexerToken(LexerTokenType.WHITESPACE, " ".repeat(i));
        }
        for (int i = 0; i <= MAX_CACHED_INT; i++) {
            INT_CACHE[i] = new LexerToken(LexerTokenType.NUMBER, Integer.toString(i));
        }
    }

    /**
     * Round 36: return a cached IDENTIFIER token for a common Perl keyword, or null.
     * Compares input characters directly — no String.substring() or LexerToken allocation.
     *
     * @param src   the full source string being lexed
     * @param start the index of the first character of the identifier
     * @param len   the length of the identifier (= position - start)
     */
    public static LexerToken ofKeyword(String src, int start, int len) {
        switch (len) {
            case 2: {
                char a = src.charAt(start), b = src.charAt(start + 1);
                if (a=='m'&&b=='y') return KW_MY;
                if (a=='i'&&b=='f') return KW_IF;
                if (a=='o'&&b=='r') return KW_OR;
                if (a=='d'&&b=='o') return KW_DO;
                if (a=='n'&&b=='o') return KW_NO;
                break;
            }
            case 3: {
                char a=src.charAt(start),b=src.charAt(start+1),c=src.charAt(start+2);
                if (a=='s'&&b=='u'&&c=='b') return KW_SUB;
                if (a=='u'&&b=='s'&&c=='e') return KW_USE;
                if (a=='f'&&b=='o'&&c=='r') return KW_FOR;
                if (a=='o'&&b=='u'&&c=='r') return KW_OUR;
                if (a=='n'&&b=='o'&&c=='t') return KW_NOT;
                if (a=='s'&&b=='a'&&c=='y') return KW_SAY;
                if (a=='d'&&b=='i'&&c=='e') return KW_DIE;
                if (a=='r'&&b=='e'&&c=='f') return KW_REF;
                if (a=='p'&&b=='o'&&c=='p') return KW_POP;
                break;
            }
            case 4: {
                char a=src.charAt(start),b=src.charAt(start+1),c=src.charAt(start+2),d=src.charAt(start+3);
                if (a=='e'&&b=='l'&&c=='s'&&d=='e') return KW_ELSE;
                if (a=='l'&&b=='a'&&c=='s'&&d=='t') return KW_LAST;
                if (a=='n'&&b=='e'&&c=='x'&&d=='t') return KW_NEXT;
                if (a=='r'&&b=='e'&&c=='d'&&d=='o') return KW_REDO;
                if (a=='p'&&b=='u'&&c=='s'&&d=='h') return KW_PUSH;
                if (a=='s'&&b=='o'&&c=='r'&&d=='t') return KW_SORT;
                if (a=='e'&&b=='v'&&c=='a'&&d=='l') return KW_EVAL;
                if (a=='w'&&b=='a'&&c=='r'&&d=='n') return KW_WARN;
                if (a=='e'&&b=='a'&&c=='c'&&d=='h') return KW_EACH;
                if (a=='k'&&b=='e'&&c=='y'&&d=='s') return KW_KEYS;
                if (a=='j'&&b=='o'&&c=='i'&&d=='n') return KW_JOIN;
                break;
            }
            case 5: {
                char a=src.charAt(start);
                if (a=='w') { if (src.charAt(start+1)=='h'&&src.charAt(start+2)=='i'&&src.charAt(start+3)=='l'&&src.charAt(start+4)=='e') return KW_WHILE; break; }
                if (a=='u') { if (src.charAt(start+1)=='n'&&src.charAt(start+2)=='t'&&src.charAt(start+3)=='i'&&src.charAt(start+4)=='l') return KW_UNTIL; break; }
                if (a=='p') { if (src.charAt(start+1)=='r'&&src.charAt(start+2)=='i'&&src.charAt(start+3)=='n'&&src.charAt(start+4)=='t') return KW_PRINT; break; }
                if (a=='e') { if (src.charAt(start+1)=='l'&&src.charAt(start+2)=='s'&&src.charAt(start+3)=='i'&&src.charAt(start+4)=='f') return KW_ELSIF; break; }
                if (a=='l') { if (src.charAt(start+1)=='o'&&src.charAt(start+2)=='c'&&src.charAt(start+3)=='a'&&src.charAt(start+4)=='l') return KW_LOCAL; break; }
                if (a=='B') { if (src.charAt(start+1)=='E'&&src.charAt(start+2)=='G'&&src.charAt(start+3)=='I'&&src.charAt(start+4)=='N') return KW_BEGIN; break; }
                if (a=='s') { if (src.charAt(start+1)=='p'&&src.charAt(start+2)=='l'&&src.charAt(start+3)=='i'&&src.charAt(start+4)=='t') return KW_SPLIT; break; }
                break;
            }
            case 6: {
                char a=src.charAt(start);
                if (a=='r') { if (src.charAt(start+1)=='e'&&src.charAt(start+2)=='t'&&src.charAt(start+3)=='u'&&src.charAt(start+4)=='r'&&src.charAt(start+5)=='n') return KW_RETURN; break; }
                if (a=='u') { if (src.charAt(start+1)=='n'&&src.charAt(start+2)=='l'&&src.charAt(start+3)=='e'&&src.charAt(start+4)=='s'&&src.charAt(start+5)=='s') return KW_UNLESS; break; }
                if (a=='s') { if (src.charAt(start+1)=='c'&&src.charAt(start+2)=='a'&&src.charAt(start+3)=='l'&&src.charAt(start+4)=='a'&&src.charAt(start+5)=='r') return KW_SCALAR; break; }
                break;
            }
            case 7: {
                char a=src.charAt(start);
                if (a=='f'&&src.charAt(start+1)=='o'&&src.charAt(start+2)=='r'&&src.charAt(start+3)=='e'&&src.charAt(start+4)=='a'&&src.charAt(start+5)=='c'&&src.charAt(start+6)=='h') return KW_FOREACH;
                if (a=='d'&&src.charAt(start+1)=='e'&&src.charAt(start+2)=='f'&&src.charAt(start+3)=='i'&&src.charAt(start+4)=='n'&&src.charAt(start+5)=='e'&&src.charAt(start+6)=='d') return KW_DEFINED;
                break;
            }
        }
        return null;
    }

    /**
     * Return the cached OPERATOR token for {@code c}, or a new one when not cached.
     * Avoids {@code String.valueOf(c)} for the common single-char case.
     */
    public static LexerToken ofOperatorChar(char c) {
        if (c < 128) {
            LexerToken cached = OPERATOR_CACHE[c];
            if (cached != null) return cached;
        }
        return new LexerToken(LexerTokenType.OPERATOR, String.valueOf(c));
    }

    /**
     * Return the cached WHITESPACE token for a run of {@code count} spaces,
     * or a new one when the count exceeds the cache range.
     * Avoids {@code String.substring()} for the common indentation case.
     */
    public static LexerToken ofPureSpaces(int count) {
        if (count >= 1 && count <= MAX_CACHED_SPACES) {
            return SPACE_CACHE[count];
        }
        return new LexerToken(LexerTokenType.WHITESPACE, " ".repeat(count));
    }

    /**
     * Factory that returns a cached instance for common tokens, or allocates a new one.
     * Use this instead of {@code new LexerToken(...)} everywhere except the static
     * initialiser above.
     */
    public static LexerToken of(LexerTokenType type, String text) {
        if (type == LexerTokenType.OPERATOR && text.length() == 1) {
            char c = text.charAt(0);
            if (c < 128) {
                LexerToken cached = OPERATOR_CACHE[c];
                if (cached != null) return cached;
            }
        } else if (type == LexerTokenType.EOF) {
            return TOKEN_EOF;
        } else if (type == LexerTokenType.NEWLINE && "\n".equals(text)) {
            return TOKEN_NEWLINE;
        } else if (type == LexerTokenType.WHITESPACE) {
            int len = text.length();
            if (len >= 1 && len <= MAX_CACHED_SPACES && text.charAt(0) == ' ') {
                LexerToken cached = SPACE_CACHE[len];
                if (cached.text.equals(text)) return cached;
            }
        }
        return new LexerToken(type, text);
    }

    /**
     * Constructs a new LexerToken with the specified type and text.
     * Prefer the {@link #of} factory for common token types.
     *
     * @param type the type of the token, represented by an instance of the LexerTokenType enum
     * @param text the text of the token, represented as a string
     */
    public LexerToken(LexerTokenType type, String text) {
        this.type = type;
        this.text = text;
    }

    /**
     * Returns a string representation of the token.
     * The string representation includes the type and text of the token.
     *
     * @return a string representation of the token
     */
    @Override
    public String toString() {
        return "LexerToken{" + "type=" + type + ", text='" + text + '\'' + '}';
    }
}

