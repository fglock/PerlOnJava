package org.perlonjava.runtime;

import java.util.regex.Matcher;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class RuntimeScalarRegexVariable extends RuntimeBaseProxy {

    public static Matcher matcher;

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
            if (matcher == null || position > matcher.groupCount()) {
                return null;
            }
            return matcher.group(position);
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
