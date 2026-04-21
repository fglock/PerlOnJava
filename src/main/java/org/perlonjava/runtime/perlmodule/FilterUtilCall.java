package org.perlonjava.runtime.perlmodule;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarZero;

/**
 * Java implementation of Filter::Util::Call XS functions.
 * <p>
 * This module implements Perl source filters by intercepting the parsing/compilation
 * of source code and transforming it through user-defined filter functions.
 *
 * <h2>How Source Filters Work</h2>
 * <ol>
 *   <li>A filter is installed via filter_add() (usually in BEGIN block or import)</li>
 *   <li>When Perl reads source code, it checks for active filters</li>
 *   <li>Filters are called to process each line/block of source</li>
 *   <li>Filter reads input via filter_read(), modifies $_, returns status</li>
 *   <li>Modified source is then compiled/executed</li>
 * </ol>
 *
 * @see <a href="https://perldoc.perl.org/Filter::Util::Call">perldoc Filter::Util::Call</a>
 */
public class FilterUtilCall extends PerlModuleBase {

    /**
     * Thread-local storage for source filter context.
     * Each thread can have its own stack of active filters.
     */
    private static final ThreadLocal<FilterContext> filterContext = ThreadLocal.withInitial(FilterContext::new);

    /**
     * Thread-local flag to track if a filter was installed during the current use statement.
     * This is used by the parser to know when to rejoin/re-tokenize remaining source.
     */
    private static final ThreadLocal<Boolean> filterInstalledDuringUse = ThreadLocal.withInitial(() -> false);

    /**
     * Mark that a filter was installed during the current use statement.
     * Called by real_import() when a filter is added to the stack.
     */
    public static void markFilterInstalled() {
        filterInstalledDuringUse.set(true);
    }

    /**
     * Check if a filter was installed during the current use statement.
     * Also resets the flag after checking.
     *
     * @return true if a filter was installed, false otherwise
     */
    public static boolean wasFilterInstalled() {
        boolean result = filterInstalledDuringUse.get();
        filterInstalledDuringUse.set(false);  // Reset after checking
        return result;
    }

    /**
     * Check if there are any active filters on the stack.
     *
     * @return true if filters are active, false otherwise
     */
    public static boolean hasActiveFilters() {
        FilterContext context = filterContext.get();
        return context.filterStack.size() > 0;
    }

    /**
     * Constructor for FilterUtilCall.
     * Note: We don't set %INC here because the Perl module file needs to be loaded
     * to provide filter_add() and filter_read_exact() functions.
     */
    public FilterUtilCall() {
        super("Filter::Util::Call", false);  // Don't set %INC - let the .pm file be loaded
    }

    /**
     * Static initializer to set up the Filter::Util::Call module.
     */
    public static void initialize() {
        FilterUtilCall module = new FilterUtilCall();
        try {
            // Register XS functions
            module.registerMethod("real_import", null);
            module.registerMethod("filter_read", null);
            module.registerMethod("filter_del", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Filter::Util::Call method: " + e.getMessage());
        }
    }

    /**
     * real_import - Install a source filter.
     * <p>
     * Called by filter_add() to actually install the filter.
     *
     * @param args [0] = filter object (blessed ref or coderef)
     *             [1] = caller package name
     *             [2] = boolean: true if coderef, false if method filter
     * @param ctx  Execution context
     * @return true on success
     */
    public static RuntimeList real_import(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("real_import requires 3 arguments");
        }

        RuntimeScalar filterObj = args.get(0);
        RuntimeScalar packageName = args.get(1);
        RuntimeScalar isCodeRef = args.get(2);

        FilterContext context = filterContext.get();

        // Create a filter entry
        RuntimeArray filterEntry = new RuntimeArray();
        filterEntry.push(filterObj);       // The filter object/coderef
        filterEntry.push(packageName);     // Package name for method lookup
        filterEntry.push(isCodeRef);       // Whether it's a coderef or method filter

        // Add to the filter stack
        context.filterStack.add(new RuntimeScalar(filterEntry));

        // Mark that a filter was installed (for parser to check after import())
        markFilterInstalled();

        return scalarTrue.getList();
    }

    /**
     * filter_read - Read next chunk of source code through the filter chain.
     * <p>
     * This is called by the filter itself to get more input.
     * Returns status:
     * > 0 : OK, more data available
     * = 0 : EOF reached
     * < 0 : Error occurred
     *
     * @param args [0] = optional: block size (if present, read block; else read line)
     * @param ctx  Execution context
     * @return status code
     */
    public static RuntimeList filter_read(RuntimeArray args, int ctx) {
        FilterContext context = filterContext.get();

        // Prevent infinite recursion
        if (context.inFilterRead) {
            return scalarZero.getList(); // EOF
        }

        try {
            context.inFilterRead = true;

            // Get $_ to append data to
            RuntimeScalar defaultVar = GlobalVariable.getGlobalVariable("main::_");
            String currentContent = defaultVar.toString();

            // Determine read mode: line or block
            boolean blockMode = args.size() > 0;
            int blockSize = blockMode ? args.get(0).getInt() : -1;

            // Check if we have source lines to read from
            if (context.sourceLines == null || context.currentLine >= context.sourceLines.length) {
                // No more input
                return scalarZero.getList(); // EOF
            }

            String nextChunk;
            if (blockMode && blockSize > 0) {
                // Block mode: read up to blockSize bytes
                StringBuilder block = new StringBuilder();
                int bytesRead = 0;

                while (bytesRead < blockSize && context.currentLine < context.sourceLines.length) {
                    String line = context.sourceLines[context.currentLine];
                    if (bytesRead + line.length() <= blockSize) {
                        block.append(line);
                        bytesRead += line.length();
                        context.currentLine++;
                        // In block mode, keep reading until we hit blockSize or EOF.
                        // Do NOT stop on newlines — that's line mode.
                    } else {
                        // Partial line read
                        int remaining = blockSize - bytesRead;
                        block.append(line, 0, remaining);
                        // Update the current line to be the remainder
                        context.sourceLines[context.currentLine] = line.substring(remaining);
                        bytesRead = blockSize;
                        break;
                    }
                }
                nextChunk = block.toString();
            } else {
                // Line mode: read next line
                nextChunk = context.sourceLines[context.currentLine];
                context.currentLine++;
            }

            // Append to $_
            defaultVar.set(currentContent + nextChunk);

            // Return status > 0 (success)
            return new RuntimeScalar(1).getList();

        } finally {
            context.inFilterRead = false;
        }
    }

    /**
     * filter_del - Remove the current filter from the filter stack.
     * <p>
     * This tells Perl to stop calling this filter.
     *
     * @param args Unused
     * @param ctx  Execution context
     * @return true on success
     */
    public static RuntimeList filter_del(RuntimeArray args, int ctx) {
        FilterContext context = filterContext.get();

        // Remove the top filter from the stack
        if (context.filterStack.size() > 0) {
            context.filterStack.elements.remove(context.filterStack.size() - 1);
        }

        return scalarTrue.getList();
    }

    /**
     * Apply filters to source code before execution.
     * <p>
     * This is called internally by do/eval when filters are active.
     * This method applies all currently installed filters to the source code.
     *
     * @param sourceCode The source code to filter
     * @return The filtered source code
     */
    public static String applyFilters(String sourceCode) {
        FilterContext context = filterContext.get();

        if (context.filterStack.size() == 0) {
            // No filters active
            return sourceCode;
        }

        // Debug: show that we're filtering
        String debugEnv = System.getenv("JPERL_FILTER_DEBUG");
        boolean debug = debugEnv != null && !debugEnv.isEmpty();
        if (debug) {
            System.err.println("[FILTER] applyFilters called with " + sourceCode.length() + " chars");
            System.err.println("[FILTER] Source: " + sourceCode.substring(0, Math.min(200, sourceCode.length())));
        }

        // Set up the source for filter_read()
        context.sourceLines = sourceCode.split("(?<=\n)", -1);
        context.currentLine = 0;

        // Apply each filter in the stack (LIFO order)
        RuntimeScalar savedDefaultVar = GlobalVariable.getGlobalVariable("main::_");
        StringBuilder filteredCode = new StringBuilder();

        try {
            // Apply the first (most recent) filter
            if (context.filterStack.size() > 0) {
                RuntimeScalar filterEntryScalar = (RuntimeScalar) context.filterStack.elements.get(context.filterStack.size() - 1);
                RuntimeArray filterEntry = (RuntimeArray) filterEntryScalar.value;
                RuntimeScalar filterObj = filterEntry.get(0);
                RuntimeScalar packageName = filterEntry.get(1);
                RuntimeScalar isCodeRef = filterEntry.get(2);

                // Clear $_
                GlobalVariable.getGlobalVariable("main::_").set("");

                if (isCodeRef.getBoolean()) {
                    // Closure filter: call the coderef repeatedly
                    RuntimeCode code = (RuntimeCode) filterObj.value;
                    boolean continueFiltering = true;

                    while (continueFiltering) {
                        // Call the filter
                        RuntimeBase result = code.apply(new RuntimeArray(), RuntimeContextType.SCALAR);

                        // Get the modified $_
                        String chunk = GlobalVariable.getGlobalVariable("main::_").toString();
                        if (!chunk.isEmpty()) {
                            filteredCode.append(chunk);
                            if (debug) {
                                System.err.println("[FILTER] Got chunk: " + chunk);
                            }

                            // Check if the chunk ends with __DATA__, __END__, or "no Module;" terminator
                            // If so, stop filtering and append remaining source unchanged
                            // This is important for Filter::Simple which stops at these terminators
                            // Pattern matches:
                            // - __DATA__ or __END__ at end of line
                            // - "no ModuleName;" at start of line (with optional comment)
                            if (chunk.matches("(?sm).*^__(?:DATA|END)__\\s*$") ||
                                chunk.matches("(?sm).*^\\s*no\\s+[\\w:]+\\s*;.*$")) {
                                // Append remaining source unchanged
                                if (debug) {
                                    System.err.println("[FILTER] Hit terminator, currentLine=" + context.currentLine + 
                                        ", totalLines=" + context.sourceLines.length);
                                }
                                while (context.currentLine < context.sourceLines.length) {
                                    filteredCode.append(context.sourceLines[context.currentLine]);
                                    context.currentLine++;
                                }
                                continueFiltering = false;
                                if (debug) {
                                    System.err.println("[FILTER] Hit __DATA__/__END__ terminator, appending remaining source unchanged");
                                }
                            }
                        }

                        // Check status - convert to scalar if it's a list
                        RuntimeScalar statusScalar = result.scalar();
                        int status = statusScalar.getInt();
                        if (debug) {
                            System.err.println("[FILTER] Status: " + status);
                        }
                        if (status <= 0) {
                            continueFiltering = false;
                        }

                        // Prepare for next iteration
                        GlobalVariable.getGlobalVariable("main::_").set("");
                    }
                } else {
                    // Method filter: call the "filter" method on the blessed filter object
                    // repeatedly until it returns a non-positive status.
                    String pkg = packageName.toString();
                    RuntimeScalar method = org.perlonjava.runtime.mro.InheritanceResolver
                            .findMethodInHierarchy("filter", pkg, null, 0);
                    if (method == null || method.type != org.perlonjava.runtime.runtimetypes.RuntimeScalarType.CODE) {
                        if (debug) {
                            System.err.println("[FILTER] No filter() method found in " + pkg
                                    + "; returning source unchanged");
                        }
                        return sourceCode;
                    }
                    RuntimeCode code = (RuntimeCode) method.value;
                    boolean continueFiltering = true;
                    while (continueFiltering) {
                        // Call $obj->filter  (pass $self as first arg)
                        RuntimeArray callArgs = new RuntimeArray();
                        callArgs.push(filterObj);
                        RuntimeBase result = code.apply(callArgs, RuntimeContextType.SCALAR);

                        String chunk = GlobalVariable.getGlobalVariable("main::_").toString();
                        if (!chunk.isEmpty()) {
                            filteredCode.append(chunk);
                            if (debug) {
                                System.err.println("[FILTER] Method filter chunk: " + chunk);
                            }
                        }

                        int status = result.scalar().getInt();
                        if (debug) {
                            System.err.println("[FILTER] Method filter status: " + status);
                        }
                        if (status <= 0) {
                            continueFiltering = false;
                        }
                        GlobalVariable.getGlobalVariable("main::_").set("");
                    }
                }
            }

            if (debug) {
                System.err.println("[FILTER] Final filtered code length: " + filteredCode.length());
                System.err.println("[FILTER] Final filtered code: " + filteredCode.toString());
            }
            return filteredCode.toString();

        } finally {
            // Restore $_
            GlobalVariable.getGlobalVariable("main::_").set(savedDefaultVar.toString());

            // Clean up context
            context.sourceLines = null;
            context.currentLine = 0;
        }
    }

    /**
     * Check if source code contains BEGIN blocks that might install filters.
     * If so, execute a pre-parse pass to install the filters, then filter the remaining source.
     * <p>
     * This is a workaround for the limitation that our architecture tokenizes all source upfront,
     * while Perl's source filters need to be applied during incremental source reading.
     *
     * @param sourceCode The original source code
     * @return The filtered source code if filters were installed, otherwise the original
     */
    public static String preprocessWithBeginFilters(String sourceCode) {
        // Quick check: does the source contain "filter_add" or similar?
        if (!sourceCode.contains("filter")) {
            return sourceCode;
        }

        // Check for BEGIN blocks - simple regex check
        if (!sourceCode.matches("(?s).*BEGIN\\s*\\{.*filter.*\\}.*")) {
            return sourceCode;
        }

        // Find the END of the first BEGIN block and split the source there
        // We'll execute everything up to and including the BEGIN block,
        // then apply any installed filters to the rest

        int beginPos = sourceCode.indexOf("BEGIN");
        if (beginPos == -1) {
            return sourceCode;
        }

        // Find the matching closing brace for the BEGIN block
        int braceStart = sourceCode.indexOf('{', beginPos);
        if (braceStart == -1) {
            return sourceCode;
        }

        int braceCount = 1;
        int pos = braceStart + 1;
        while (pos < sourceCode.length() && braceCount > 0) {
            char c = sourceCode.charAt(pos);
            if (c == '{') braceCount++;
            else if (c == '}') braceCount--;
            pos++;
        }

        if (braceCount != 0) {
            // Couldn't find matching brace
            return sourceCode;
        }

        // Split at the end of the BEGIN block
        String beginPart = sourceCode.substring(0, pos);
        String remainingPart = sourceCode.substring(pos);

        // Execute the BEGIN part to install any filters
        try {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<filter-install>";
            options.code = beginPart;
            PerlLanguageProvider.executePerlCode(options, false);
        } catch (Exception e) {
            // If execution fails, just return original source
            return sourceCode;
        }

        // Now apply any installed filters to the remaining source
        String filteredRemaining = applyFilters(remainingPart);

        // Return the BEGIN part + filtered remaining part
        return beginPart + filteredRemaining;
    }

    /**
     * Clear all filters for the current thread.
     * Used when starting a new do/eval.
     */
    public static void clearFilters() {
        FilterContext context = filterContext.get();
        context.filterStack = new RuntimeList();
        context.sourceLines = null;
        context.currentLine = 0;
    }

    /**
     * Context for managing active source filters.
     */
    static class FilterContext {
        // Stack of active filters (LIFO - last added is first applied)
        RuntimeList filterStack = new RuntimeList();

        // Current input source being filtered (set during do/eval)
        String[] sourceLines = null;
        int currentLine = 0;

        // Whether we're currently inside a filter_read() call
        boolean inFilterRead = false;
    }
}

