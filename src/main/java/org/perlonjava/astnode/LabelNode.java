package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;

public class LabelNode extends AbstractNode {
    public final String label;

    public LabelNode(String label, int tokenIndex) {
        this.tokenIndex = tokenIndex;
        this.label = label;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
