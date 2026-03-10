# Profile PerlOnJava

Profile and optimize PerlOnJava runtime performance using Java Flight Recorder.

## Git Workflow

**IMPORTANT: Never push directly to master. Always use feature branches and PRs.**

```bash
git checkout -b perf/optimization-name
# ... make changes ...
git push origin perf/optimization-name
gh pr create --title "Perf: description" --body "Details"
```

## When to Use

- Investigating performance bottlenecks in Perl scripts running on PerlOnJava
- Finding optimization opportunities in the runtime
- Measuring impact of optimizations

## Workflow

### 1. Run with JFR Profiling

```bash
cd /Users/fglock/projects/PerlOnJava2

# Profile a long-running script (adjust duration as needed)
java -XX:+FlightRecorder \
  -XX:StartFlightRecording=duration=60s,filename=profile.jfr \
  -jar target/perlonjava-3.0.0.jar <script.pl> [args...]
```

### 2. Analyze with JFR Tools

```bash
# Path to jfr tool
JFR="$(/usr/libexec/java_home)/bin/jfr"

# Summary of recorded events
$JFR summary profile.jfr

# Extract execution samples (CPU hotspots)
$JFR print --events jdk.ExecutionSample profile.jfr

# Aggregate hotspots by method (most useful)
$JFR print --events jdk.ExecutionSample profile.jfr 2>&1 | \
  grep -E "^\s+[a-z].*line:" | \
  sed 's/line:.*//' | \
  sort | uniq -c | sort -rn | head -40
```

### 3. Key Hotspot Categories

| Category | Methods to Watch | Optimization Approach |
|----------|------------------|----------------------|
| **Number parsing** | `Long.parseLong`, `Double.parseDouble`, `NumberParser.parseNumber` | Cache numeric values, avoid string→number conversions |
| **Type checking** | `ScalarUtils.looksLikeNumber`, `RuntimeScalar.getDefinedBoolean` | Fast-path for common types (INTEGER, DOUBLE) |
| **Bitwise ops** | `BitwiseOperators.*` | Ensure values stay as INTEGER type |
| **Regex** | `Pattern.match`, `Matcher.matches` | Reduce unnecessary regex checks |
| **Loop control** | `RuntimeControlFlowRegistry.checkLoopAndGetAction` | ThreadLocal overhead |
| **Array ops** | `ArrayList.grow`, `Arrays.copyOf` | Pre-size arrays, reduce allocations |

### 4. Common Runtime Files

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` | Scalar value representation, getLong/getDouble/getInt |
| `src/main/java/org/perlonjava/runtime/runtimetypes/ScalarUtils.java` | Utility functions like looksLikeNumber |
| `src/main/java/org/perlonjava/runtime/operators/BitwiseOperators.java` | Bitwise operations (&, |, ^, ~, <<, >>) |
| `src/main/java/org/perlonjava/runtime/operators/Operator.java` | General operators |
| `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeArray.java` | Array operations |

### 5. Optimization Patterns

#### Fast-path for common types
```java
public static boolean looksLikeNumber(RuntimeScalar runtimeScalar) {
    // Inlined fast-path for most common numeric types
    int t = runtimeScalar.type;
    if (t == INTEGER || t == DOUBLE) {
        return true;
    }
    return looksLikeNumberSlow(runtimeScalar, t);
}
```

#### Avoid repeated parsing
```java
// Bad: parses string every time
long val = runtimeScalar.getLong();  // calls Long.parseLong if STRING

// Better: check type first, use cached value
if (runtimeScalar.type == INTEGER) {
    long val = (int) runtimeScalar.value;  // direct access
}
```

### 6. Benchmark Commands

```bash
# Quick benchmark with life_bitpacked.pl
java -jar target/perlonjava-3.0.0.jar examples/life_bitpacked.pl \
  -w 200 -h 200 -g 10000 -r none

# Multiple runs for consistency
for i in 1 2 3; do
  java -jar target/perlonjava-3.0.0.jar examples/life_bitpacked.pl \
    -w 200 -h 200 -g 10000 -r none 2>&1 | grep "per second"
done
```

### 7. Build and Test

```bash
# Rebuild after changes
mvn package -q -DskipTests

# Run tests to verify correctness
mvn test -q
```

## Example Session

```
1. Identify slow script or operation
2. Profile with JFR (60s recording)
3. Aggregate hotspots by method
4. Identify top bottlenecks (parsing, type checks, etc.)
5. Implement fast-path optimization
6. Rebuild and benchmark
7. Profile again to verify improvement
8. Run tests to ensure correctness
```
