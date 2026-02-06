# Useful One-Liners

## YAML Module

Test YAML dump:
```bash
./jperl -MYAML -e 'print Dump({ hello => "world", numbers => [1,2,3] })'
```

Test YAML round-trip:
```bash
./jperl -MYAML -e 'my $data = { hello => "world", numbers => [1,2,3] }; print Dump(Load(Dump($data)))'
```

## JSON Module

Test JSON encode:
```bash
./jperl -MJSON -e 'print encode_json({ hello => "world", numbers => [1,2,3] })'
```

Test JSON pretty print:
```bash
./jperl -MJSON -e 'my $json = JSON->new->pretty(1); print $json->encode({ hello => "world", numbers => [1,2,3] })'
```

## File Operations

Write and read YAML file:
```bash
./jperl -MYAML -e 'DumpFile("test.yml", { hello => "world" }); print Dump(LoadFile("test.yml"))'
```

Write and read JSON file:
```bash
./jperl -MJSON -e 'my $json = JSON->new; DumpFile("test.json", { hello => "world" }); print encode_json(from_json(LoadFile("test.json")))'
```

## Benchmark

# Benchmarking a Perl script to compare performance with a similar operation in PerlOnJava

```bash
time perl -MBenchmark -e 'timethis(200000, sub { my $sum = 0; $sum += $_ ** 2 for 1..1000 });'
timethis 200000:  6 wallclock secs ( 6.02 usr +  0.03 sys =  6.05 CPU) @ 33057.85/s (n=200000)

real	0m6.267s
user	0m6.077s
sys	0m0.051s
```

# Benchmarking the same operation using PerlOnJava `jperl`

```bash
time ./jperl -MBenchmark -e 'timethis(200000, sub { my $sum = 0; $sum += $_ ** 2 for 1..1000 });'
timethis 200000:  1 wallclock secs ( 1,59 usr +  0,02 sys =  1,61 CPU) @ 124488,04/s (n=200000)

real	0m2.374s
user	0m2.791s
sys	0m0.200s
```
