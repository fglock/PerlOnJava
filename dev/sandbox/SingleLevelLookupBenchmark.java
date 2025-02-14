import java.util.HashMap;
import java.util.Map;

public class SingleLevelLookupBenchmark {

    public static void main(String[] args) {
        Map<String, String> singleLevelMap = new HashMap<>();
        
        // Populate the map
        for (int i = 0; i < 1000; i++) {
            String packageName = "package" + i;
            for (int j = 0; j < 100; j++) {
                String variableName = "var" + j;
                String key = packageName + "::" + variableName;
                singleLevelMap.put(key, "value" + i + "_" + j);
            }
        }

        // Benchmark the lookup
        long start = System.nanoTime();
        for (int i = 0; i < 1000000; i++) {
            String packageName = "package" + (i % 1000);
            for (int j = 0; j < 100; j++) {
                String variableName = "var" + (j % 100);
                String key = packageName + "::" + variableName;
                singleLevelMap.get(key); // lookup
            }
        }
        long end = System.nanoTime();
        System.out.println("Single-level lookup time: " + (end - start) + " ns");
    }
}

