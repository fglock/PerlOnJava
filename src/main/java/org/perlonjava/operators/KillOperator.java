package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.awt.image.renderable.RenderableImage;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

/**
 * Implementation of Perl's kill operator for PerlOnJava
 */
public class KillOperator {
    
    // Signal name to number mappings (POSIX standard)
    private static final Map<String, Integer> SIGNAL_MAP = new HashMap<>();
    static {
        SIGNAL_MAP.put("HUP", 1);
        SIGNAL_MAP.put("SIGHUP", 1);
        SIGNAL_MAP.put("INT", 2);
        SIGNAL_MAP.put("SIGINT", 2);
        SIGNAL_MAP.put("QUIT", 3);
        SIGNAL_MAP.put("SIGQUIT", 3);
        SIGNAL_MAP.put("KILL", 9);
        SIGNAL_MAP.put("SIGKILL", 9);
        SIGNAL_MAP.put("TERM", 15);
        SIGNAL_MAP.put("SIGTERM", 15);
        SIGNAL_MAP.put("USR1", 10);
        SIGNAL_MAP.put("SIGUSR1", 10);
        SIGNAL_MAP.put("USR2", 12);
        SIGNAL_MAP.put("SIGUSR2", 12);
        SIGNAL_MAP.put("ZERO", 0);
        SIGNAL_MAP.put("SIGZERO", 0);
    }
    
    /**
     * Implements Perl's kill operator
     * @param args RuntimeList containing signal and process IDs
     * @return RuntimeScalar with count of successfully signaled processes
     */
    public static RuntimeScalar kill(RuntimeBase... args) {
        if (args.length == 0) {
            return new RuntimeScalar(0);
        }
        
        String signal = args[0].toString();
        int successCount = 0;
        
        // Parse signal (name or number)
        int signalNum = parseSignal(signal);
        boolean isProcessGroup = signalNum < 0;
        if (isProcessGroup) {
            signalNum = Math.abs(signalNum);
        }
        
        // Process each PID in the list
        for (RuntimeBase elem : args) {
            for (RuntimeScalar scalar : elem) {
                long pid = scalar.getInt();
                if (sendSignal(signalNum, pid, isProcessGroup)) {
                    successCount++;
                }
            }
        }
        
        return new RuntimeScalar(successCount);
    }
    
    private static int parseSignal(String signal) {
        try {
            // Try parsing as number first
            return Integer.parseInt(signal);
        } catch (NumberFormatException e) {
            // Try parsing as signal name
            String upperSignal = signal.toUpperCase();
            Integer signalNum = SIGNAL_MAP.get(upperSignal);
            if (signalNum != null) {
                return signalNum;
            }
            
            // Handle negative signal names (process groups)
            if (upperSignal.startsWith("-")) {
                String positiveSignal = upperSignal.substring(1);
                Integer posSignalNum = SIGNAL_MAP.get(positiveSignal);
                if (posSignalNum != null) {
                    return -posSignalNum;
                }
            }
            
            throw new IllegalArgumentException("Unknown signal: " + signal);
        }
    }
    
    private static boolean sendSignal(int signalNum, long pid, boolean isProcessGroup) {
        if (SystemUtils.osIsWindows()) {
            return sendSignalWindows(signalNum, pid);
        } else {
            return sendSignalUnix(signalNum, pid, isProcessGroup);
        }
    }
    
    private static boolean sendSignalWindows(int signalNum, long pid) {
        try {
            RuntimeList cmdArgs = new RuntimeList();
            
            if (signalNum == 0) {
                // Signal 0 check - verify process exists using tasklist
                cmdArgs.elements.add(new RuntimeScalar("tasklist"));
                cmdArgs.elements.add(new RuntimeScalar("/FI"));
                cmdArgs.elements.add(new RuntimeScalar("PID eq " + pid));
            } else if (signalNum == 9 || signalNum == 15) { // KILL or TERM
                cmdArgs.elements.add(new RuntimeScalar("taskkill"));
                cmdArgs.elements.add(new RuntimeScalar("/F"));
                cmdArgs.elements.add(new RuntimeScalar("/PID"));
                cmdArgs.elements.add(new RuntimeScalar(String.valueOf(pid)));
            } else if (signalNum == 2) { // INT
                cmdArgs.elements.add(new RuntimeScalar("taskkill"));
                cmdArgs.elements.add(new RuntimeScalar("/PID"));
                cmdArgs.elements.add(new RuntimeScalar(String.valueOf(pid)));
            } else {
                // Other signals not supported on Windows
                return false;
            }
            
            RuntimeScalar exitCode = SystemOperator.system(cmdArgs, false, RuntimeContextType.SCALAR);
            return exitCode.getInt() == 0;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean sendSignalUnix(int signalNum, long pid, boolean isProcessGroup) {
        try {
            RuntimeList cmdArgs = new RuntimeList();
            cmdArgs.elements.add(new RuntimeScalar("kill"));
            
            if (signalNum == 0) {
                cmdArgs.elements.add(new RuntimeScalar("-0"));
            } else {
                cmdArgs.elements.add(new RuntimeScalar("-" + signalNum));
            }
            
            if (isProcessGroup) {
                cmdArgs.elements.add(new RuntimeScalar("-" + pid));
            } else {
                cmdArgs.elements.add(new RuntimeScalar(String.valueOf(pid)));
            }
            
            RuntimeScalar exitCode = SystemOperator.system(cmdArgs, false, RuntimeContextType.SCALAR);
            return exitCode.getInt() == 0;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get list of supported signal names (similar to Perl's $Config{sig_name})
     */
    public static List<String> getSupportedSignals() {
        List<String> signals = new ArrayList<>();
        
        if (SystemUtils.osIsWindows()) {
            // Windows has very limited signal support
            signals.addAll(Arrays.asList("ZERO", "INT", "KILL", "TERM"));
        } else {
            // Unix-like systems support standard POSIX signals
            signals.addAll(SIGNAL_MAP.keySet());
        }
        
        return signals;
    }
}

