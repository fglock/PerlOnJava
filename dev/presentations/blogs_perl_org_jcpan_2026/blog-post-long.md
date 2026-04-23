# PerlOnJava Gets a CPAN Client

If you've ever tried to run Perl code in a Java environment, you know the drill. Rewrite everything in Java (expensive, risky), or maintain two separate runtimes with all the deployment headaches.

PerlOnJava offers a third path: compile your Perl to JVM bytecode and run it anywhere Java runs. I've been working on Perl-to-JVM compilation, on and off, for longer than I'd like to admit. The latest push has been getting the ecosystem tooling right — and `jcpan` is the result.

## Why This Matters

Some scenarios where this pays off:

**Legacy integration.** You have 50,000 lines of battle-tested Perl that process reports, transform data, or implement business logic. Rewriting it is a multi-year project with uncertain ROI. With PerlOnJava, you can deploy it as a JAR alongside your Java services.

**JDBC database access.** Perl's DBI works with PerlOnJava's JDBC backend. Connect to PostgreSQL, MySQL, Oracle, or any database with a JDBC driver — no DBD compilation required, no driver version mismatches.

    use DBI;
    my $dbh = DBI->connect("jdbc:postgresql://localhost/mydb", $user, $pass);
    my $sth = $dbh->prepare("SELECT * FROM users WHERE active = ?");
    $sth->execute(1);

**Container deployments.** One Docker image with OpenJDK and your Perl code. You don't need a Perl installation, cpanm in your Dockerfile, or XS modules that compiled fine on your laptop.

**Embedding in Java applications.** PerlOnJava implements JSR-223, the standard Java scripting API. Your Java application can `eval` Perl code, pass data back and forth, and let users write Perl plugins.

## The 30-Second Version

    git clone https://github.com/fglock/PerlOnJava.git
    cd PerlOnJava && make

    ./jcpan Moo
    ./jperl -MMoo -e 'print "Moo version: $Moo::VERSION\n"'

That's it. Moo is installed. No cpanm, no local::lib dance.

## What Actually Ships in the JAR

PerlOnJava distributes as a single 23MB JAR file. Inside, you get:

- **568 Perl modules** — DBI, JSON, YAML, HTTP::Tiny, Test::More, and the rest of the usual suspects
- **Java implementations** of key XS modules — DateTime, Digest::MD5, Compress::Zlib
- **The compiler and runtime** — parse Perl, emit JVM bytecode, execute

When you run `./jperl script.pl`, there's no second download, no dependency resolution. The standard library is there.

    # These all work out of the box
    use JSON;
    use HTTP::Tiny;
    use Digest::SHA qw(sha256_hex);
    use Archive::Tar;
    use DBI;

    my $response = HTTP::Tiny->new->get('https://api.example.com/data');
    my $data = decode_json($response->{content});
    print sha256_hex($data->{token}), "\n";

## Installing Additional Modules

The bundled modules cover common use cases, but CPAN has over 200,000 distributions. For everything else, there's `jcpan`:

    ./jcpan Moo                    # Install a module
    ./jcpan -f Some::Module        # Force install (skip failing tests)  
    ./jcpan -t DateTime            # Run a module's test suite
    ./jcpan                        # Interactive CPAN shell

Modules install to `~/.perlonjava/lib/`, which is automatically in `@INC`.

### How Installation Works Without Make

Traditional CPAN installation runs `perl Makefile.PL`, then `make`, then `make install`. This requires a C compiler and the Perl development headers — things that don't exist on the JVM.

PerlOnJava ships a custom `ExtUtils::MakeMaker` that skips the `make` step entirely. When you run `jperl Makefile.PL`, it:

1. Parses the distribution metadata
2. Copies `.pm` files directly to the install location
3. Reports any XS files it can't compile (more on that below)

For pure-Perl modules — which is most of CPAN — this just works.

### What About XS Modules?

XS modules contain C code that gets compiled to native machine code. Since PerlOnJava compiles to JVM bytecode, not native code, these need special handling.

For popular XS modules, PerlOnJava includes **Java implementations** of the XS functions:

- **DateTime** — java.time APIs
- **JSON** — bundled `JSON::PP` (pure Perl)
- **Digest::MD5/SHA** — Java MessageDigest
- **DBI** — JDBC backend
- **Compress::Zlib** — java.util.zip

When you `use DateTime`, PerlOnJava's XSLoader detects the Java implementation and loads it automatically. You get the module's full API, backed by Java libraries.

For XS modules without Java implementations, many have pure-Perl fallbacks that activate automatically. For the rest, installation succeeds (the `.pm` files install), but runtime fails with a clear error message.

## A Real Example: DateTime

DateTime is a good stress test. It has a deep dependency tree — Specio, Params::ValidationCompiler, namespace::autoclean, and so on. It also has XS code for performance-critical date math and a comprehensive test suite.

Here's what happens when you install and test it:

    $ ./jcpan -t DateTime
    ...
    t/00-report-prereqs.t .... ok
    t/00load.t ............... ok
    t/01sanity.t ............. ok
    ...
    t/19leap-second.t ........ ok
    t/20infinite.t ........... ok
    ...
    All tests successful.
    Files=51, Tests=3589, 78 wallclock secs
    Result: PASS

3,589 tests, all passing. The Java XS implementation handles Rata Die conversions (the internal date representation), leap years, leap seconds, and timezone arithmetic. Under the hood, it's using `java.time.JulianFields` — the same code that powers Java's date/time library.

    use DateTime;

    my $dt = DateTime->new(
        year      => 2026,
        month     => 3,
        day       => 28,
        hour      => 14,
        minute    => 30,
        time_zone => 'America/New_York'
    );

    print $dt->strftime('%Y-%m-%d %H:%M %Z'), "\n";
    # Output: 2026-03-28 14:30 EDT

    $dt->add(months => 1);
    print $dt->ymd, "\n";
    # Output: 2026-04-28

## The Other Tools

`jcpan` isn't the only addition. PerlOnJava now includes:

**jperldoc** — Read module documentation:

    ./jperldoc DateTime
    ./jperldoc Moo::Role

**jprove** — Run test suites:

    ./jprove t/*.t
    ./jprove -v t/specific_test.t

These are the standard Perl tools, running on the JVM.

## Performance

**Startup is slow.** The JVM needs to load classes and initialize. A "hello world" takes about 250ms versus Perl's 15ms. That's annoying for command-line scripts, irrelevant for services.

**Short-lived programs don't benefit from JIT compilation.** If your script runs for less than a few seconds, the JVM's just-in-time compiler never kicks in. Test suites, where each `.t` file is a separate process, run slower than native Perl.

**Long-running programs can be significantly faster.** After warmup (~10,000 iterations through hot code paths), the JIT compiler optimizes aggressively. Here's a real benchmark — closure calls in a tight loop:

    $ time perl dev/bench/benchmark_closure.pl
    timethis 5000:  7 wallclock secs ( 7.49 usr ) @ 667/s

    $ time ./jperl dev/bench/benchmark_closure.pl  
    timethis 5000:  4 wallclock secs ( 3.54 usr ) @ 1411/s

PerlOnJava runs this benchmark **2.1x faster** than native Perl. The JVM's C2 compiler inlines calls and unrolls loops.

Bottom line: use it for long-running services and batch jobs. Not for command-line tools that need to start instantly.

## What Doesn't Work

Some things just can't work on the JVM:

- **fork()** — The JVM doesn't do Unix-style forking. Period. For tests that need fork, use native `perl`.

- **Weak references** — `Scalar::Util::weaken` is a no-op. This breaks some cleanup patterns.

- **DESTROY** — Object destructors never run. The JVM has garbage collection, not deterministic destruction. If your code depends on DEMOLISH or cleanup in destructors, it won't work.

- **Some XS modules** — No Java implementation and no pure-Perl fallback means it won't work.

- **Spurious warnings** — There's currently a bug where test description strings trigger `Argument "..." isn't numeric` warnings. Annoying, being fixed.

## Cross-Platform, Single Artifact

PerlOnJava runs on Linux, macOS, and Windows. Same JAR everywhere — the JVM's "write once, run anywhere" actually delivers here. Your deployment is Java 22+, the JAR, and the wrapper scripts.

The project includes a **Dockerfile** for containerized deployments and a **Debian package recipe** (`make deb`) for system installation.

## Getting Started

    # Clone and build
    git clone https://github.com/fglock/PerlOnJava.git
    cd PerlOnJava
    make

    # Run some Perl
    ./jperl -E 'say "Hello from the JVM"'

    # Install a module
    ./jcpan Moo

    # Use it
    ./jperl -MMoo -E '
        package Point {
            use Moo;
            has x => (is => "ro");
            has y => (is => "ro");
        }
        my $p = Point->new(x => 3, y => 4);
        say "Point: (", $p->x, ", ", $p->y, ")"
    '

The project is at [github.com/fglock/PerlOnJava](https://github.com/fglock/PerlOnJava), licensed under the same terms as Perl 5 (Artistic License or GPL v1+). Issues and contributions welcome.

---

*PerlOnJava implements Perl 5.42 semantics and is validated against the Perl test suite. It's been in development since 2024, building on nearly 30 years of prior work on Perl-JVM integration.*
