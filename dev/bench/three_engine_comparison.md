# PerlOnJava, Perl, and JRuby benchmark

This report compares six small, checksum-checked workloads on PerlOnJava's
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
| Numeric accumulation | 12,626,553 | 53,356,105 | 87,899,537 | 0.237x (0.231–0.241) | 1.644x (1.563–1.728) | 0.144x (0.139–0.149) |
| Captured closure call | 2,252,236 | 16,522,560 | 50,928,248 | 0.135x (0.116–0.151) | 3.089x (2.917–3.317) | 0.044x (0.039–0.047) |
| Method dispatch and object state | 2,073,805 | 7,265,630 | 33,248,705 | 0.287x (0.268–0.312) | 4.523x (4.030–5.080) | 0.064x (0.058–0.072) |
| String transforms | 2,991,210 | 11,263,684 | 21,083,605 | 0.265x (0.246–0.284) | 1.873x (1.806–1.961) | 0.142x (0.133–0.149) |
| Regex capture | 1,195,954 | 4,507,043 | 7,482,653 | 0.266x (0.246–0.294) | 1.692x (1.389–2.164) | 0.157x (0.130–0.179) |
| Eval-string compilation | 201,193 | 506,993 | 143,559 | 0.396x (0.360–0.437) | 0.283x (0.273–0.296) | 1.397x (1.305–1.504) |

JRuby led five workloads. For eval-string compilation, PerlOnJava reached 1.397x
JRuby's throughput and 0.396x Perl's. On the numeric, method, string, and regex
workloads, PerlOnJava reached 23.7% to 28.7% of Perl and 14.4% to 15.7% of
JRuby. On closure calls, it reached 13.5% of Perl and 4.4% of JRuby. These are
synthetic microbenchmarks, not application-level performance predictions.
Each workload uses matching operations and expected results, but the Perl and
Ruby workers express those operations in their respective languages.

## Method

The run used six workloads: local numeric accumulation; repeated calls to a
closure capturing a value; method dispatch that updates two object fields;
uppercase, reverse, concatenate, and length operations on a fixed ASCII
string; a regex capture of four digits; and eval-string compilation. In the
eval-string workload, every operation compiles a distinct source string whose
monotonically increasing identifier appears in `1 + identifier - identifier`;
each source evaluates to one. The worker process persists across warmup and
measured windows, so identifiers are not reused. All three workers use the
same workload semantics and one work unit per loop iteration.

For each workload, seven fresh-process triplets run in randomized engine
orders. The schedule covers all six possible orders before repeating one.
Each engine runs alone, so the measured runtimes do not compete with each
other for CPU. A persistent worker handles five one-second warmup windows and
then seven one-second measured windows; startup and JVM initialization are
outside the scored windows. PerlOnJava and JRuby both use Temurin OpenJDK
24.0.2. The runner checks each returned checksum against the closed-form
expected value before retaining any sample. All 1,512 windows passed those
checks.

For each process pair, throughput is the median of its measured windows. Each
pairwise speedup is calculated from the two engines' medians in that triplet.
The table's pairwise result is the geometric mean of those seven ratios. The
interval resamples the paired ratios with replacement. This design reduces
order and short-term host-load effects, though it cannot remove them.

## Environment and limits

- PerlOnJava source commit: `da0f0eb8de818cf4bea8b09a092ffc52c8d96546`
- PerlOnJava JAR: `target/perlonjava-5.44.1.jar`
- JAR SHA-256: `78bfb30a0b7e2447798a9c38f04d868b48f1ecf569f08b4d45803dba34e4da7a`
- Perl: `v5.42.2`
- JRuby: `10.1.3.0`, running on Temurin OpenJDK `24.0.2+12`
- Host: macOS `26.7.3`, arm64, 16 logical CPUs
- Load averages before the run: `3.7, 5.3, 6.6` (1, 5, and 15 minutes)
- Load averages after the run: `35.5, 31.3, 23.9` (1, 5, and 15 minutes)

The host load was low before the run and high afterward. Randomized sequential
pairing reduces direct contention between engines, but host activity during
the run could still affect timings, especially for regex and eval-string,
which had wider intervals. Treat the results as directional and specific to
this workload set and host. They do not measure memory use, startup latency,
interpreter-backend performance, or complete applications.

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
