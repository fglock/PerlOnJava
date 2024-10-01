package org.perlonjava.codegen;

import org.objectweb.asm.Label;

public class LoopLabels {
    public String labelName;
    public Label nextLabel;
    public Label redoLabel;
    public Label lastLabel;

    public LoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel) {
        this.labelName = labelName;
        this.nextLabel = nextLabel;
        this.redoLabel = redoLabel;
        this.lastLabel = lastLabel;
    }

    @Override
    public String toString() {
        return "LoopLabels{" +
                "labelName='" + labelName + '\'' +
                ", nextLabel=" + nextLabel +
                ", redoLabel=" + redoLabel +
                ", lastLabel=" + lastLabel +
                '}';
    }
}

