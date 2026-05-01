# PerlOnJava Development Workspace

This directory contains development resources, tools and experimental code for PerlOnJava that are not part of the production distribution.

## Directory Structure

- **architecture/** - Stable "how the system is built" reference docs
    - Explains how major subsystems work: dynamic scoping, control flow, weaken/DESTROY, lexical pragmas, large-code handling
    - See [architecture/README.md](architecture/README.md)

- **bench/** - Performance benchmarks and comparison tests
    - Perl benchmark scripts for various operations (regex, strings, typing, closures, eval)
    - See [bench/README.md](bench/README.md)

- **cpan-reports/** - CPAN module compatibility tracking
    - Pass/fail/skip lists from automated CPAN testing
    - Per-module notes (Memoize, Scalar::Util, etc.)
    - See [cpan-reports/README.md](cpan-reports/README.md)

- **custom_bytecode/** - Custom bytecode / interpreter subsystem
    - Architecture, opcodes, closure implementation, eval-string spec
    - Tests and optimization results for the interpreter backend
    - See [custom_bytecode/README.md](custom_bytecode/README.md)

- **design/** - Technical documentation, specifications, and analysis
    - Implementation guides, feature specs, optimization plans, root-cause analyses
    - See [design/README.md](design/README.md)

- **design_spark/** - Exploratory / brainstorming material
    - Early-stage ideas, conceptual discussions, proof-of-concept sketches
    - See [design_spark/README.md](design_spark/README.md)

- **diagnostic-traces/** - Raw diagnostic output from debugging sessions
    - Log traces captured with instrumented builds (e.g. refcount tracing)
    - See [diagnostic-traces/README.md](diagnostic-traces/README.md)

- **examples/** - Sample code and usage demonstrations
    - Mix of working examples and feature concepts for design exploration
    - GUI, networking, threading, type system, core-global overrides
    - See [examples/README.md](examples/README.md)

- **implementation/** - Implementation notes for specific features
    - Focused notes on alarm, overload, pack/unpack, regex, signal handling, tie
    - See [implementation/README.md](implementation/README.md)

- **import-perl5/** - Upstream perl5 test import tooling
    - Scripts and config for importing and syncing perl5 test files
    - See [import-perl5/README.md](import-perl5/README.md)

- **jvm_profiler/** - JVM profiling skill and tooling
    - Skill definitions for profiling PerlOnJava with JFR/async-profiler
    - See [jvm_profiler/README.md](jvm_profiler/README.md)

- **known-bugs/** - Known bugs with reproduction cases
    - Minimal Perl scripts that reproduce confirmed bugs
    - See [known-bugs/README.md](known-bugs/README.md)

- **maintenance/** - Ongoing maintenance tracking
    - Dependency update plans, version bump notes
    - See [maintenance/README.md](maintenance/README.md)

- **modules/** - CPAN module porting documentation
    - Module compatibility guides (Moose, Moo, DateTime, DBIx::Class, DBI, Storable)
    - XS fallback mechanisms and Java XS implementations
    - jcpan client and MakeMaker documentation
    - See [modules/README.md](modules/README.md)

- **patches/** - Patches applied to third-party CPAN modules
    - Minimal diffs that make specific CPAN modules work under PerlOnJava
    - See [patches/README.md](patches/README.md)

- **presentations/** - Conference talks and blog posts
    - Slide decks, workshop materials, blog-post drafts
    - See [presentations/README.md](presentations/README.md)

- **prompts/** - LLM prompts for development assistance
    - Code generation and conversion, debugging, documentation, refactoring
    - See [prompts/README.md](prompts/README.md)

- **sandbox/** - Quick tests and proof of concepts
    - IO operations testing, command line handling, regex experiments
    - Compile-time optimizations, lambda handling, stack management prototypes
    - See [sandbox/README.md](sandbox/README.md)

- **scripts/** - One-off development scripts
    - Shell scripts for ad-hoc tasks (fast DBIx::Class checks, etc.)
    - See [scripts/README.md](scripts/README.md)

- **tools/** - Development utilities and helpers
    - Java implementation utilities, Vim configuration, parser/lexer generation tools
    - See [tools/README.md](tools/README.md)

## Contributing

When adding new code or tools:

1. Place it in the appropriate category directory
2. Include clear documentation and usage examples
3. Add relevant tests where applicable
4. Reference related design documents from `design/` or `modules/`

## Development Guidelines

- Keep experimental code separate from production code
- Document design decisions in the `design/` directory
- Document module porting work in the `modules/` directory
- Use the `bench/` directory for performance testing
- Check `examples/` for implementation patterns
- Store reusable LLM prompts in `prompts/` directory
- Raw debugging traces belong in `diagnostic-traces/`; analysed write-ups belong in `design/`
