# PerlOnJava, Perl, and JRuby benchmark

This report compares five small, checksum-checked workloads on PerlOnJava's
JVM backend, standard Perl, and JRuby. It records the benchmark source and
artifact revision so the run can be repeated with
[`dev/bench/three_engine/compare.py`](three_engine/compare.py).

## Results

Each throughput value is the mean of seven fresh-process pair medians; each
pair median uses seven one-second measurement windows. Speedup intervals are
paired 95% bootstrap percentile intervals over the seven fresh-process pairs
(50,000 resamples). Ratios above 1.0 mean the numerator engine completed more
work units per second.

| Workload | PerlOnJava ops/s | Perl ops/s | JRuby ops/s | PerlOnJava / Perl (95% interval) | JRuby / Perl (95% interval) | PerlOnJava / JRuby (95% interval) |
|---|---:|---:|---:|---:|---:|---:|
| Numeric accumulation | 10,194,164 | 43,306,326 | 74,144,057 | 0.235x (0.225–0.247) | 1.711x (1.632–1.782) | 0.137x (0.132–0.143) |
| Captured closure call | 2,815,960 | 20,737,794 | 63,143,723 | 0.136x (0.134–0.138) | 3.043x (2.961–3.125) | 0.045x (0.043–0.046) |
| Method dispatch and object state | 2,252,368 | 8,263,144 | 41,109,577 | 0.271x (0.249–0.286) | 4.969x (4.776–5.279) | 0.054x (0.047–0.059) |
| String transforms | 2,148,552 | 7,522,721 | 15,514,807 | 0.292x (0.248–0.376) | 2.132x (1.730–2.755) | 0.137x (0.118–0.158) |
| Regex capture | 1,150,372 | 4,844,742 | 6,791,035 | 0.239x (0.224–0.251) | 1.401x (1.295–1.516) | 0.170x (0.160–0.181) |

The measurements favor JRuby on these workloads. PerlOnJava throughput ranged
from 13.6% to 29.2% of Perl and from 4.5% to 17.0% of JRuby. These are
synthetic microbenchmarks, not application-level performance predictions.
Each workload uses matching operations and expected results, but the Perl and
Ruby workers express those operations in their respective languages.

## Method

The runner uses five workloads: local numeric accumulation; repeated calls to
a closure capturing a value; method dispatch that updates two object fields;
uppercase, reverse, concatenate, and length operations on a fixed ASCII
string; and a regex capture of four digits. All three workers use the same
input data and one work unit per loop iteration.

For each workload, seven fresh-process triplets run in randomized engine
orders. The schedule covers all six possible orders before repeating one.
Each engine runs alone, so the measured runtimes do not compete with each
other for CPU. A persistent worker handles five one-second warmup windows and
then seven one-second measured windows; startup and JVM initialization are
outside the scored windows. PerlOnJava and JRuby both use Temurin OpenJDK
24.0.2. The runner checks each returned checksum against the closed-form
expected value before retaining any sample. All 1,260 windows passed those
checks.

For each process pair, throughput is the median of its measured windows. Each
pairwise speedup is calculated from the two engines' medians in that triplet.
The table's pairwise result is the geometric mean of those seven ratios. The
interval resamples the paired ratios with replacement. This design reduces
order and short-term host-load effects, though it cannot remove them.

## Environment and limits

- PerlOnJava source commit: `d4a466409b036a37305006af6b9fb4d17aaeff81`
- PerlOnJava JAR: `target/perlonjava-5.44.1.jar`
- JAR SHA-256: `c25df4cc5267d23a3fb112372fef1a7fd84c62845fbbbc04c73634e09fb887ef`
- Perl: `v5.42.2`
- JRuby: `10.1.3.0`, running on Temurin OpenJDK `24.0.2+12`
- Host: macOS `26.7.3`, arm64, 16 logical CPUs
- Load averages before the run: `31.5, 44.1, 45.4` (1, 5, and 15 minutes)
- Load averages after the run: `20.4, 35.1, 35.1` (1, 5, and 15 minutes)

The host was under substantial shared load. Randomized sequential pairing
reduces direct contention between the engines, but unrelated host activity
could still affect timings. The wider intervals for strings and method
dispatch reflect greater run-to-run variation. Treat the results as
directional and specific to this workload set and host. They do not measure
memory use, startup latency, interpreter-backend performance, or complete
applications.

The JRuby launcher printed `AppCDS archive directory is not writable` during
startup. Startup was excluded from timed windows.

## Reproduction

Run from the repository root with Perl, JRuby, and a JDK supported by both
PerlOnJava and JRuby:

```sh
timeout 3600 python3 dev/bench/three_engine/compare.py \
  --java-home "$(/usr/libexec/java_home -v 24)" \
  --output /tmp/three-engine-comparison.json
```

The default command runs seven pairs, five warmup windows, and seven measured
windows per engine and workload. The runner writes raw samples to the required
`--output` path and rejects paths inside the repository. Keep that JSON and
the process log outside version control; this report intentionally publishes
only the summary and methodology.
