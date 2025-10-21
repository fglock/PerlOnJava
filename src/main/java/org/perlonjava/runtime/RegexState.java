package org.perlonjava.runtime;

import org.perlonjava.regex.RuntimeRegex;

import java.util.regex.Matcher;

/**
 * RegexState holds a snapshot of all regex-related state.
 * Used to save and restore regex state when entering/exiting blocks with regex operations.
 * Implements DynamicState to integrate with the local variable management system.
 */
public class RegexState implements DynamicState {
    private Matcher globalMatcher;
    private String globalMatchString;
    private String lastMatchedString;
    private int lastMatchStart;
    private int lastMatchEnd;
    private String lastSuccessfulMatchedString;
    private int lastSuccessfulMatchStart;
    private int lastSuccessfulMatchEnd;
    private String lastSuccessfulMatchString;
    private RuntimeRegex lastSuccessfulPattern;

    /**
     * Creates a marker object for regex state management.
     * The actual state is saved when dynamicSaveState() is called.
     */
    public RegexState() {
        // State will be saved in dynamicSaveState()
    }

    /**
     * Saves the current regex state from RuntimeRegex static fields.
     * Called automatically when pushed onto the DynamicVariableManager stack.
     */
    @Override
    public void dynamicSaveState() {
        // Save all the static fields from RuntimeRegex
        this.globalMatcher = RuntimeRegex.globalMatcher;
        this.globalMatchString = RuntimeRegex.globalMatchString;
        this.lastMatchedString = RuntimeRegex.lastMatchedString;
        this.lastMatchStart = RuntimeRegex.lastMatchStart;
        this.lastMatchEnd = RuntimeRegex.lastMatchEnd;
        this.lastSuccessfulMatchedString = RuntimeRegex.lastSuccessfulMatchedString;
        this.lastSuccessfulMatchStart = RuntimeRegex.lastSuccessfulMatchStart;
        this.lastSuccessfulMatchEnd = RuntimeRegex.lastSuccessfulMatchEnd;
        this.lastSuccessfulMatchString = RuntimeRegex.lastSuccessfulMatchString;
        this.lastSuccessfulPattern = RuntimeRegex.lastSuccessfulPattern;
    }

    /**
     * Restores the saved regex state back to RuntimeRegex static fields.
     * Called automatically when popped from the DynamicVariableManager stack.
     */
    @Override
    public void dynamicRestoreState() {
        // Restore all the static fields to RuntimeRegex
        RuntimeRegex.globalMatcher = this.globalMatcher;
        RuntimeRegex.globalMatchString = this.globalMatchString;
        RuntimeRegex.lastMatchedString = this.lastMatchedString;
        RuntimeRegex.lastMatchStart = this.lastMatchStart;
        RuntimeRegex.lastMatchEnd = this.lastMatchEnd;
        RuntimeRegex.lastSuccessfulMatchedString = this.lastSuccessfulMatchedString;
        RuntimeRegex.lastSuccessfulMatchStart = this.lastSuccessfulMatchStart;
        RuntimeRegex.lastSuccessfulMatchEnd = this.lastSuccessfulMatchEnd;
        RuntimeRegex.lastSuccessfulMatchString = this.lastSuccessfulMatchString;
        RuntimeRegex.lastSuccessfulPattern = this.lastSuccessfulPattern;
    }
}
