package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.AbstractNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;

import java.util.List;
import java.util.Set;

/**
 * Computes the source line Perl associates with a statement (its COP line).
 *
 * <p>Perl attaches one COP per statement and {@code caller}, {@code warn}, and
 * {@code die} all report that single line.  The line is not simply the
 * statement's first line: {@code perl}'s lexer maintains {@code PL_parser->copline}
 * and lowers it through the {@code CLINE} macro
 * ({@code copline = min(copline, current_lexer_line)}) at selected token points
 * only.  {@code newSTATEOP} then consumes {@code copline}, or, when no
 * {@code CLINE} fired, falls back to the lexer's line at the moment the COP is
 * created — in practice the statement's terminating semicolon.
 *
 * <p>Two consequences are visible to Perl programs and are modelled here:
 *
 * <ul>
 *   <li><b>Which tokens arm the line.</b>  Only tokens returned through
 *       {@code TERM()}/{@code LOP()} and a handful of explicit {@code CLINE}
 *       sites in {@code toke.c} count: literals, quote-like constructs,
 *       barewords used as terms, sigils, {@code )}, {@code ]}, list operators.
 *       {@code my}, {@code return}, {@code ->method}, {@code (} and most infix
 *       operators do not.  So {@code sub f { return\n  $x\n  ->m; }} reports the
 *       {@code $x} line, not the {@code return} line.</li>
 *   <li><b>The lookahead swallow.</b>  A brace-terminated compound statement
 *       ({@code if (...) { }}, a loop, a bare block, {@code { package X; ... }})
 *       needs one lookahead token past its {@code }} before the LALR parser can
 *       reduce it and build its COP.  That lookahead is the <i>next</i>
 *       statement's first token, and building the compound statement's COP
 *       resets {@code copline}, discarding it.  The following statement
 *       therefore loses its first token's contribution and takes the next
 *       armed line instead.  A construct that produces no COP (a named
 *       {@code sub} declaration, {@code package NAME;}, a phaser block) does not
 *       consume {@code copline}, so it does not cause the shift.</li>
 * </ul>
 *
 * <p>Only multi-line statements can observe any of this: when every token of a
 * statement is on one line, each candidate yields the same line.
 */
final class StatementCopline {

    /**
     * Identifiers that do not arm {@code copline} in {@code perl}'s lexer.
     * Declarators and {@code return} use {@code OPERATOR()}, control-flow
     * keywords use {@code TOKEN()}/{@code OPERATOR()}, and the word-ish infix
     * operators are not terms.  Quote-like operators ({@code q}, {@code qq},
     * {@code qw}, {@code m}, {@code s}, {@code tr}, {@code y}, {@code qr}) are
     * deliberately absent: they go through {@code sublex_start()} and do arm the
     * line.
     */
    private static final Set<String> NON_ARMING_WORDS = Set.of(
            "ADJUST", "AUTOLOAD", "BEGIN", "CHECK", "DESTROY", "END", "INIT", "UNITCHECK",
            "__CLASS__", "__DATA__", "__END__", "abs", "alarm", "all", "and", "break", "caller",
            "catch", "chdir", "chomp", "chop", "chr", "chroot", "class", "close", "closedir",
            "cmp", "continue", "cos", "dbmclose", "default", "defer", "defined", "delete", "do",
            "dump", "each", "else", "elsif", "endgrent", "endhostent", "endnetent",
            "endprotoent", "endpwent", "endservent", "eof", "eq", "eval", "evalbytes", "exists",
            "exit", "exp", "fc", "field", "fileno", "finally", "for", "foreach", "fork",
            "format", "ge", "getc", "getgrent", "getgrgid", "getgrnam", "gethostbyname",
            "gethostent", "getlogin", "getnetbyname", "getnetent", "getpeername", "getpgrp",
            "getppid", "getprotobyname", "getprotoent", "getpwent", "getpwnam", "getpwuid",
            "getservent", "getsockname", "given", "gmtime", "goto", "gt", "hex", "if", "int",
            "isa", "keys", "last", "lc", "lcfirst", "le", "length", "local", "localtime",
            "lock", "log", "lstat", "lt", "method", "my", "ne", "next", "no", "not", "oct",
            "or", "ord", "our", "pop", "pos", "prototype", "quotemeta", "rand", "readdir",
            "readline", "readlink", "readpipe", "redo", "ref", "require", "reset", "return",
            "rewinddir", "rmdir", "scalar", "setgrent", "sethostent", "setnetent",
            "setprotoent", "setpwent", "setservent", "shift", "sin", "sleep", "sqrt", "srand",
            "stat", "state", "study", "sub", "tell", "telldir", "tied", "time", "times", "try",
            "uc", "ucfirst", "umask", "undef", "unless", "untie", "until", "use", "values",
            "wait", "wantarray", "when", "while", "write", "x", "xor");

    /**
     * Operators that arm {@code copline} when they appear in term position.
     * The sigils and the closing brackets reach {@code TERM()} in {@code toke.c};
     * the quote delimiters reach it through {@code sublex_start()}.
     */
    private static final Set<String> ARMING_OPERATORS = Set.of(
            "$", "@", "%", "&", "*", "$#",
            ")", "]",
            "\"", "'", "`",
            "++", "--");

    /**
     * Token texts that end a complete term.  Used to tell an infix {@code %} or
     * {@code *} from the sigil spelled the same way.
     */
    private static final Set<String> TERM_ENDERS = Set.of(")", "]", "}");

    private StatementCopline() {
    }

    /**
     * Annotation name marking a do-block statement whose COP Perl discards, so
     * calls in it report the enclosing statement's line.
     */
    static final String INHERIT_ENCLOSING_COPLINE = "inheritEnclosingCopline";

    /**
     * A do-block passed through {@code op_scope()} nulls the leading COP when it
     * contains one statement, preserving the enclosing statement's caller line.
     */
    static void markOpScopedBlock(BlockNode block) {
        if (block != null && block.elements.size() == 1
                && block.elements.getFirst() instanceof AbstractNode first) {
            first.setAnnotation(INHERIT_ENCLOSING_COPLINE, true);
        }
    }

    /**
     * Returns the index of the token whose line becomes the statement's COP line.
     *
     * @param tokens          the full token list
     * @param startIndex      index of the statement's first token
     * @param endIndex        exclusive index one past the statement's last token
     * @param swallowFirst    true when the preceding statement was a
     *                        COP-producing brace-terminated statement, so this
     *                        statement's first token was consumed as its
     *                        lookahead
     * @return a token index, or {@code startIndex} when the range is degenerate
     */
    static int coplineTokenIndex(List<LexerToken> tokens, int startIndex, int endIndex,
                                 boolean swallowFirst) {
        if (startIndex < 0 || startIndex >= tokens.size()) {
            return Math.max(startIndex, 0);
        }
        int limit = Math.min(endIndex, tokens.size());

        int firstSignificant = -1;
        int lastSignificant = -1;
        int previousSignificant = -1;
        int armed = -1;
        // Exclusive end of the tokens that make up the statement's first token as
        // perl's lexer sees it; only meaningful when swallowFirst is set.
        int swallowedEnd = -1;
        // A block's opening brace also lowers copline, but from perly.y's block
        // rule rather than from the lexer, so it runs after the COP-consuming
        // reset and survives the lookahead swallow.
        int firstBrace = -1;
        for (int i = startIndex; i < limit; i++) {
            LexerToken token = tokens.get(i);
            if (isSkippable(token)) {
                continue;
            }
            if (token.type == LexerTokenType.EOF) {
                break;
            }
            if (isCommentStart(tokens, i, previousSignificant)) {
                i = skipComment(tokens, i, limit);
                continue;
            }
            if (firstSignificant < 0) {
                firstSignificant = i;
                swallowedEnd = swallowFirst ? firstLexemeEnd(tokens, i, limit) : i;
            }
            lastSignificant = i;
            if (firstBrace < 0 && token.type == LexerTokenType.OPERATOR
                    && "{".equals(token.text)) {
                firstBrace = i;
            }
            boolean eligible = !(swallowFirst && i < swallowedEnd);
            if (eligible && armed < 0 && armsCopline(tokens, i, previousSignificant)) {
                armed = i;
            }
            previousSignificant = i;
        }

        if (armed >= 0) {
            return firstBrace >= 0 ? Math.min(armed, firstBrace) : armed;
        }
        if (firstBrace >= 0) {
            return firstBrace;
        }
        // No token armed copline: newSTATEOP falls back to the lexer's line,
        // which by then has reached the statement's final token.
        if (lastSignificant >= 0) {
            return lastSignificant;
        }
        return firstSignificant >= 0 ? firstSignificant : startIndex;
    }

    /**
     * Reports whether the token range ends at a closing brace, meaning the
     * statement was terminated by a block rather than by a semicolon.
     */
    static boolean endsWithClosingBrace(List<LexerToken> tokens, int startIndex, int endIndex) {
        int limit = Math.min(endIndex, tokens.size());
        int previousSignificant = -1;
        LexerToken last = null;
        for (int i = Math.max(startIndex, 0); i < limit; i++) {
            LexerToken token = tokens.get(i);
            if (isSkippable(token)) {
                continue;
            }
            if (token.type == LexerTokenType.EOF) {
                break;
            }
            if (isCommentStart(tokens, i, previousSignificant)) {
                i = skipComment(tokens, i, limit);
                continue;
            }
            last = token;
            previousSignificant = i;
        }
        return last != null && last.type == LexerTokenType.OPERATOR && "}".equals(last.text);
    }

    /**
     * Returns the exclusive end of the tokens forming what perl's lexer treats as a
     * single token starting at {@code index}.
     *
     * <p>This lexer splits things perl keeps whole: {@code Foo::Bar} arrives as
     * {@code Foo}, {@code ::}, {@code Bar} and {@code $x} as {@code $}, {@code x}.
     * The lookahead that a brace-terminated statement swallows is one <i>perl</i>
     * token, so the whole group has to be skipped -- otherwise the trailing piece
     * would arm the line and hide the shift.
     */
    private static int firstLexemeEnd(List<LexerToken> tokens, int index, int limit) {
        int i = index;
        LexerToken token = tokens.get(i);
        if (token.type == LexerTokenType.OPERATOR && SIGILS.contains(token.text)) {
            // A sigil may be repeated for dereferences: $$x, @$$y.
            while (i < limit && tokens.get(i).type == LexerTokenType.OPERATOR
                    && SIGILS.contains(tokens.get(i).text)) {
                i++;
            }
        }
        // A possibly package-qualified name, with optional leading "::".
        while (i < limit && tokens.get(i).type == LexerTokenType.OPERATOR
                && "::".equals(tokens.get(i).text)) {
            i++;
        }
        if (i < limit && tokens.get(i).type == LexerTokenType.IDENTIFIER) {
            i++;
            while (i + 1 < limit && tokens.get(i).type == LexerTokenType.OPERATOR
                    && "::".equals(tokens.get(i).text)
                    && tokens.get(i + 1).type == LexerTokenType.IDENTIFIER) {
                i += 2;
            }
            // Trailing "::" is part of the name, as in "Foo::"->method.
            while (i < limit && tokens.get(i).type == LexerTokenType.OPERATOR
                    && "::".equals(tokens.get(i).text)) {
                i++;
            }
        }
        return i > index ? i : index + 1;
    }

    /**
     * Variable sigils, which this lexer emits separately from the name.
     */
    private static final Set<String> SIGILS = Set.of("$", "@", "%", "&", "*", "$#");

    private static boolean isSkippable(LexerToken token) {
        return token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.NEWLINE;
    }

    /**
     * The lexer emits a comment as a plain {@code #} operator followed by ordinary
     * tokens up to the end of the line.  A {@code #} that follows a sigil is part
     * of a variable such as {@code $#array} and starts no comment.
     */
    private static boolean isCommentStart(List<LexerToken> tokens, int index, int previousSignificant) {
        LexerToken token = tokens.get(index);
        if (token.type != LexerTokenType.OPERATOR || !"#".equals(token.text)) {
            return false;
        }
        if (previousSignificant >= 0) {
            String previous = tokens.get(previousSignificant).text;
            if ("$".equals(previous) || "@".equals(previous) || "%".equals(previous)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the index of the last token of the comment starting at {@code index},
     * so that a {@code for} loop's increment lands on the terminating newline.
     */
    private static int skipComment(List<LexerToken> tokens, int index, int limit) {
        int i = index;
        while (i < limit && tokens.get(i).type != LexerTokenType.NEWLINE
                && tokens.get(i).type != LexerTokenType.EOF) {
            i++;
        }
        return i;
    }

    /**
     * Reports whether the token at {@code index} arms {@code copline}.
     *
     * @param tokens              the token list
     * @param index               the token to classify
     * @param previousSignificant index of the previous non-whitespace token in
     *                            the statement, or -1
     */
    private static boolean armsCopline(List<LexerToken> tokens, int index, int previousSignificant) {
        LexerToken token = tokens.get(index);
        String previous = previousSignificant >= 0 ? tokens.get(previousSignificant).text : null;

        switch (token.type) {
            case NUMBER, STRING -> {
                return true;
            }
            case IDENTIFIER -> {
                // A method name after "->" is forced as METHCALL0 and never arms
                // the line; neither does the name in "sub NAME".
                if ("->".equals(previous) || "sub".equals(previous) || "method".equals(previous)) {
                    return false;
                }
                return !NON_ARMING_WORDS.contains(token.text);
            }
            case OPERATOR -> {
                if (!ARMING_OPERATORS.contains(token.text)) {
                    return false;
                }
                // "%", "*", "&" and the postfix increments are only terms when
                // they do not follow a completed term.
                if (previous != null
                        && ("%".equals(token.text) || "*".equals(token.text) || "&".equals(token.text))) {
                    LexerToken previousToken = tokens.get(previousSignificant);
                    if (previousToken.type == LexerTokenType.NUMBER
                            || previousToken.type == LexerTokenType.IDENTIFIER
                            || TERM_ENDERS.contains(previous)) {
                        return false;
                    }
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
