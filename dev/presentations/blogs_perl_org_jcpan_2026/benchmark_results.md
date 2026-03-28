# Benchmark Results

## Closure Benchmark (dev/bench/benchmark_closure.pl)

This benchmark tests closure calls in a loop (20,000 iterations x 5,000 times).

### System Perl
```
$ time perl dev/bench/benchmark_closure.pl
timethis 5000:  7 wallclock secs ( 7.49 usr +  0.00 sys =  7.49 CPU) @ 667.56/s (n=5000)
done 1440000

real    0m7.532s
user    0m7.520s
sys     0m0.006s
```

### PerlOnJava
```
$ time ./jperl dev/bench/benchmark_closure.pl
timethis 5000: 4 wallclock secs ( 3.48 usr +  0.07 sys =  3.54 CPU) @ 1411.12/s (n=5000)
done 1440000

real    0m3.990s
user    0m4.770s
sys     0m0.243s
```

### Summary

| Implementation | CPU Time | Iterations/sec | Speedup |
|----------------|----------|----------------|---------|
| Perl 5         | 7.49s    | 667/s          | baseline |
| PerlOnJava     | 3.54s    | 1411/s         | **2.1x faster** |

The JVM's JIT compiler optimizes hot loops after warmup (~10K iterations).
