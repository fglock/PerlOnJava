package org.perlonjava.runtime;

import org.perlonjava.regex.RuntimeRegex;

import java.util.regex.Matcher;

/**
 * RegexState holds a snapshot of all regex-related state.
 * Used to save and restore regex state when entering/exiting eval blocks.
 */
public class RegexState {
    public final Matcher globalMatcher;
    public final String globalMatchString;
    public final String lastMatchedString;
    public final int lastMatchStart;
    public final int lastMatchEnd;
    public final String lastSuccessfulMatchedString;
    public final int lastSuccessfulMatchStart;
    public final int lastSuccessfulMatchEnd;
    public final String lastSuccessfulMatchString;
    public final RuntimeRegex lastSuccessfulPattern;

    public RegexState() {
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

    public void restore() {
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
