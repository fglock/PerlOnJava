package org.perlonjava.runtime;

public class RuntimeScalarRegexVariable extends RuntimeBaseProxy {

    final int position;

    public RuntimeScalarRegexVariable(int position) {
        super();
        this.position = position;
    }

    @Override
    void vivify() {
        throw new RuntimeException("Can't modify constant item $" + position);
    }

    public RuntimeScalar addToScalar(RuntimeScalar var) {
        String str = this.getStringValue();
        if (str == null) {
            var.undefine();
        } else {
            var.set(str);
        }
        return var;
    }

    public String getStringValue() {
        try {
            if (RuntimeRegex.globalMatcher == null || position > RuntimeRegex.globalMatcher.groupCount()) {
                return null;
            }
            return RuntimeRegex.globalMatcher.group(position);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    @Override
    public int getInt() {
        return new RuntimeScalar(this.getStringValue()).getInt();
    }

    @Override
    public double getDouble() {
        return new RuntimeScalar(this.getStringValue()).getDouble();
    }

    @Override
    public String toString() {
        String str = this.getStringValue();
        return str == null ? "" : str;
    }

    @Override
    public boolean getBoolean() {
        String str = this.getStringValue();
        return (str != null && !str.isEmpty() && !str.equals("0"));
    }
}
