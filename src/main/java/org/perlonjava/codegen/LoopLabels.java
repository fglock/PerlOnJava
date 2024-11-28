package org.perlonjava.codegen;

import org.objectweb.asm.Label;

public class LoopLabels {
    public String labelName;
    public Label nextLabel;
    public Label redoLabel;
    public Label lastLabel;
    public int context;
    public int asmStackLevel;

    public LoopLabels(String labelName, Label nextLabel, Label redoLabel, Label lastLabel, int asmStackLevel, int context) {
        this.labelName = labelName;
        this.nextLabel = nextLabel;
        this.redoLabel = redoLabel;
        this.lastLabel = lastLabel;
        this.asmStackLevel = asmStackLevel;
        this.context = context;
    }

    @Override
    public String toString() {
        return "LoopLabels{" +
                "labelName='" + labelName + '\'' +
                ", nextLabel=" + nextLabel +
                ", redoLabel=" + redoLabel +
                ", lastLabel=" + lastLabel +
                ", asmStackLevel=" + asmStackLevel +
                ", context=" + context +
                '}';
    }
}

