package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.Node;

/** Parses a registered lexical keyword at a statement boundary. */
@FunctionalInterface
public interface ParserKeywordPlugin {
    Node parse(Parser parser, String keyword);
}
