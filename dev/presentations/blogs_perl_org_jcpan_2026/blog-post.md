# PerlOnJava - CPAN client for the JVM

[PerlOnJava](https://github.com/fglock/PerlOnJava) now includes `jcpan`, a CPAN client that installs pure Perl modules without needing `make` or a C compiler. It runs on Linux, macOS, and Windows - just add Java 22+.

## Installing modules

```bash
$ ./jcpan Moo
```

Modules are installed to `~/.perlonjava/lib/`, which is automatically in `@INC`.

The interactive CPAN shell also works:

```
$ ./jcpan
cpan shell -- CPAN exploration and target module installation
cpan[1]> install DateTime
```

## Other tools

PerlOnJava also includes:

- `jperldoc` - documentation viewer
- `jprove` - TAP test harness

```bash
$ ./jperldoc DateTime
$ ./jprove t/*.t
```

## How it works

PerlOnJava includes a custom `ExtUtils::MakeMaker` that copies `.pm` files directly to the install location. No `make` step, no native toolchain.

For XS modules (those with C code), PerlOnJava provides Java implementations for popular ones:

| Module | Java Backend |
|--------|--------------|
| DateTime | java.time with JulianFields |
| JSON | bundled `JSON::PP` (pure Perl) |
| Digest::MD5/SHA | Java MessageDigest |
| DBI | JDBC |
| Compress::Zlib | java.util.zip |

## Moo example

Moo is a lightweight object system. After installing with `jcpan Moo`:

```perl
package Point {
    use Moo;
    has x => (is => 'ro', default => 0);
    has y => (is => 'ro', default => 0);
    
    sub distance {
        my $self = shift;
        return sqrt($self->x ** 2 + $self->y ** 2);
    }
}

my $p = Point->new(x => 3, y => 4);
print "Distance from origin: ", $p->distance, "\n";
```

Output:
```
Distance from origin: 5
```

## DateTime example

DateTime is one of the more complex CPAN modules. It passes 100% of tests on PerlOnJava:

```bash
$ ./jcpan DateTime
```

```perl
use DateTime;
my $dt = DateTime->new(
    year      => 2026,
    month     => 3,
    day       => 28,
    time_zone => 'America/New_York'
);
print $dt->strftime('%Y-%m-%d %Z'), "\n";
```

Output:
```
2026-03-28 EDT
```

The Java XS implementation uses `java.time.JulianFields` for Rata Die conversions and includes full leap second support.

## What's included

PerlOnJava ships with 568 Perl modules bundled in the jar — JSON, YAML, HTTP::Tiny, DBI, Archive::Tar, Test::More, Try::Tiny, and more. No installation needed.

## Performance

JVM startup is slower than Perl, but long-running programs benefit from JIT compilation. After warmup (~10K iterations), hot loops become significantly faster:

```
$ time perl dev/bench/benchmark_closure.pl
timethis 5000:  7 wallclock secs ( 7.49 usr ... ) @ 667/s

$ time ./jperl dev/bench/benchmark_closure.pl
timethis 5000:  4 wallclock secs ( 3.54 usr ... ) @ 1411/s
```

**PerlOnJava: 2.1x faster** for this closure benchmark.

Short-lived programs (like individual test files) won't see this benefit - the JIT doesn't have time to warm up. Running test suites is slower than native Perl.

## Current limitations

- `fork` - not available (use native `perl` for fork-heavy tests)
- `weaken` / `DESTROY` - not implemented
- XS modules without Java implementations or pure Perl fallbacks won't work
- JVM startup overhead - short programs are slower than Perl
- Test suites run slower due to per-file JVM startup
- Some module tests fail due to unimplemented features (Moo's `weaken` tests, for example)
- Spurious warnings: PerlOnJava currently emits `Argument "..." isn't numeric` warnings for test description strings - this will be fixed

---

More info: https://github.com/fglock/PerlOnJava
