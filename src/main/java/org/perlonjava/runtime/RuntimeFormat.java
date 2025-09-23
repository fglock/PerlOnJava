package org.perlonjava.runtime;

import java.util.Iterator;

import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Represents a runtime format in Perl. Formats are used with the write() function
 * to produce formatted output. This class provides methods to manipulate and interact
 * with formats in the runtime environment.
 */
public class RuntimeFormat extends RuntimeScalar implements RuntimeScalarReference {

    // The name of the format
    public String formatName;
    
    // The format template string
    public String formatTemplate;
    
    // Whether this format is defined
    private boolean isDefined;

    /**
     * Constructor for RuntimeFormat.
     * Initializes a new instance of the RuntimeFormat class with the specified format name.
     *
     * @param formatName The name of the format.
     */
    public RuntimeFormat(String formatName) {
        this.formatName = formatName;
        this.formatTemplate = "";
        this.isDefined = false;
        // Initialize the RuntimeScalar fields
        this.type = RuntimeScalarType.FORMAT;
        this.value = this;
    }

    /**
     * Constructor for RuntimeFormat with template.
     * Initializes a new instance of the RuntimeFormat class with the specified format name and template.
     *
     * @param formatName The name of the format.
     * @param formatTemplate The format template string.
     */
    public RuntimeFormat(String formatName, String formatTemplate) {
        this.formatName = formatName;
        this.formatTemplate = formatTemplate;
        this.isDefined = true;
        // Initialize the RuntimeScalar fields
        this.type = RuntimeScalarType.FORMAT;
        this.value = this;
    }

    /**
     * Sets the format template.
     *
     * @param template The format template string.
     * @return This RuntimeFormat instance.
     */
    public RuntimeFormat setTemplate(String template) {
        this.formatTemplate = template;
        this.isDefined = true;
        return this;
    }

    /**
     * Gets the format template.
     *
     * @return The format template string.
     */
    public String getTemplate() {
        return this.formatTemplate;
    }

    /**
     * Checks if the format is defined.
     *
     * @return true if the format is defined, false otherwise.
     */
    public boolean isFormatDefined() {
        return this.isDefined;
    }

    /**
     * Undefines the format.
     */
    public void undefineFormat() {
        this.formatTemplate = "";
        this.isDefined = false;
    }

    /**
     * Counts the number of elements in the format.
     *
     * @return The number of elements, which is always 1 for a format.
     */
    public int countElements() {
        return 1;
    }

    /**
     * Returns a string representation of the format.
     *
     * @return A string in the format "FORMAT(formatName)".
     */
    public String toString() {
        return "FORMAT(" + this.formatName + ")";
    }

    /**
     * Returns a string representation of the format reference.
     * The format is "FORMAT(hashCode)" where hashCode is the unique identifier for this instance.
     *
     * @return A string representation of the format reference.
     */
    public String toStringRef() {
        String ref = "FORMAT(0x" + this.hashCode() + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns an integer representation of the format reference.
     * This is the hash code of the current instance.
     *
     * @return The hash code of this instance.
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the format reference.
     * This is the hash code of the current instance, cast to a double.
     *
     * @return The hash code of this instance as a double.
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the format reference.
     * This always returns true, indicating the presence of the format.
     *
     * @return Always true.
     */
    public boolean getBooleanRef() {
        return true;
    }

    /**
     * Returns a boolean indicating whether the format is defined.
     *
     * @return true if the format is defined, false otherwise.
     */
    public boolean getDefinedBoolean() {
        return this.isDefined;
    }

    /**
     * Gets the scalar value of the format.
     *
     * @return A RuntimeScalar representing the format.
     */
    public RuntimeScalar scalar() {
        return this;
    }

    /**
     * Retrieves the boolean value of the format.
     *
     * @return true if the format is defined, false otherwise.
     */
    public boolean getBoolean() {
        return this.isDefined;
    }

    /**
     * Creates a reference to the format.
     *
     * @return A RuntimeScalar representing the reference to the format.
     */
    public RuntimeScalar createReference() {
        RuntimeScalar ret = new RuntimeScalar();
        ret.type = RuntimeScalarType.REFERENCE;
        ret.value = this;
        return ret;
    }

    /**
     * Gets the list value of the format.
     *
     * @return A RuntimeList containing the scalar representation of the format.
     */
    public RuntimeList getList() {
        return new RuntimeList(this.scalar());
    }

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar to which this format will be added.
     * @return The updated RuntimeScalar.
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    /**
     * Sets itself from a RuntimeList.
     *
     * @param value The RuntimeList from which this format will be set.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setFromList(RuntimeList value) {
        return new RuntimeArray(this.set(value.scalar()));
    }

    /**
     * Returns an iterator over the elements of type RuntimeScalar.
     *
     * @return An Iterator<RuntimeScalar> for iterating over the elements.
     */
    public Iterator<RuntimeScalar> iterator() {
        return super.iterator();
    }

    /**
     * Gets the Format alias into an Array.
     *
     * @param arr The RuntimeArray to which the alias will be added.
     * @return The updated RuntimeArray.
     */
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(this.scalar());
        return arr;
    }

    /**
     * Saves the current state of the format.
     */
    @Override
    public void dynamicSaveState() {
        // Format state is immutable once created, no need to save state
    }

    /**
     * Restores the most recently saved state of the format.
     */
    @Override
    public void dynamicRestoreState() {
        // Format state is immutable once created, no need to restore state
    }
}
