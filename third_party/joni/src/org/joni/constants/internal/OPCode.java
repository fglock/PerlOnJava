/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni.constants.internal;

import org.joni.Config;

public interface OPCode {
    int FINISH                        = 0;            /* matching process terminator (no more alternative) */
    int END                           = 1;            /* pattern code terminator (success end) */

    int EXACT1                        = 2;            /* single byte, N = 1 */
    int EXACT2                        = 3;            /* single byte, N = 2 */
    int EXACT3                        = 4;            /* single byte, N = 3 */
    int EXACT4                        = 5;            /* single byte, N = 4 */
    int EXACT5                        = 6;            /* single byte, N = 5 */
    int EXACTN                        = 7;            /* single byte */
    int EXACTMB2N1                    = 8;            /* mb-length = 2 N = 1 */
    int EXACTMB2N2                    = 9;            /* mb-length = 2 N = 2 */
    int EXACTMB2N3                    = 10;           /* mb-length = 2 N = 3 */
    int EXACTMB2N                     = 11;           /* mb-length = 2 */
    int EXACTMB3N                     = 12;           /* mb-length = 3 */
    int EXACTMBN                      = 13;           /* other length */

    int EXACT1_IC                     = 14;           /* single byte, N = 1, ignore case */
    int EXACTN_IC                     = 15;           /* single byte,        ignore case */

    int CCLASS                        = 16;
    int CCLASS_MB                     = 17;
    int CCLASS_MIX                    = 18;
    int CCLASS_NOT                    = 19;
    int CCLASS_MB_NOT                 = 20;
    int CCLASS_MIX_NOT                = 21;

    int ANYCHAR                       = 22;           /* "."  */
    int ANYCHAR_ML                    = 23;           /* "."  multi-line */
    int ANYCHAR_STAR                  = 24;           /* ".*" */
    int ANYCHAR_ML_STAR               = 25;           /* ".*" multi-line */
    int ANYCHAR_STAR_PEEK_NEXT        = 26;
    int ANYCHAR_ML_STAR_PEEK_NEXT     = 27;

    int WORD                          = 28;
    int NOT_WORD                      = 29;
    int WORD_BOUND                    = 30;
    int NOT_WORD_BOUND                = 31;
    int WORD_BEGIN                    = 32;
    int WORD_END                      = 33;

    int ASCII_WORD                    = 34;
    int ASCII_NOT_WORD                = 35;
    int ASCII_WORD_BOUND              = 36;
    int ASCII_NOT_WORD_BOUND          = 37;
    int ASCII_WORD_BEGIN              = 38;
    int ASCII_WORD_END                = 39;

    int BEGIN_BUF                     = 40;
    int END_BUF                       = 41;
    int BEGIN_LINE                    = 42;
    int END_LINE                      = 43;
    int SEMI_END_BUF                  = 44;
    int BEGIN_POSITION                = 45;

    int BACKREF1                      = 46;
    int BACKREF2                      = 47;
    int BACKREFN                      = 48;
    int BACKREFN_IC                   = 49;
    int BACKREF_MULTI                 = 50;
    int BACKREF_MULTI_IC              = 51;
    int BACKREF_WITH_LEVEL            = 52;           /* \k<xxx+n>, \k<xxx-n> */

    int MEMORY_START                  = 53;
    int MEMORY_START_PUSH             = 54;           /* push back-tracker to stack */
    int MEMORY_END_PUSH               = 55;           /* push back-tracker to stack */
    int MEMORY_END_PUSH_REC           = 56;           /* push back-tracker to stack */
    int MEMORY_END                    = 57;
    int MEMORY_END_REC                = 58;           /* push marker to stack */

    int KEEP                          = 59;
    int FAIL                          = 60;           /* pop stack and move */
    int JUMP                          = 61;
    int PUSH                          = 62;
    int POP                           = 63;
    int PUSH_OR_JUMP_EXACT1           = 64;           /* if match exact then push, else jump. */
    int PUSH_IF_PEEK_NEXT             = 65;           /* if match exact then push, else none. */

    int REPEAT                        = 66;           /* {n,m} */
    int REPEAT_NG                     = 67;           /* {n,m}? (non greedy) */
    int REPEAT_INC                    = 68;
    int REPEAT_INC_NG                 = 69;           /* non greedy */
    int REPEAT_INC_SG                 = 70;           /* search and get in stack */
    int REPEAT_INC_NG_SG              = 71;           /* search and get in stack (non greedy) */

    int NULL_CHECK_START              = 72;           /* null loop checker start */
    int NULL_CHECK_END                = 73;           /* null loop checker end   */
    int NULL_CHECK_END_MEMST          = 74;           /* null loop checker end (with capture status) */
    int NULL_CHECK_END_MEMST_PUSH     = 75;           /* with capture status and push check-end */

    int PUSH_POS                      = 76;           /* (?=...)  start */
    int POP_POS                       = 77;           /* (?=...)  end   */
    int PUSH_POS_NOT                  = 78;           /* (?!...)  start */
    int FAIL_POS                      = 79;           /* (?!...)  end   */
    int PUSH_STOP_BT                  = 80;           /* (?>...)  start */
    int POP_STOP_BT                   = 81;           /* (?>...)  end   */
    int LOOK_BEHIND                   = 82;           /* (?<=...) start (no needs end opcode) */
    int PUSH_LOOK_BEHIND_NOT          = 83;           /* (?<!...) start */
    int FAIL_LOOK_BEHIND_NOT          = 84;           /* (?<!...) end   */

    int PUSH_ABSENT_POS               = 85;           /* (?~...)  start */
    int ABSENT                        = 86;           /* (?~...)  start of inner loop */
    int ABSENT_END                    = 87;           /* (?~...)  end   */

    int CALL                          = 88;           /* \g<name> */
    int RETURN                        = 89;
    int CONDITION                     = 90;

    int STATE_CHECK_PUSH              = 91;           /* combination explosion check and push */
    int STATE_CHECK_PUSH_OR_JUMP      = 92;           /* check ok -> push, else jump  */
    int STATE_CHECK                   = 93;           /* check only */
    int STATE_CHECK_ANYCHAR_STAR      = 94;
    int STATE_CHECK_ANYCHAR_ML_STAR   = 95;

      /* no need: IS_DYNAMIC_OPTION() == 0 */
    int SET_OPTION_PUSH               = 96;           /* set option and push recover option */
    int SET_OPTION                    = 97;           /* set option */

    int EXACT1_IC_SB                  = 98;           /* single byte, N = 1, ignore case */
    int EXACTN_IC_SB                  = 99;           /* single byte,        ignore case */
    int CALLOUT                       = 100;          /* generic match-time callout */
    int CALLOUT_CONDITION             = 101;          /* callback-selected conditional */
    int DYNAMIC_CALLOUT               = 102;          /* callback-supplied nested program */
    int ACCEPT                        = 103;          /* accept current matcher boundary */
    int PRUNE                         = 104;          /* discard alternatives at this boundary */
    int SKIP                          = 105;          /* prune and resume search at current position */
    int THEN                          = 106;          /* prune through the nearest alternative */
    int COMMIT                        = 107;          /* prune and prevent another search start */
    int MARK                          = 108;          /* record a named Perl backtracking mark */
    int POP_POS_NOT                   = 109;          /* successful conditional assertion end */
    int RECURSION_CONDITION           = 110;          /* active subpattern call conditional */
    int CHECK_POS_END                 = 111;          /* require current position at saved positive boundary */
    int CHECK_LOOK_BEHIND_END         = 112;          /* require current position at saved negative boundary */
    int GRAPHEME_BOUNDARY             = 113;          /* Perl \b{gcb} */
    int NOT_GRAPHEME_BOUNDARY         = 114;          /* Perl \B{gcb} */
    int SENTENCE_BOUNDARY             = 115;          /* Perl \b{sb} */
    int NOT_SENTENCE_BOUNDARY         = 116;          /* Perl \B{sb} */
    int WORD_BREAK_BOUNDARY           = 117;          /* Perl \b{wb} */
    int NOT_WORD_BREAK_BOUNDARY       = 118;          /* Perl \B{wb} */
    int LINE_BOUNDARY                 = 119;          /* Perl \b{lb} */
    int NOT_LINE_BOUNDARY             = 120;          /* Perl \B{lb} */
    int PHYSICAL_NAMED_CAPTURE_START  = 121;
    int PHYSICAL_NAMED_CAPTURE_END    = 122;
    int WIDE_SCALAR                   = 123;
    int WIDE_SCALAR_CLASS             = 124;

    String[] OpCodeNames = Config.DEBUG_COMPILE ? new String[] {
        "finish", /*OP_FINISH*/
        "end", /*OP_END*/
        "exact1", /*OP_EXACT1*/
        "exact2", /*OP_EXACT2*/
        "exact3", /*OP_EXACT3*/
        "exact4", /*OP_EXACT4*/
        "exact5", /*OP_EXACT5*/
        "exactn", /*OP_EXACTN*/
        "exactmb2-n1", /*OP_EXACTMB2N1*/
        "exactmb2-n2", /*OP_EXACTMB2N2*/
        "exactmb2-n3", /*OP_EXACTMB2N3*/
        "exactmb2-n", /*OP_EXACTMB2N*/
        "exactmb3n", /*OP_EXACTMB3N*/
        "exactmbn", /*OP_EXACTMBN*/
        "exact1-ic", /*OP_EXACT1_IC*/
        "exactn-ic", /*OP_EXACTN_IC*/
        "cclass", /*OP_CCLASS*/
        "cclass-mb", /*OP_CCLASS_MB*/
        "cclass-mix", /*OP_CCLASS_MIX*/
        "cclass-not", /*OP_CCLASS_NOT*/
        "cclass-mb-not", /*OP_CCLASS_MB_NOT*/
        "cclass-mix-not", /*OP_CCLASS_MIX_NOT*/
        "anychar", /*OP_ANYCHAR*/
        "anychar-ml", /*OP_ANYCHAR_ML*/
        "anychar*", /*OP_ANYCHAR_STAR*/
        "anychar-ml*", /*OP_ANYCHAR_ML_STAR*/
        "anychar*-peek-next", /*OP_ANYCHAR_STAR_PEEK_NEXT*/
        "anychar-ml*-peek-next", /*OP_ANYCHAR_ML_STAR_PEEK_NEXT*/
        "word", /*OP_WORD*/
        "not-word", /*OP_NOT_WORD*/
        "word-bound", /*OP_WORD_BOUND*/
        "not-word-bound", /*OP_NOT_WORD_BOUND*/
        "word-begin", /*OP_WORD_BEGIN*/
        "word-end", /*OP_WORD_END*/
        "ascii-word", /*OP_ASCII_WORD*/
        "not-ascii-word", /*OP_NOT_ASCII_WORD*/
        "ascii-word-bound", /*OP_ASCII_WORD_BOUND*/
        "not-ascii-word-bound", /*OP_NOT_ASCII_WORD_BOUND*/
        "ascii-word-begin", /*OP_ASCII_WORD_BEGIN*/
        "ascii-word-end", /*OP_ASCII_WORD_END*/
        "begin-buf", /*OP_BEGIN_BUF*/
        "end-buf", /*OP_END_BUF*/
        "begin-line", /*OP_BEGIN_LINE*/
        "end-line", /*OP_END_LINE*/
        "semi-end-buf", /*OP_SEMI_END_BUF*/
        "begin-position", /*OP_BEGIN_POSITION*/
        "backref1", /*OP_BACKREF1*/
        "backref2", /*OP_BACKREF2*/
        "backrefn", /*OP_BACKREFN*/
        "backrefn-ic", /*OP_BACKREFN_IC*/
        "backref_multi", /*OP_BACKREF_MULTI*/
        "backref_multi-ic", /*OP_BACKREF_MULTI_IC*/
        "backref_at_level", /*OP_BACKREF_AT_LEVEL*/
        "mem-start", /*OP_MEMORY_START*/
        "mem-start-push", /*OP_MEMORY_START_PUSH*/
        "mem-end-push", /*OP_MEMORY_END_PUSH*/
        "mem-end-push-rec", /*OP_MEMORY_END_PUSH_REC*/
        "mem-end", /*OP_MEMORY_END*/
        "mem-end-rec", /*OP_MEMORY_END_REC*/
        "keep", /*OP_KEEP*/
        "fail", /*OP_FAIL*/
        "jump", /*OP_JUMP*/
        "push", /*OP_PUSH*/
        "pop", /*OP_POP*/
        "push-or-jump-e1", /*OP_PUSH_OR_JUMP_EXACT1*/
        "push-if-peek-next", /*OP_PUSH_IF_PEEK_NEXT*/
        "repeat", /*OP_REPEAT*/
        "repeat-ng", /*OP_REPEAT_NG*/
        "repeat-inc", /*OP_REPEAT_INC*/
        "repeat-inc-ng", /*OP_REPEAT_INC_NG*/
        "repeat-inc-sg", /*OP_REPEAT_INC_SG*/
        "repeat-inc-ng-sg", /*OP_REPEAT_INC_NG_SG*/
        "null-check-start", /*OP_NULL_CHECK_START*/
        "null-check-end", /*OP_NULL_CHECK_END*/
        "null-check-end-memst", /*OP_NULL_CHECK_END_MEMST*/
        "null-check-end-memst-push", /*OP_NULL_CHECK_END_MEMST_PUSH*/
        "push-pos", /*OP_PUSH_POS*/
        "pop-pos", /*OP_POP_POS*/
        "push-pos-not", /*OP_PUSH_POS_NOT*/
        "fail-pos", /*OP_FAIL_POS*/
        "push-stop-bt", /*OP_PUSH_STOP_BT*/
        "pop-stop-bt", /*OP_POP_STOP_BT*/
        "look-behind", /*OP_LOOK_BEHIND*/
        "push-look-behind-not", /*OP_PUSH_LOOK_BEHIND_NOT*/
        "fail-look-behind-not", /*OP_FAIL_LOOK_BEHIND_NOT*/
        "push-absent-pos", /*OP_PUSH_ABSENT_POS*/
        "absent", /*OP_ABSENT*/
        "absent-end", /*OP_ABSENT_END*/
        "call", /*OP_CALL*/
        "return", /*OP_RETURN*/
        "condition", /*OP_CONDITION*/
        "state-check-push", /*OP_STATE_CHECK_PUSH*/
        "state-check-push-or-jump", /*OP_STATE_CHECK_PUSH_OR_JUMP*/
        "state-check", /*OP_STATE_CHECK*/
        "state-check-anychar*", /*OP_STATE_CHECK_ANYCHAR_STAR*/
        "state-check-anychar-ml*", /*OP_STATE_CHECK_ANYCHAR_ML_STAR*/
        "set-option-push", /*OP_SET_OPTION_PUSH*/
        "set-option", /*OP_SET_OPTION*/

        "exact1-ic-sb", /*OP_EXACT1_IC*/
        "exactn-ic-sb", /*OP_EXACTN_IC*/
        "callout", /*OP_CALLOUT*/
        "callout-condition", /*OP_CALLOUT_CONDITION*/
        "dynamic-callout", /*OP_DYNAMIC_CALLOUT*/
        "accept", /*OP_ACCEPT*/
        "prune", /*OP_PRUNE*/
        "skip", /*OP_SKIP*/
        "then", /*OP_THEN*/
        "commit", /*OP_COMMIT*/
        "mark", /*OP_MARK*/
        "pop-pos-not", /*OP_POP_POS_NOT*/
        "recursion-condition", /*OP_RECURSION_CONDITION*/
        "check-pos-end", /*OP_CHECK_POS_END*/
        "check-look-behind-end", /*OP_CHECK_LOOK_BEHIND_END*/
        "grapheme-boundary", /*OP_GRAPHEME_BOUNDARY*/
        "not-grapheme-boundary", /*OP_NOT_GRAPHEME_BOUNDARY*/
        "sentence-boundary", /*OP_SENTENCE_BOUNDARY*/
        "not-sentence-boundary", /*OP_NOT_SENTENCE_BOUNDARY*/
        "word-break-boundary", /*OP_WORD_BREAK_BOUNDARY*/
        "not-word-break-boundary", /*OP_NOT_WORD_BREAK_BOUNDARY*/
        "line-boundary", /*OP_LINE_BOUNDARY*/
        "not-line-boundary", /*OP_NOT_LINE_BOUNDARY*/
        "physical-named-capture-start",
        "physical-named-capture-end",
        "wide-scalar",
        "wide-scalar-class",
    } : null;

    int[] OpCodeArgTypes = Config.DEBUG_COMPILE ? new int[] {
        Arguments.NON, /*OP_FINISH*/
        Arguments.NON, /*OP_END*/
        Arguments.SPECIAL, /*OP_EXACT1*/
        Arguments.SPECIAL, /*OP_EXACT2*/
        Arguments.SPECIAL, /*OP_EXACT3*/
        Arguments.SPECIAL, /*OP_EXACT4*/
        Arguments.SPECIAL, /*OP_EXACT5*/
        Arguments.SPECIAL, /*OP_EXACTN*/
        Arguments.SPECIAL, /*OP_EXACTMB2N1*/
        Arguments.SPECIAL, /*OP_EXACTMB2N2*/
        Arguments.SPECIAL, /*OP_EXACTMB2N3*/
        Arguments.SPECIAL, /*OP_EXACTMB2N*/
        Arguments.SPECIAL, /*OP_EXACTMB3N*/
        Arguments.SPECIAL, /*OP_EXACTMBN*/
        Arguments.SPECIAL, /*OP_EXACT1_IC*/
        Arguments.SPECIAL, /*OP_EXACTN_IC*/
        Arguments.SPECIAL, /*OP_CCLASS*/
        Arguments.SPECIAL, /*OP_CCLASS_MB*/
        Arguments.SPECIAL, /*OP_CCLASS_MIX*/
        Arguments.SPECIAL, /*OP_CCLASS_NOT*/
        Arguments.SPECIAL, /*OP_CCLASS_MB_NOT*/
        Arguments.SPECIAL, /*OP_CCLASS_MIX_NOT*/
        Arguments.NON, /*OP_ANYCHAR*/
        Arguments.NON, /*OP_ANYCHAR_ML*/
        Arguments.NON, /*OP_ANYCHAR_STAR*/
        Arguments.NON, /*OP_ANYCHAR_ML_STAR*/
        Arguments.SPECIAL, /*OP_ANYCHAR_STAR_PEEK_NEXT*/
        Arguments.SPECIAL, /*OP_ANYCHAR_ML_STAR_PEEK_NEXT*/
        Arguments.NON, /*OP_WORD*/
        Arguments.NON, /*OP_NOT_WORD*/
        Arguments.NON, /*OP_WORD_BOUND*/
        Arguments.NON, /*OP_NOT_WORD_BOUND*/
        Arguments.NON, /*OP_WORD_BEGIN*/
        Arguments.NON, /*OP_WORD_END*/
        Arguments.NON, /*OP_ASCII_WORD*/
        Arguments.NON, /*OP_NOT_ASCII_WORD*/
        Arguments.NON, /*OP_ASCII_WORD_BOUND*/
        Arguments.NON, /*OP_NOT_ASCII_WORD_BOUND*/
        Arguments.NON, /*OP_ASCII_WORD_BEGIN*/
        Arguments.NON, /*OP_ASCII_WORD_END*/
        Arguments.NON, /*OP_BEGIN_BUF*/
        Arguments.NON, /*OP_END_BUF*/
        Arguments.NON, /*OP_BEGIN_LINE*/
        Arguments.NON, /*OP_END_LINE*/
        Arguments.NON, /*OP_SEMI_END_BUF*/
        Arguments.NON, /*OP_BEGIN_POSITION*/
        Arguments.NON, /*OP_BACKREF1*/
        Arguments.NON, /*OP_BACKREF2*/
        Arguments.MEMNUM, /*OP_BACKREFN*/
        Arguments.SPECIAL, /*OP_BACKREFN_IC*/
        Arguments.SPECIAL, /*OP_BACKREF_MULTI*/
        Arguments.SPECIAL, /*OP_BACKREF_MULTI_IC*/
        Arguments.SPECIAL, /*OP_BACKREF_AT_LEVEL*/
        Arguments.MEMNUM, /*OP_MEMORY_START*/
        Arguments.MEMNUM, /*OP_MEMORY_START_PUSH*/
        Arguments.MEMNUM, /*OP_MEMORY_END_PUSH*/
        Arguments.MEMNUM, /*OP_MEMORY_END_PUSH_REC*/
        Arguments.MEMNUM, /*OP_MEMORY_END*/
        Arguments.MEMNUM, /*OP_MEMORY_END_REC*/
        Arguments.NON, /*OP_KEEP*/
        Arguments.NON, /*OP_FAIL*/
        Arguments.RELADDR, /*OP_JUMP*/
        Arguments.RELADDR, /*OP_PUSH*/
        Arguments.NON, /*OP_POP*/
        Arguments.SPECIAL, /*OP_PUSH_OR_JUMP_EXACT1*/
        Arguments.SPECIAL, /*OP_PUSH_IF_PEEK_NEXT*/
        Arguments.SPECIAL, /*OP_REPEAT*/
        Arguments.SPECIAL, /*OP_REPEAT_NG*/
        Arguments.MEMNUM, /*OP_REPEAT_INC*/
        Arguments.MEMNUM, /*OP_REPEAT_INC_NG*/
        Arguments.MEMNUM, /*OP_REPEAT_INC_SG*/
        Arguments.MEMNUM, /*OP_REPEAT_INC_NG_SG*/
        Arguments.MEMNUM, /*OP_NULL_CHECK_START*/
        Arguments.MEMNUM, /*OP_NULL_CHECK_END*/
        Arguments.MEMNUM, /*OP_NULL_CHECK_END_MEMST*/
        Arguments.MEMNUM, /*OP_NULL_CHECK_END_MEMST_PUSH*/
        Arguments.NON, /*OP_PUSH_POS*/
        Arguments.NON, /*OP_POP_POS*/
        Arguments.RELADDR, /*OP_PUSH_POS_NOT*/
        Arguments.NON, /*OP_FAIL_POS*/
        Arguments.NON, /*OP_PUSH_STOP_BT*/
        Arguments.NON, /*OP_POP_STOP_BT*/
        Arguments.SPECIAL, /*OP_LOOK_BEHIND*/
        Arguments.SPECIAL, /*OP_PUSH_LOOK_BEHIND_NOT*/
        Arguments.NON, /*OP_FAIL_LOOK_BEHIND_NOT*/
        Arguments.NON, /*OP_PUSH_ABSENT_POS*/
        Arguments.RELADDR, /*OP_ABSENT*/
        Arguments.NON, /*OP_ABSENT_END*/
        Arguments.SPECIAL, /*OP_CALL*/
        Arguments.NON, /*OP_RETURN*/
        Arguments.SPECIAL, /*OP_CONDITION*/
        Arguments.SPECIAL, /*OP_STATE_CHECK_PUSH*/
        Arguments.SPECIAL, /*OP_STATE_CHECK_PUSH_OR_JUMP*/
        Arguments.STATE_CHECK, /*OP_STATE_CHECK*/
        Arguments.STATE_CHECK, /*OP_STATE_CHECK_ANYCHAR_STAR*/
        Arguments.STATE_CHECK, /*OP_STATE_CHECK_ANYCHAR_ML_STAR*/
        Arguments.OPTION, /*OP_SET_OPTION_PUSH*/
        Arguments.OPTION, /*OP_SET_OPTION*/

        Arguments.SPECIAL, /*OP_EXACT1_IC*/
        Arguments.SPECIAL, /*OP_EXACTN_IC*/
        Arguments.MEMNUM, /*OP_CALLOUT*/
        Arguments.SPECIAL, /*OP_CALLOUT_CONDITION*/
        Arguments.MEMNUM, /*OP_DYNAMIC_CALLOUT*/
        Arguments.NON, /*OP_ACCEPT*/
        Arguments.MEMNUM, /*OP_PRUNE*/
        Arguments.MEMNUM, /*OP_SKIP*/
        Arguments.MEMNUM, /*OP_THEN*/
        Arguments.MEMNUM, /*OP_COMMIT*/
        Arguments.MEMNUM, /*OP_MARK*/
        Arguments.NON, /*OP_POP_POS_NOT*/
        Arguments.SPECIAL, /*OP_RECURSION_CONDITION*/
        Arguments.NON, /*OP_CHECK_POS_END*/
        Arguments.NON, /*OP_CHECK_LOOK_BEHIND_END*/
        Arguments.NON, /*OP_GRAPHEME_BOUNDARY*/
        Arguments.NON, /*OP_NOT_GRAPHEME_BOUNDARY*/
        Arguments.NON, /*OP_SENTENCE_BOUNDARY*/
        Arguments.NON, /*OP_NOT_SENTENCE_BOUNDARY*/
        Arguments.NON, /*OP_WORD_BREAK_BOUNDARY*/
        Arguments.NON, /*OP_NOT_WORD_BREAK_BOUNDARY*/
        Arguments.NON, /*OP_LINE_BOUNDARY*/
        Arguments.NON, /*OP_NOT_LINE_BOUNDARY*/
        Arguments.MEMNUM, /*OP_PHYSICAL_NAMED_CAPTURE_START*/
        Arguments.MEMNUM, /*OP_PHYSICAL_NAMED_CAPTURE_END*/
        Arguments.SPECIAL, /*OP_WIDE_SCALAR*/
        Arguments.MEMNUM, /*OP_WIDE_SCALAR_CLASS*/
    } : null;
}
