package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.PerlJavaUnimplementedException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Implementation of Perl's layered IO system for filehandles.
 *
 * <p>This class provides a simplified implementation of Perl's PerlIO layer
 * system, allowing multiple transformations to be stacked on an IO handle.
 * It wraps an underlying IOHandle and applies a pipeline of layers for
 * input and output transformations.</p>
 *
 * <p>In Perl, layered IO allows you to stack multiple transformations:</p>
 * <pre>
 * # Open with multiple layers
 * open(my $fh, '<:encoding(UTF-8):crlf', 'file.txt');
 *
 * # Change layers on existing handle
 * binmode($fh, ':raw');
 * binmode($fh, ':encoding(iso-8859-1):crlf');
 * </pre>
 *
 * <p>Key features of this implementation:</p>
 * <ul>
 *   <li>Supports stacking multiple IO layers in order</li>
 *   <li>Handles standard Perl layers: :raw, :bytes, :crlf, :utf8, :encoding(name)</li>
 *   <li>Maintains separate pipelines for input and output transformations</li>
 *   <li>Properly resets layer state when needed</li>
 * </ul>
 *
 * <p>The layer pipeline is implemented using Java's Function composition,
 * where each layer's transformation is composed with the previous ones
 * to create an efficient processing pipeline.</p>
 *
 * @see IOHandle
 * @see IOLayer
 */
public class LayeredIOHandle implements IOHandle {
    /**
     * List of currently active layers.
     * Maintained for proper cleanup and reset operations.
     */
    public final List<IOLayer> activeLayers = new ArrayList<>();
    /**
     * The underlying IO handle that performs actual read/write operations.
     * This could be a file handle, socket handle, or any other IOHandle implementation.
     */
    private final IOHandle delegate;
    /**
     * The composed function pipeline for input transformations.
     * Each layer's processInput method is composed into this pipeline
     * in the order they were applied.
     */
    private Function<String, String> inputPipeline = Function.identity();
    /**
     * The composed function pipeline for output transformations.
     * Each layer's processOutput method is composed into this pipeline
     * in the order they were applied.
     */
    private Function<String, String> outputPipeline = Function.identity();

    /**
     * Buffer for decoded characters that were produced by the encoding layer
     * but not yet consumed by doRead(). This prevents character loss when
     * the encoding layer decodes more characters than the caller requested
     * (e.g., reading 4 bytes of UTF-16BE gives 2 characters when only 1 was needed).
     */
    private StringBuilder decodedCharBuffer = new StringBuilder();

    /**
     * Constructs a new layered IO handle wrapping the given delegate.
     *
     * <p>Initially, no layers are applied, so all operations pass through
     * to the delegate unchanged. Layers can be added using the binmode() method.</p>
     *
     * @param delegate the underlying IO handle to wrap
     * @throws NullPointerException if delegate is null
     */
    public LayeredIOHandle(IOHandle delegate) {
        this.delegate = delegate;
    }

    /**
     * Returns the underlying delegate handle.
     *
     * <p>This can be useful for accessing handle-specific functionality
     * or for debugging purposes.</p>
     *
     * @return the wrapped IO handle
     */
    public IOHandle getDelegate() {
        return delegate;
    }

    /**
     * Writes data to the handle, applying all output layers.
     *
     * <p>The data passes through the output pipeline in the order layers
     * were added, transforming the data before it reaches the underlying
     * handle. For example, with :encoding(UTF-8):crlf layers:</p>
     * <ol>
     *   <li>The encoding layer converts characters to UTF-8 bytes</li>
     *   <li>The crlf layer converts LF to CRLF</li>
     *   <li>The transformed data is written to the delegate</li>
     * </ol>
     *
     * @param data the string data to write
     * @return a RuntimeScalar indicating success (1) or failure (0)
     */
    @Override
    public RuntimeScalar write(String data) {
        // Apply output pipeline
        String processed = outputPipeline.apply(data);
        return delegate.write(processed);
    }

    /**
     * Reads data from the handle, applying all input layers.
     *
     * <p>This method implements a sophisticated reading strategy that handles
     * character encoding boundaries properly. It may read multiple chunks from
     * the delegate to ensure complete character sequences are returned.</p>
     *
     * <p>The data passes through the input pipeline in the order layers
     * were added. For example, with :crlf:encoding(UTF-8) layers:</p>
     * <ol>
     *   <li>Raw bytes are read from the delegate</li>
     *   <li>The crlf layer converts CRLF to LF</li>
     *   <li>The encoding layer decodes UTF-8 bytes to characters</li>
     * </ol>
     *
     * <p>The method handles cases where multi-byte sequences are split
     * across read boundaries by reading additional data when necessary.</p>
     *
     * @param maxBytes the maximum number of bytes to read
     * @param charset  the character set (currently unused, layers handle encoding)
     * @return a RuntimeScalar containing the read data
     */
    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        // If no active layers, delegate directly (byte-based reading)
        if (activeLayers.isEmpty()) {
            return delegate.doRead(maxBytes, charset);
        }

        // For encoding layers, use precise character-based reading
        StringBuilder result = new StringBuilder();
        int charactersNeeded = maxBytes;
        boolean hasEncoding = hasEncodingLayer();

        // First, drain any previously buffered decoded characters
        if (decodedCharBuffer.length() > 0) {
            int charsFromBuffer = Math.min(decodedCharBuffer.length(), charactersNeeded);
            result.append(decodedCharBuffer, 0, charsFromBuffer);
            decodedCharBuffer.delete(0, charsFromBuffer);
            charactersNeeded -= charsFromBuffer;
        }

        // Safety limit must be generous for multi-byte encodings (e.g., UTF-32 = 4 bytes/char)
        int safetyLimit = Math.max(maxBytes * 8, 64); // Prevent infinite loops

        while (charactersNeeded > 0 && safetyLimit > 0) {
            // For encoding layers (UTF-16, UTF-32), read extra bytes to ensure we decode
            // at least enough characters. For non-encoding layers (e.g., :crlf), read
            // conservatively to avoid over-consuming from the delegate (which would make
            // tell() inaccurate since it reports the delegate's position).
            int bytesToRead;
            if (hasEncoding) {
                bytesToRead = Math.min(128, Math.max(4, charactersNeeded * 4));
            } else {
                bytesToRead = Math.min(128, charactersNeeded);
            }
            RuntimeScalar chunk = delegate.doRead(bytesToRead, charset);
            String chunkStr = chunk.toString();

            if (chunkStr.isEmpty()) {
                break; // EOF reached
            }

            safetyLimit -= chunkStr.length();

            // Apply input pipeline to transform bytes to characters
            String processed = inputPipeline.apply(chunkStr);

            // Add the processed characters to the result
            if (!processed.isEmpty()) {
                int charsToTake = Math.min(processed.length(), charactersNeeded);
                result.append(processed, 0, charsToTake);
                charactersNeeded -= charsToTake;

                // Buffer any excess decoded characters for the next doRead() call
                if (processed.length() > charsToTake) {
                    decodedCharBuffer.append(processed, charsToTake, processed.length());
                    break;
                }
            }
        }

        return new RuntimeScalar(result.toString());
    }

    /**
     * Sets or changes the IO layers on this handle, similar to Perl's binmode().
     *
     * <p>This method parses a Perl-style layer specification string and applies
     * the requested layers to the handle. Any existing layers are removed and
     * reset before applying the new ones.</p>
     *
     * <p>Supported layer specifications:</p>
     * <ul>
     *   <li><b>:raw</b> or <b>:bytes</b> - Binary mode, no transformations</li>
     *   <li><b>:crlf</b> - CRLF/LF conversion layer</li>
     *   <li><b>:utf8</b> - UTF-8 encoding layer</li>
     *   <li><b>:encoding(name)</b> - Specific character encoding layer</li>
     * </ul>
     *
     * <p>Examples:</p>
     * <pre>
     * handle.binmode(":raw");                    // Binary mode
     * handle.binmode(":utf8");                   // UTF-8 text mode
     * handle.binmode(":encoding(UTF-16):crlf");  // UTF-16 with CRLF conversion
     * </pre>
     *
     * @param modeStr the layer specification string (may be null or empty for raw mode)
     * @return RuntimeScalar(1) on success, RuntimeScalar(0) on failure
     */
    public RuntimeScalar binmode(String modeStr) {
        try {
            // Reset all pipelines
            inputPipeline = Function.identity();
            outputPipeline = Function.identity();

            // Clear decoded character buffer (layer change invalidates buffered data)
            decodedCharBuffer.setLength(0);

            // Reset and clear existing layers
            for (IOLayer layer : activeLayers) {
                layer.reset();
            }
            activeLayers.clear();

            // Parse and apply new layers
            parseAndSetLayers(modeStr);
            return new RuntimeScalar(1);
        } catch (PerlJavaUnimplementedException e) {
            // Loud-fail for unimplemented layers (e.g. :via(Foo)).
            // Matches upstream behavior of returning false from binmode on
            // layer push failure, but also surfaces the reason via a warning
            // so users don't silently lose their layer configuration.
            org.perlonjava.runtime.operators.WarnDie.warn(
                    new RuntimeScalar(e.getMessage() + "\n"),
                    new RuntimeScalar(""));
            return new RuntimeScalar(0);
        } catch (Exception e) {
            return new RuntimeScalar(0);
        }
    }

    /**
     * Parses the layer specification string and applies the layers.
     *
     * <p>This method handles the parsing of Perl-style layer strings,
     * including proper handling of the :encoding(...) syntax.</p>
     *
     * @param modeStr the layer specification string
     * @throws IllegalArgumentException if an unknown layer is specified
     */
    private void parseAndSetLayers(String modeStr) {
        if (modeStr == null || modeStr.isEmpty()) {
            // Default to raw mode (no transformation)
            return;
        }

        String[] layers = splitLayers(modeStr);

        for (String layer : layers) {
            if (layer.isEmpty()) continue;
            addLayer(layer);
        }
    }

    /**
     * Splits a layer specification string into individual layer names.
     *
     * <p>This method handles the special case of :encoding(...) which contains
     * parentheses that should not be split. For example:</p>
     * <ul>
     *   <li>":raw:crlf" → [":raw", ":crlf"]</li>
     *   <li>":encoding(UTF-8):crlf" → [":encoding(UTF-8)", ":crlf"]</li>
     *   <li>"encoding(iso-8859-1)" → ["encoding(iso-8859-1)"]</li>
     * </ul>
     *
     * @param modeStr the layer specification string to split
     * @return array of individual layer specifications
     */
    private String[] splitLayers(String modeStr) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int i = 0;

        while (i < modeStr.length()) {
            if (modeStr.charAt(i) == ':') {
                // Found a layer separator
                if (i > start) {
                    result.add(modeStr.substring(start, i));
                }
                start = i + 1;
                i++;
            } else if (modeStr.startsWith("encoding(", i) || modeStr.startsWith("via(", i)) {
                // Handle encoding(...) / via(...) specially to preserve parentheses.
                // Without this, ":via(Foo::Bar)" would be split at the "::" inside
                // the class name because ":" is the layer separator.
                int closeIdx = modeStr.indexOf(')', i);
                if (closeIdx != -1) {
                    // Extract everything before the layer() call if any
                    if (i > start) {
                        result.add(modeStr.substring(start, i));
                    }
                    // Extract the complete layer(...) specification
                    result.add(modeStr.substring(i, closeIdx + 1));
                    i = closeIdx + 1;
                    start = i;
                    // Skip separator if present after the layer()
                    if (i < modeStr.length() && modeStr.charAt(i) == ':') {
                        start++;
                        i++;
                    }
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }

        // Add any remaining content
        if (start < modeStr.length()) {
            result.add(modeStr.substring(start));
        }

        return result.toArray(new String[0]);
    }

    /**
     * Adds a single layer to the IO handle.
     *
     * <p>This method creates the appropriate layer instance and adds it to
     * both the active layers list and the input/output pipelines.</p>
     *
     * <p>The layers are applied in order, with each new layer wrapping
     * the previous transformations. This matches Perl's layer stacking behavior.</p>
     *
     * @param layerSpec the layer specification (e.g., "crlf", "utf8", "encoding(UTF-16)")
     * @throws IllegalArgumentException if the layer specification is unknown
     */
    private void addLayer(String layerSpec) {
        switch (layerSpec) {
            case "bytes", "raw", "unix" -> {
                // No-op layers - binary mode with no transformation
                // These layers essentially remove other layers when used alone
            }
            case "crlf" -> {
                // CRLF layer for line ending conversion
                CrlfLayer layer = new CrlfLayer();
                activeLayers.add(layer);
                // Create transformation functions and compose them into pipelines
                Function<String, String> inputTransform = s -> layer.processInput(s);
                Function<String, String> outputTransform = s -> layer.processOutput(s);
                inputPipeline = inputPipeline.andThen(inputTransform);
                outputPipeline = outputPipeline.andThen(outputTransform);
            }
            case "utf8" -> {
                // UTF-8 encoding layer - convenience alias for :encoding(UTF-8)
                EncodingLayer layer = new EncodingLayer(StandardCharsets.UTF_8, "utf8");
                activeLayers.add(layer);
                Function<String, String> inputTransform = s -> layer.processInput(s);
                Function<String, String> outputTransform = s -> layer.processOutput(s);
                inputPipeline = inputPipeline.andThen(inputTransform);
                outputPipeline = outputPipeline.andThen(outputTransform);
            }
            default -> {
                // Check for :encoding(...) pattern
                if (layerSpec.startsWith("encoding(") && layerSpec.endsWith(")")) {
                    // Extract charset name from encoding(name)
                    String charsetName = layerSpec.substring(9, layerSpec.length() - 1);
                    try {
                        Charset charset = Charset.forName(charsetName);
                        EncodingLayer layer = new EncodingLayer(charset, layerSpec);
                        activeLayers.add(layer);
                        Function<String, String> inputTransform = s -> layer.processInput(s);
                        Function<String, String> outputTransform = s -> layer.processOutput(s);
                        inputPipeline = inputPipeline.andThen(inputTransform);
                        outputPipeline = outputPipeline.andThen(outputTransform);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Unknown encoding: " + charsetName);
                    }
                } else if (layerSpec.startsWith("via(") && layerSpec.endsWith(")")) {
                    // :via(Foo) invokes a Perl-implemented PerlIO layer. PerlOnJava
                    // does not yet bridge the :via(...) layer dispatch back into
                    // Perl callbacks (PUSHED / FILL / READ / WRITE / CLOSE ...).
                    // Fail loudly so users don't get a silent no-op; see
                    // dev/modules/perlio_via.md for the plan to make this
                    // functional. Under JPERL_UNIMPLEMENTED=warn this is still
                    // caught by binmode()/open() and surfaced via $!.
                    String className = layerSpec.substring(4, layerSpec.length() - 1);
                    throw new PerlJavaUnimplementedException(
                            "PerlIO layer :via(" + className + ") not implemented " +
                                    "in PerlOnJava (see dev/modules/perlio_via.md)");
                } else {
                    throw new IllegalArgumentException("Unknown layer: " + layerSpec);
                }
            }
        }
    }

    /**
     * Flushes any buffered data to the underlying handle.
     *
     * <p>This method passes through to the delegate's flush method.
     * Individual layers don't typically buffer data in this implementation,
     * so no layer-specific flushing is needed.</p>
     *
     * @return the result from the delegate's flush operation
     */
    @Override
    public RuntimeScalar flush() {
        return delegate.flush();
    }

    /**
     * Synchronizes data to physical storage (fsync).
     *
     * <p>This method passes through to the delegate's sync method.
     * Use this only when you need guaranteed disk durability.</p>
     *
     * @return the result from the delegate's sync operation
     * @see IOHandle#sync()
     */
    @Override
    public RuntimeScalar sync() {
        return delegate.sync();
    }

    /**
     * Closes the handle and cleans up all layers.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Flushes any pending data</li>
     *   <li>Resets all active layers to clear their state</li>
     *   <li>Closes the underlying delegate handle</li>
     * </ol>
     *
     * @return the result from the delegate's close operation
     */
    @Override
    public RuntimeScalar close() {
        flush();
        // Reset all layers to clear any internal state
        for (IOLayer layer : activeLayers) {
            layer.reset();
        }
        decodedCharBuffer.setLength(0);
        return delegate.close();
    }

    /**
     * Returns the file descriptor number of the underlying handle.
     *
     * <p>This method passes through to the delegate, as layers don't
     * affect the file descriptor.</p>
     *
     * @return the file descriptor number from the delegate
     */
    @Override
    public RuntimeScalar fileno() {
        return delegate.fileno();
    }

    /**
     * Checks if the end of file has been reached.
     *
     * <p>This method passes through to the delegate, as EOF detection
     * happens at the underlying IO level.</p>
     *
     * @return true if EOF has been reached, false otherwise
     */
    @Override
    public RuntimeScalar eof() {
        // If there are buffered decoded characters, we're not at EOF
        if (decodedCharBuffer.length() > 0) {
            return new RuntimeScalar(0);
        }
        return delegate.eof();
    }

    /**
     * Returns the current position in the file.
     *
     * <p>This method passes through to the delegate. Note that with
     * encoding layers, the position is in terms of bytes in the underlying
     * file, not characters.</p>
     *
     * @return the current file position from the delegate
     */
    @Override
    public RuntimeScalar tell() {
        return delegate.tell();
    }

    /**
     * Seeks to a new position in the file.
     *
     * <p>This method resets all layers before seeking, as seeking can
     * invalidate any partial character sequences or other stateful
     * information maintained by layers.</p>
     *
     * <p>This matches Perl's behavior where seeking clears layer buffers.</p>
     *
     * @param pos    the position to seek to
     * @param whence the seek mode (SEEK_SET, SEEK_CUR, or SEEK_END)
     * @return the result from the delegate's seek operation
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        // Reset all layers when seeking to clear any partial state
        for (IOLayer layer : activeLayers) {
            layer.reset();
        }
        decodedCharBuffer.setLength(0);
        return delegate.seek(pos, whence);
    }

    /**
     * Truncates the file to the specified length.
     *
     * <p>This method passes through to the delegate, as truncation
     * operates at the file level below any layer transformations.</p>
     *
     * @param length the new file length
     * @return the result from the delegate's truncate operation
     */
    @Override
    public RuntimeScalar truncate(long length) {
        return delegate.truncate(length);
    }

    /**
     * Delegates flock operations to the underlying handle.
     *
     * <p>This is necessary because handles opened with encoding layers
     * (e.g., :encoding(UTF-8)) get wrapped in a LayeredIOHandle, but
     * the flock implementation lives in the delegate (e.g., CustomFileChannel).</p>
     *
     * @param operation the flock operation (LOCK_SH, LOCK_EX, LOCK_UN, etc.)
     * @return the result from the delegate's flock operation
     */
    @Override
    public RuntimeScalar flock(int operation) {
        return delegate.flock(operation);
    }

    /**
     * Checks if this handle has any encoding layers (e.g., :utf8, :encoding(UTF-8)).
     *
     * <p>Encoding layers decode bytes into characters, which means reads should
     * produce character strings (UTF-8 flag set in Perl terms). Without encoding
     * layers, reads produce byte strings.</p>
     *
     * @return true if any active layer is an EncodingLayer
     */
    public boolean hasEncodingLayer() {
        for (IOLayer layer : activeLayers) {
            if (layer instanceof EncodingLayer) {
                return true;
            }
        }
        return false;
    }

    public String getCurrentLayers() {
        // Return the currently applied layers as a string
        StringBuilder layers = new StringBuilder();
        for (IOLayer layer : this.activeLayers) {
            if (layer instanceof CrlfLayer) {
                layers.append(":crlf");
            } else if (layer instanceof EncodingLayer) {
                // You might need to store the encoding name
                layers.append(":encoding");
            }
            // Add other layer types as needed
        }
        return layers.toString();
    }
}
