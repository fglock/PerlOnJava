package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeScalar;

public class SubroutineParser {
    /**
     * Parses a subroutine call.
     *
     * @param parser
     * @return A Node representing the parsed subroutine call.
     */
    static Node parseSubroutineCall(Parser parser) {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be a v-string like v10.20.30   XXX TODO

        String subName = IdentifierParser.parseSubroutineIdentifier(parser);
        parser.ctx.logDebug("SubroutineCall subName `" + subName + "` package " + parser.ctx.symbolTable.getCurrentPackage());
        if (subName == null) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        // Normalize the subroutine name to include the current package
        String fullName = GlobalContext.normalizeVariableName(subName, parser.ctx.symbolTable.getCurrentPackage());

        // Create an identifier node for the subroutine name
        IdentifierNode nameNode = new IdentifierNode(subName, parser.tokenIndex);

        // Check if the subroutine exists in the global namespace
        boolean subExists = GlobalContext.existsGlobalCodeRef(fullName);
        String prototype = null;
        if (subExists) {
            // Fetch the subroutine reference
            RuntimeScalar codeRef = GlobalContext.getGlobalCodeRef(fullName);
            prototype = ((RuntimeCode) codeRef.value).prototype;
        }
        parser.ctx.logDebug("SubroutineCall exists " + subExists + " prototype `" + prototype + "`");

        // Check if the subroutine call has parentheses
        boolean hasParentheses = parser.peek().text.equals("(");
        if (!subExists && !hasParentheses) {
            // If the subroutine does not exist and there are no parentheses, it is not a subroutine call
            return nameNode;
        }

        // Handle the parameter list for the subroutine call
        Node arguments;
        if (prototype == null) {
            // no prototype
            arguments = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        } else if (prototype.isEmpty()) {
            // prototype is empty string
            arguments = new ListNode(parser.tokenIndex);
        } else if (prototype.equals("$")) {
            // prototype is `$`
            arguments = ListParser.parseZeroOrOneList(parser, 1);
        } else if (prototype.equals(";$")) {
            // prototype is `;$`
            arguments = ListParser.parseZeroOrOneList(parser, 0);
        } else {
            // XXX TODO: Handle more prototypes or parameter variables
            arguments = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        }

        // Rewrite and return the subroutine call as `&name(arguments)`
        return new BinaryOperatorNode("(",
                new OperatorNode("&", nameNode, nameNode.tokenIndex),
                arguments,
                parser.tokenIndex);
    }
}
