package org.perlonjava.codegen;

import org.objectweb.asm.Label;

public class GotoLabels {
    public String labelName;
    public Label gotoLabel;
    public int asmStackLevel;

    public GotoLabels(String labelName, Label gotoLabel, int asmStackLevel) {
        this.labelName = labelName;
        this.gotoLabel = gotoLabel;
        this.asmStackLevel = asmStackLevel;
    }

    @Override
    public String toString() {
        return "GotoLabels{" +
                "labelName='" + labelName + '\'' +
                ", gotoLabel=" + gotoLabel +
                ", asmStackLevel=" + asmStackLevel +
                '}';
    }
}
