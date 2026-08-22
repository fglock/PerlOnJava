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
package org.joni;

import java.util.EnumSet;

import org.jcodings.Encoding;
import org.joni.ast.EncloseNode;
import org.joni.ast.Node;
import org.joni.constants.SyntaxProperties;
import org.joni.exception.ErrorMessages;
import org.joni.exception.InternalException;

public final class ScanEnvironment {
    public int option;
    final int caseFoldFlag;
    public final Encoding enc;
    public final Syntax syntax;
    int captureHistory;
    int btMemStart;
    int btMemEnd;
    int backrefedMem;

    public final WarnCallback warnings;

    int numCall;
    UnsetAddrList unsetAddrList; // USE_SUBEXP_CALL
    public int numMem;
    boolean[] multiplexMemNodes;

    int numNamed; // USE_NAMED_GROUP

    public EncloseNode[] memNodes;
    private EncloseNode[] physicalNamedMemNodes;

    // USE_COMBINATION_EXPLOSION_CHECK
    int numCombExpCheck;
    int combExpMaxRegNum;
    int currMaxRegNum;
    boolean hasRecursion;
    boolean hasControlVerb;
    boolean hasCallout;
    boolean hasOptimizationBlockingCallout;
    boolean inPerlExtendedClass;
    private final EnumSet<Regex.ParsedProgramFeature> parsedProgramFeatures =
            EnumSet.noneOf(Regex.ParsedProgramFeature.class);
    private int warningsFlag;

    int numPrecReadNotNodes;
    Node[] precReadNotNodes;

    ScanEnvironment(Regex regex, Syntax syntax, WarnCallback warnings) {
        this.syntax = syntax;
        this.warnings = warnings;
        option = regex.options;
        if (Option.isPerlLocale(option)) {
            markParsedProgramFeature(Regex.ParsedProgramFeature.LOCALE_CHARSET);
        }
        caseFoldFlag = regex.caseFoldFlag;
        enc = regex.enc;
    }

    void markParsedProgramFeature(Regex.ParsedProgramFeature feature) {
        parsedProgramFeatures.add(feature);
    }

    Regex.ParsedProgramMetadata parsedProgramMetadata() {
        return Regex.ParsedProgramMetadata.copyOf(parsedProgramFeatures);
    }

    int caseFoldFlagFor(int option) {
        // Character classes need the complete fold relation so
        // ApplyCaseFold can retain safe non-ASCII siblings under /aa while
        // filtering relations that cross into ASCII.
        return caseFoldFlag;
    }

    int addMemEntry() {
        if (numMem >= Config.MAX_CAPTURE_GROUP_NUM) throw new InternalException(ErrorMessages.TOO_MANY_CAPTURE_GROUPS);
        if (numMem++ == 0) {
            // Branch-reset parsing can rewind numMem without starting a new
            // parse. Keep nodes recorded by earlier alternatives.
            if (memNodes == null) {
                memNodes = new EncloseNode[Config.SCANENV_MEMNODES_SIZE];
                multiplexMemNodes = new boolean[Config.SCANENV_MEMNODES_SIZE];
            }
        } else if (numMem >= memNodes.length) {
            EncloseNode[]tmp = new EncloseNode[memNodes.length << 1];
            System.arraycopy(memNodes, 0, tmp, 0, memNodes.length);
            memNodes = tmp;
            boolean[] multiplexTmp = new boolean[multiplexMemNodes.length << 1];
            System.arraycopy(multiplexMemNodes, 0, multiplexTmp, 0, multiplexMemNodes.length);
            multiplexMemNodes = multiplexTmp;
        }

        return numMem;
    }

    void setMemNode(int num, EncloseNode node) {
        if (numMem >= num) {
            // Branch-reset alternatives reuse capture numbers. Subexpression
            // calls target the leftmost physical group with that number.
            if (memNodes[num] == null) {
                memNodes[num] = node;
            } else if (memNodes[num] != node) {
                multiplexMemNodes[num] = true;
            }
            if (node.physicalNamedCaptureId > 0) {
                setPhysicalNamedMemNode(node.physicalNamedCaptureId, node);
            }
        } else {
            throw new InternalException(ErrorMessages.PARSER_BUG);
        }
    }

    private void setPhysicalNamedMemNode(int physicalId, EncloseNode node) {
        if (physicalNamedMemNodes == null) {
            physicalNamedMemNodes = new EncloseNode[Config.SCANENV_MEMNODES_SIZE];
        } else if (physicalId >= physicalNamedMemNodes.length) {
            EncloseNode[] expanded = new EncloseNode[physicalNamedMemNodes.length << 1];
            System.arraycopy(physicalNamedMemNodes, 0, expanded, 0,
                    physicalNamedMemNodes.length);
            physicalNamedMemNodes = expanded;
        }
        physicalNamedMemNodes[physicalId] = node;
    }

    EncloseNode physicalNamedMemNode(int physicalId) {
        return physicalNamedMemNodes == null || physicalId <= 0
                || physicalId >= physicalNamedMemNodes.length
                ? null : physicalNamedMemNodes[physicalId];
    }

    boolean isMultiplexMemNode(int num) {
        return multiplexMemNodes != null && multiplexMemNodes[num];
    }


    void pushPrecReadNotNode(Node node) {
        numPrecReadNotNodes++;
        if (precReadNotNodes == null) {
            precReadNotNodes = new Node[Config.SCANENV_MEMNODES_SIZE];
        } else if (numPrecReadNotNodes >= precReadNotNodes.length) {
            Node[]tmp = new Node[precReadNotNodes.length << 1];
            System.arraycopy(precReadNotNodes, 0, tmp, 0, precReadNotNodes.length);
            precReadNotNodes = tmp;
        }
        precReadNotNodes[numPrecReadNotNodes - 1] = node;
    }

    void popPrecReadNotNode(Node node) {
        if (precReadNotNodes != null && precReadNotNodes[numPrecReadNotNodes - 1] == node) {
            precReadNotNodes[numPrecReadNotNodes - 1] = null;
            numPrecReadNotNodes--;
        }
    }

    Node currentPrecReadNotNode() {
        if (numPrecReadNotNodes > 0) {
            return precReadNotNodes[numPrecReadNotNodes - 1];
        }
        return null;
    }

    int convertBackslashValue(int c) {
        if (syntax.opEscControlChars()) {
            switch (c) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case 'f': return '\f';
            case 'a': return '\007';
            case 'b': return '\010';
            case 'e': return '\033';
            case 'v':
                if (syntax.op2EscVVtab()) return 11; // '\v'
                break;
            default:
                if (('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z')) unknownEscWarn(String.valueOf((char)c));
            }
        }
        return c;
    }

    void ccEscWarn(String s) {
        if (warnings != WarnCallback.NONE) {
            // Perl accepts a leading ']' as a literal character in a class
            // without warning.  Ruby/Oniguruma warns for this spelling, but
            // PerlNG must preserve Perl's warning policy as well as its parse.
            if (syntax.op2OptionPerl() && "]".equals(s)) return;
            if (syntax.warnCCOpNotEscaped() && syntax.backSlashEscapeInCC()) {
                warnings.warn("character class has '" + s + "' without escape");
            }
        }
    }

    void unknownEscWarn(String s) {
        if (warnings != WarnCallback.NONE) {
            warnings.warn("Unknown escape \\" + s + " is ignored");
        }
    }

    public String emptyRangeError() {
        return usesPerlDiagnostics()
                ? ErrorMessages.PERL_INVALID_RANGE_IN_CHAR_CLASS
                : ErrorMessages.EMPTY_RANGE_IN_CHAR_CLASS;
    }

    public boolean usesPerlDiagnostics() {
        return "PerlNG".equals(syntax.name) || "PERLONJAVA".equals(syntax.name);
    }

    void closeBracketWithoutEscapeWarn(String s) {
        if (warnings != WarnCallback.NONE) {
            // A closing bracket outside a character class is an ordinary
            // literal in Perl (except at the start, which the lexer already
            // permits separately) and does not produce a regexp warning.
            if (syntax.op2OptionPerl() && "]".equals(s)) return;
            if (syntax.warnCCOpNotEscaped()) {
                warnings.warn("regular expression has '" + s + "' without escape");
            }
        }
    }

    void ccDuplicateWarn() {
        if (syntax.warnCCDup() && (warningsFlag & SyntaxProperties.WARN_CC_DUP) == 0) {
            warningsFlag |= SyntaxProperties.WARN_CC_DUP;
            // FIXME: #34 points out problem and what it will take to uncomment this (we were getting erroneous versions of this)
            // warnings.warn("character class has duplicated range");
        }
    }
}
