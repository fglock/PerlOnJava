// Worker Node Architecture for PerlOnJava + Spark

// 1. Each Spark worker must include PerlOnJava JAR in classpath
// spark.jars = "/path/to/perlonJava.jar,/path/to/spark-perl-adapter.jar"

// 2. Serialized Perl closure structure
public class SerializedPerlClosure implements Serializable {
    private String perlSource;           // Original Perl code
    private Map<String, Object> lexicals; // Captured variables
    private List<String> requiredModules; // Required Perl modules
    private String closureId;            // Unique identifier
    
    // Metadata for reconstruction
    private String packageName;
    private List<String> imports;
}

// 3. Worker-side Perl closure executor
public class PerlClosureExecutor implements Function<Row, Row> {
    private transient PerlInterpreter interpreter;
    private SerializedPerlClosure serializedClosure;
    
    public PerlClosureExecutor(SerializedPerlClosure closure) {
        this.serializedClosure = closure;
    }
    
    @Override
    public Row call(Row input) throws Exception {
        // Lazy initialization of Perl interpreter on worker
        if (interpreter == null) {
            initializePerlInterpreter();
        }
        
        // Convert Spark Row to Perl data structure
        Object perlInput = sparkRowToPerlHash(input);
        
        // Execute the Perl closure
        Object perlResult = interpreter.call(serializedClosure.getClosureId(), perlInput);
        
        // Convert result back to Spark Row
        return perlHashToSparkRow(perlResult);
    }
    
    private void initializePerlInterpreter() {
        // Initialize PerlOnJava interpreter on this worker
        interpreter = new PerlInterpreter();
        
        // Load required modules
        for (String module : serializedClosure.getRequiredModules()) {
            interpreter.eval("use " + module + ";");
        }
        
        // Reconstruct lexical environment
        for (Map.Entry<String, Object> lexical : serializedClosure.getLexicals().entrySet()) {
            interpreter.setVariable(lexical.getKey(), lexical.getValue());
        }
        
        // Compile and register the closure
        String closureCode = serializedClosure.getPerlSource();
        interpreter.eval("sub " + serializedClosure.getClosureId() + " { " + closureCode + " }");
    }
    
    // Data conversion methods
    private Object sparkRowToPerlHash(Row row) {
        // Convert Spark Row to Perl hash reference
        // This needs to handle all Spark data types
        Map<String, Object> perlHash = new HashMap<>();
        
        for (int i = 0; i < row.schema().fields().length; i++) {
            String fieldName = row.schema().fields()[i].name();
            Object value = row.get(i);
            
            // Convert Spark types to Perl-compatible types
            perlHash.put(fieldName, convertSparkTypeToPerlType(value));
        }
        
        return interpreter.newReference(perlHash);
    }
    
    private Row perlHashToSparkRow(Object perlResult) {
        // Convert Perl result back to Spark Row
        // Handle both scalar returns and hash references
        if (perlResult instanceof Map) {
            return RowFactory.create(/* convert map to row values */);
        } else {
            // Handle scalar results
            return RowFactory.create(perlResult);
        }
    }
}

// 4. Spark application setup with PerlOnJava dependencies
public class PerlSparkApplication {
    public static void main(String[] args) {
        SparkConf conf = new SparkConf()
            .setAppName("PerlSpark Application")
            // CRITICAL: Include PerlOnJava JAR on all nodes
            .set("spark.jars", "/path/to/perlonJava.jar,/path/to/our-adapter.jar")
            // Ensure sufficient memory for Perl interpreters
            .set("spark.executor.memory", "4g")
            // May need to tune GC for Perl interpreter overhead
            .set("spark.executor.extraJavaOptions", "-XX:+UseG1GC");
            
        JavaSparkContext sc = new JavaSparkContext(conf);
        
        // Now we can use Perl closures that will be executed on workers
        JavaRDD<Row> data = sc.textFile("input.csv")
            .map(line -> RowFactory.create(line.split(",")));
            
        // This Perl closure will be serialized and executed on workers
        JavaRDD<Row> processed = data.map(new PerlClosureFunction(
            "my $row = shift; " +
            "my $amount = $row->{amount}; " +
            "return { %$row, tax => $amount * 0.08, category => classify($amount) };"
        ));
        
        processed.collect();
    }
}

// 5. Cluster deployment considerations
/*
Deployment Requirements:

1. PerlOnJava JAR Distribution:
   - Include perlonJava.jar in spark.jars configuration
   - OR: Install PerlOnJava on every worker node's classpath
   - OR: Use --jars flag when submitting application

2. Perl Module Dependencies:
   - If Perl closures use CPAN modules, those need to be available on workers
   - May need custom Docker images with required Perl modules
   - Or distribute modules as part of the application

3. Memory Overhead:
   - Each worker needs memory for PerlOnJava interpreter(s)
   - May need one interpreter per core/thread
   - Factor this into executor memory allocation

4. Initialization Cost:
   - Perl interpreter initialization has startup cost
   - Consider interpreter pooling or reuse strategies
   - May want to initialize interpreters lazily

5. Error Handling:
   - Perl runtime errors need to propagate back to Spark
   - Need to handle Perl compilation errors gracefully
   - Consider timeout mechanisms for long-running Perl code

Example spark-submit command:
spark-submit \
  --class PerlSparkApplication \
  --master yarn \
  --deploy-mode cluster \
  --jars /path/to/perlonJava.jar \
  --conf spark.executor.memory=4g \
  --conf spark.executor.cores=4 \
  perl-spark-app.jar

*/

// 6. Alternative: Embedded Perl interpreter per executor
public class PerlExecutorPlugin implements ExecutorPlugin {
    private static PerlInterpreter sharedInterpreter;
    
    @Override
    public void init(PluginContext ctx, Map<String, String> extraConf) {
        // Initialize shared Perl interpreter when executor starts
        if (sharedInterpreter == null) {
            synchronized (PerlExecutorPlugin.class) {
                if (sharedInterpreter == null) {
                    sharedInterpreter = new PerlInterpreter();
                    // Pre-load common modules
                    sharedInterpreter.eval("use strict; use warnings;");
                }
            }
        }
    }
    
    public static PerlInterpreter getSharedInterpreter() {
        return sharedInterpreter;
    }
}

