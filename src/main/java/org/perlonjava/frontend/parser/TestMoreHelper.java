package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.BlockNode;

public class TestMoreHelper {

    // Test::More SKIP blocks use native labeled-block control flow; the
    // parser must preserve the labeled block without rewriting its AST.
    static void handleSkipTest(Parser parser, BlockNode block) {
    }
}
