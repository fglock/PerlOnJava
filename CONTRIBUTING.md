# Contributing to PerlOnJava

We welcome contributions! This guide will help you get started.

PerlOnJava is actively developed in its
[compatibility and performance phase](docs/about/roadmap.md#current-project-status).
The broad language implementation is in place, so the highest-impact work now
closes remaining Perl behavior gaps, expands CPAN compatibility, and improves
CPU and memory performance.

## Ways to Contribute

- **Report bugs** - Open issues on GitHub
- **Fix bugs** - Submit pull requests
- **Add features** - Implement missing Perl features
- **Improve docs** - Fix typos, add examples, clarify explanations
- **Port modules** - Help port CPAN modules to PerlOnJava
- **Write tests** - Add test coverage
- **Share compatibility results** - Report reproducible `jcpan -t` failures
- **Measure performance** - Contribute repeatable CPU, memory, and startup benchmarks

For issue evidence, classification, and planning conventions, see the
[Issue Reporting and Triage guide](docs/guides/issue-triage.md).

## Quick Start for Contributors

### 1. Set Up Development Environment

```bash
# Clone repository
git clone https://github.com/fglock/PerlOnJava.git
cd PerlOnJava

# Build and run tests
make        # Build + run all unit tests

# Run comprehensive tests
make test-all
```

### 2. Development Workflow

**Before making changes:**
1. Create a feature branch: `git checkout -b feature-name`
2. **Never push directly to master** — always use feature branches and PRs
3. Read the [Architecture Guide](docs/reference/architecture.md)
4. Check [Project Status and Roadmap](docs/about/roadmap.md#current-project-status) for current priorities

**While coding:**
1. Follow existing code style
2. Run `make` frequently to ensure tests pass
3. Add tests for new features in `src/test/resources/unit/`

**Before committing:**
1. Ensure `make` passes (all unit tests)
2. Write clear commit messages

### 3. Submit Pull Request

1. Push your branch: `git push origin feature-name`
2. Open a PR on GitHub
3. Describe what your PR does and why
4. Link related issues

**The #1 priority is keeping `make` working.** All unit tests must pass.

## Code Organization

### Source Code Structure

```
src/main/java/org/perlonjava/
├── app/             # CLI entry point and JSR-223 ScriptEngine
├── frontend/        # lexer, parser, AST nodes, semantic analysis
├── backend/
│   ├── jvm/         # JVM bytecode emitter (ASM)
│   └── bytecode/    # bytecode interpreter backend
├── runtime/
│   ├── runtimetypes/  # RuntimeScalar, RuntimeArray, RuntimeHash, RuntimeCode, ...
│   ├── operators/     # Perl operator implementations
│   ├── regex/         # regex engine integration
│   ├── io/            # file handles and I/O layers
│   └── perlmodule/    # Java implementations of Perl (XS) modules
└── core/            # build-time configuration

src/main/perl/lib/   # bundled Perl modules
src/test/resources/  # Perl test files
third_party/joni/    # forked regex engine
```

### Key Components

- **Lexer** (`frontend/lexer/`) - Tokenizes Perl source
- **Parser** (`frontend/parser/`) - Builds the AST from tokens
- **JVM backend** (`backend/jvm/`) - Emits JVM bytecode using the ASM library
- **Interpreter backend** (`backend/bytecode/`) - Compiles the AST to custom bytecode and interprets it
- **Runtime** (`runtime/runtimetypes/`) - RuntimeScalar, RuntimeArray, RuntimeHash, RuntimeCode
- **Operators** (`runtime/operators/`) - Perl operator implementations

See the [Architecture Guide](docs/reference/architecture.md) for details.

### Test Organization

```
src/test/resources/
└── unit/            # Fast unit tests (run in seconds)

perl5_t/ (at project root)
├── t/               # Perl 5 core test suite
└── [Module]/        # Perl 5 module tests
```

## Development Guidelines

### Building and Testing

```bash
# Build + run all unit tests (default)
make

# Run all tests including Perl 5 core tests
make test-all

# Run single test
./jperl path/to/test.t
```

### Adding Tests

1. Create `.t` file in `src/test/resources/unit/`
2. Use TAP format (Test::More compatible)
3. Test should complete in < 1 second
4. Run with `./jperl path/to/test.t`

Example test:
```perl
print "1..3\n";  # Plan
print "ok 1 - basic test\n";
my $x = 1 + 1;
print $x == 2 ? "ok 2\n" : "not ok 2\n";
print "ok 3 - done\n";
```

### Debugging

```bash
# Show debug information
./jperl --debug -E 'code'

# Check syntax only
./jperl -c script.pl

# Show disassembled bytecode
./jperl --disassemble script.pl

# Run lexer only
./jperl --tokenize -E 'code'

# Run parser only
./jperl --parse -E 'code'
```

## Documentation

### User Documentation

User-facing documentation is in `docs/`:
- **getting-started/** - Installation, quick start, Docker
- **guides/** - How-to guides (database, Java integration, modules)
- **reference/** - Technical reference (features, testing, architecture)
- **about/** - Project information (why, roadmap, support)

### Developer Documentation

Developer documentation is in `dev/` (see [dev/README.md](dev/README.md)):
- **architecture/** - How subsystems work today, including per-feature implementation notes
- **design/** - Proposals and active plans; finished work is in `dev/archive/`
- **modules/** - CPAN module porting notes
- **tools/** - Developer scripts

See also **[AGENTS.md](AGENTS.md)** for detailed testing commands, unimplemented features, environment variables, and git workflow rules.

## Feature Development

### Before Adding a Feature

1. Check if it's in [Feature Matrix](docs/reference/feature-matrix.md)
2. Review [Roadmap](docs/about/roadmap.md)
3. Open an issue to discuss the approach
4. Read relevant design docs in `dev/`

### High-Risk Areas

When modifying these, test extensively:
- **String parsing** (`frontend/parser/StringDoubleQuoted.java`)
- **Lexer** (`frontend/lexer/Lexer.java`)
- **Unicode handling** - Surrogate pairs, identifier parsing
- **Control flow** - die/eval, loop control
- **Bytecode generation** (`backend/jvm/`, `backend/bytecode/`)

### Adding Operators

1. Implement in the `runtime/operators/` package
2. Add parser support in `frontend/parser/`
3. Add tests in `src/test/resources/unit/`
4. Update [Feature Matrix](docs/reference/feature-matrix.md)

### Porting Modules

See **[Module Porting Guide](docs/guides/module-porting.md)** for:
- How to port pure-Perl modules
- Implementing XS modules in Java
- Using XSLoader mechanism

## Community

- **GitHub Issues** - Bug reports and feature requests
- **Pull Requests** - Code contributions
- **Discussions** - Questions and design discussions

## AI-Assisted Contributions

PerlOnJava accepts AI-assisted contributions with proper attribution. See **[AI_POLICY.md](AI_POLICY.md)** for our full policy on commit attribution, human responsibility, and how our approach compares to other open-source projects.

## Code of Conduct

All participants are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting Help

- Read the [Architecture Guide](docs/reference/architecture.md)
- Check existing [Issues](https://github.com/fglock/PerlOnJava/issues)
- Ask in [Discussions](https://github.com/fglock/PerlOnJava/discussions)
- See [Support](docs/about/support.md) for more resources

## License

By contributing, you agree that your contributions will be licensed under the same terms as Perl 5 (Artistic License or GPL v1+).
