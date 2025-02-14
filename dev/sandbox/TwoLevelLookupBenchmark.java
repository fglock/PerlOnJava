import java.util.HashMap;
import java.util.Map;

public class TwoLevelLookupBenchmark {

    public static void main(String[] args) {
        Map<String, Map<String, String>> twoLevelMap = new HashMap<>();

        // Populate the map
        for (int i = 0; i < 1000; i++) {
            String packageName = "package" + i;
            Map<String, String> packageMap = new HashMap<>();
            for (int j = 0; j < 100; j++) {
                String variableName = "var" + j;
                packageMap.put(variableName, "value" + i + "_" + j);
            }
            twoLevelMap.put(packageName, packageMap);
        }

        // Benchmark the lookup
        long start = System.nanoTime();
        for (int i = 0; i < 1000000; i++) {
            String packageName = "package" + (i % 1000);
            for (int j = 0; j < 100; j++) {
                String variableName = "var" + (j % 100);
                Map<String, String> packageMap = twoLevelMap.get(packageName);
                if (packageMap != null) {
                    packageMap.get(variableName); // lookup
                }
            }
        }
        long end = System.nanoTime();
        System.out.println("Two-level lookup time: " + (end - start) + " ns");
    }
}

