package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects context usage data from the emitter to generate ContextResolver rules.
 * 
 * Data is dumped to files in a directory, then consolidated to generate ContextResolver.
 */
public class ContextCollector {
    
    // Enable by default for data collection, disable after
    private static boolean enabled = true;
    private static final String OUTPUT_DIR = System.getProperty("contextDir", "build/context-data");
    private static final AtomicLong fileCounter = new AtomicLong(0);
    
    static {
        // Ensure output directory exists and register shutdown hook
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
        } catch (IOException e) {
            System.err.println("Warning: Cannot create context data dir: " + e.getMessage());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (enabled) {
                dumpAndClear();
                consolidateAndGenerate();
            }
        }));
    }
    
    // Key: "ParentType(op)|childField|ChildType(op)" Value: context counts
    private static final Map<String, Map<Integer, Integer>> contextCounts = new ConcurrentHashMap<>();
    
    public static void enable() {
        enabled = true;
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
        } catch (IOException e) {
            System.err.println("Failed to create context output dir: " + e.getMessage());
        }
    }
    
    public static boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Record a parent visiting a child with a specific context.
     */
    public static void recordVisit(Node parent, String childField, Node child, int context) {
        if (!enabled || child == null) return;
        
        String key = nodeKey(parent) + "|" + childField + "|" + nodeKey(child);
        contextCounts.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .merge(context, 1, Integer::sum);
    }
    
    /**
     * Record visiting a node directly (when context comes from outer visitor).
     */
    public static void recordNode(Node node, int context) {
        if (!enabled || node == null) return;
        
        String key = "ROOT|root|" + nodeKey(node);
        contextCounts.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .merge(context, 1, Integer::sum);
    }
    
    private static String nodeKey(Node node) {
        if (node == null) return "NULL";
        if (node instanceof OperatorNode op) {
            return "Op:" + op.operator;
        } else if (node instanceof BinaryOperatorNode bin) {
            return "BinOp:" + bin.operator;
        } else {
            return node.getClass().getSimpleName();
        }
    }
    
    /**
     * Dump current data to a file and clear.
     */
    public static void dumpAndClear() {
        if (!enabled || contextCounts.isEmpty()) return;
        
        String filename = OUTPUT_DIR + "/ctx_" + fileCounter.incrementAndGet() + "_" + 
                Thread.currentThread().getId() + ".txt";
        
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            for (Map.Entry<String, Map<Integer, Integer>> entry : contextCounts.entrySet()) {
                String key = entry.getKey();
                for (Map.Entry<Integer, Integer> ctxEntry : entry.getValue().entrySet()) {
                    // Format: key|context|count
                    pw.println(key + "|" + ctxEntry.getKey() + "|" + ctxEntry.getValue());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to dump context data: " + e.getMessage());
        }
        
        contextCounts.clear();
    }
    
    /**
     * Consolidate all data files and generate ContextResolver rules.
     * Call this after all tests complete.
     */
    public static void consolidateAndGenerate() {
        Map<String, Map<Integer, Integer>> allData = new HashMap<>();
        
        try {
            Path dir = Paths.get(OUTPUT_DIR);
            if (!Files.exists(dir)) {
                System.err.println("No context data directory found");
                return;
            }
            
            // Read all data files (only ctx_*.txt files)
            Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("ctx_") && p.toString().endsWith(".txt"))
                    .forEach(path -> {
                        try {
                            Files.lines(path).forEach(line -> {
                                try {
                                    String[] parts = line.split("\\|");
                                    if (parts.length >= 5) {
                                        String key = parts[0] + "|" + parts[1] + "|" + parts[2];
                                        int context = Integer.parseInt(parts[3].trim());
                                        int count = Integer.parseInt(parts[4].trim());
                                        allData.computeIfAbsent(key, k -> new HashMap<>())
                                                .merge(context, count, Integer::sum);
                                    }
                                } catch (NumberFormatException e) {
                                    // Skip malformed lines
                                }
                            });
                        } catch (IOException e) {
                            System.err.println("Error reading " + path + ": " + e.getMessage());
                        }
                    });
            
            // Generate output
            generateRulesFile(allData);
            
        } catch (IOException e) {
            System.err.println("Consolidation failed: " + e.getMessage());
        }
    }
    
    private static void generateRulesFile(Map<String, Map<Integer, Integer>> data) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(OUTPUT_DIR + "/context_rules.txt"))) {
            pw.println("# Context rules extracted from emitter");
            pw.println("# Format: parent|childField|child -> context (count), ... [dominant]");
            pw.println();
            
            List<String> keys = new ArrayList<>(data.keySet());
            Collections.sort(keys);
            
            for (String key : keys) {
                Map<Integer, Integer> counts = data.get(key);
                
                int maxCount = 0;
                int dominant = RuntimeContextType.SCALAR;
                StringBuilder sb = new StringBuilder();
                
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    if (e.getValue() > maxCount) {
                        maxCount = e.getValue();
                        dominant = e.getKey();
                    }
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(contextName(e.getKey())).append("(").append(e.getValue()).append(")");
                }
                
                String rule = counts.size() == 1 ? "FIXED" : "VARIES";
                pw.println(key.replace("|", " -> ") + " : " + sb + " [" + rule + ":" + contextName(dominant) + "]");
            }
        }
        System.err.println("Rules written to: " + OUTPUT_DIR + "/context_rules.txt");
    }
    
    private static String contextName(int ctx) {
        return switch (ctx) {
            case RuntimeContextType.SCALAR -> "SCALAR";
            case RuntimeContextType.LIST -> "LIST";
            case RuntimeContextType.VOID -> "VOID";
            case RuntimeContextType.RUNTIME -> "RUNTIME";
            default -> "CTX" + ctx;
        };
    }
}
