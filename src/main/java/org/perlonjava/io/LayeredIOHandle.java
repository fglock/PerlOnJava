package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Simplified layered I/O handle implementation.
 */
public class LayeredIOHandle implements IOHandle {
    private final IOHandle delegate;
    private Function<String, String> inputPipeline = Function.identity();
    private Function<String, String> outputPipeline = Function.identity();
    private final List<IOLayer> activeLayers = new ArrayList<>();

    public LayeredIOHandle(IOHandle delegate) {
        this.delegate = delegate;
    }

    public IOHandle getDelegate() {
        return delegate;
    }

    @Override
    public RuntimeScalar write(String data) {
        // Apply output pipeline
        String processed = outputPipeline.apply(data);
        return delegate.write(processed);
    }

    @Override
    public RuntimeScalar read(int maxBytes, Charset charset) {
        StringBuilder result = new StringBuilder();
        int bytesRead = 0;

        // Keep reading until we have some complete characters or EOF
        while (bytesRead < maxBytes) {
            // Read from delegate
            RuntimeScalar chunk = delegate.read(Math.min(maxBytes - bytesRead, 128), charset);
            if (chunk.toString().isEmpty()) {
                break; // EOF
            }

            // Apply input pipeline
            String processed = inputPipeline.apply(chunk.toString());

            // If we got something, add it to result
            if (!processed.isEmpty()) {
                result.append(processed);
                bytesRead += processed.length();
            } else if (result.length() == 0) {
                // We got nothing and have no previous data
                // This means we have incomplete sequence - read more
                continue;
            }

            // If we have some result, return it
            if (result.length() > 0) {
                break;
            }
        }

        return new RuntimeScalar(result.toString());
    }


    public RuntimeScalar binmode(String modeStr) {
        try {
            // Reset all pipelines
            inputPipeline = Function.identity();
            outputPipeline = Function.identity();

            // Reset and clear existing layers
            for (IOLayer layer : activeLayers) {
                layer.reset();
            }
            activeLayers.clear();

            // Parse and apply new layers
            parseAndSetLayers(modeStr);
            return new RuntimeScalar(1);
        } catch (Exception e) {
            return new RuntimeScalar(0);
        }
    }

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

    private String[] splitLayers(String modeStr) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int i = 0;

        while (i < modeStr.length()) {
            if (modeStr.charAt(i) == ':') {
                if (i > start) {
                    result.add(modeStr.substring(start, i));
                }
                start = i + 1;
                i++;
            } else if (modeStr.startsWith("encoding(", i)) {
                // Handle encoding(...) specially
                int closeIdx = modeStr.indexOf(')', i);
                if (closeIdx != -1) {
                    if (i > start) {
                        result.add(modeStr.substring(start, i));
                    }
                    result.add(modeStr.substring(i, closeIdx + 1));
                    i = closeIdx + 1;
                    start = i;
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

        if (start < modeStr.length()) {
            result.add(modeStr.substring(start));
        }

        return result.toArray(new String[0]);
    }

    private void addLayer(String layerSpec) {
        switch (layerSpec) {
            case "bytes", "raw", "unix" -> {
                // No-op - identity function
            }
            case "crlf" -> {
                CrlfLayer layer = new CrlfLayer();
                activeLayers.add(layer);
                Function<String, String> inputTransform = s -> layer.processInput(s);
                Function<String, String> outputTransform = s -> layer.processOutput(s);
                inputPipeline = inputPipeline.andThen(inputTransform);
                outputPipeline = outputPipeline.andThen(outputTransform);
            }
            case "utf8" -> {
                EncodingLayer layer = new EncodingLayer(StandardCharsets.UTF_8);
                activeLayers.add(layer);
                Function<String, String> inputTransform = s -> layer.processInput(s);
                Function<String, String> outputTransform = s -> layer.processOutput(s);
                inputPipeline = inputPipeline.andThen(inputTransform);
                outputPipeline = outputPipeline.andThen(outputTransform);
            }
            default -> {
                if (layerSpec.startsWith("encoding(") && layerSpec.endsWith(")")) {
                    String charsetName = layerSpec.substring(9, layerSpec.length() - 1);
                    try {
                        Charset charset = Charset.forName(charsetName);
                        EncodingLayer layer = new EncodingLayer(charset);
                        activeLayers.add(layer);
                        Function<String, String> inputTransform = s -> layer.processInput(s);
                        Function<String, String> outputTransform = s -> layer.processOutput(s);
                        inputPipeline = inputPipeline.andThen(inputTransform);
                        outputPipeline = outputPipeline.andThen(outputTransform);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Unknown encoding: " + charsetName);
                    }
                } else {
                    throw new IllegalArgumentException("Unknown layer: " + layerSpec);
                }
            }
        }
    }

    @Override
    public RuntimeScalar flush() {
        return delegate.flush();
    }

    @Override
    public RuntimeScalar close() {
        flush();
        for (IOLayer layer : activeLayers) {
            layer.reset();
        }
        return delegate.close();
    }

    @Override
    public RuntimeScalar fileno() {
        return delegate.fileno();
    }

    @Override
    public RuntimeScalar eof() {
        return delegate.eof();
    }

    @Override
    public RuntimeScalar tell() {
        return delegate.tell();
    }

    @Override
    public RuntimeScalar seek(long pos, int whence) {
        for (IOLayer layer : activeLayers) {
            layer.reset();
        }
        return delegate.seek(pos, whence);
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return delegate.truncate(length);
    }
}
