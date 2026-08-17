# PerlOnJava Feature Audit Plan

## Purpose

Create an evidence-backed inventory of PerlOnJava compatibility gaps and keep
all public documentation consistent with observed behavior. This is an audit
and documentation plan; implementation work and compatibility fixes belong in
follow-up changes.

The canonical status source is
[`docs/reference/feature-matrix.md`](../../docs/reference/feature-matrix.md).
Other documentation must either agree with it or link to a more specific,
current design or module note.

## Audit Method

### 1. Build the inventory

Start with every `❌`, `🚧`, and `🟡` entry in the feature matrix. Search
`docs/`, `dev/`, `README.md`, and module notes for claims such as
“not implemented”, “unsupported”, “partial”, “missing”, or “currently
returns”. Reconcile duplicate claims before changing status.

Older roadmap entries are not evidence by themselves. In particular, verify
whether entries concerning regex, multiplicity, ithreads, and destruction have
since been completed.

Group the audit by behavior rather than by document:

- language operators and statements;
- pragmas, compiler flags, and special variables;
- regular-expression constructs and Unicode behavior;
- I/O, encoding, process, and JVM-lifecycle behavior;
- debugger and introspection features;
- core, bundled, and CPAN modules;
- XS/native integration and direct Java interoperation;
- JSR-223 embedding behavior; and
- documented JVM-specific limitations.

### 2. Define a minimal probe for each gap

Each gap gets a focused probe that answers one compatibility question. Keep
probes small enough to isolate one behavior and record:

| Field | Required value |
|---|---|
| Feature | Exact Perl feature or module capability |
| Native oracle | Expected result from system Perl |
| JVM result | Result from `./jperl` |
| Interpreter result | Result from `./jperl --interpreter` |
| Classification | Supported, partial, unsupported, regression, or JVM limitation |
| Evidence | Test path and captured output |
| Documentation | All files whose status must be updated |

For a broad feature, split the probe by capability. For example, audit each
missing overload operator family separately, and test each JSR-223 gap
(`Invocable`, bindings, I/O writers, and `THREADING`) independently.

### 3. Establish native Perl behavior first

Run every new probe with system Perl before relying on it as compatibility
evidence:

```bash
perl path/to/probe.pl > /tmp/feature-audit-perl.txt 2>&1
```

The native result is the expected-behavior oracle. If native Perl behavior is
version-dependent, record the Perl version and the relevant variation instead
of treating one result as universal.

Then run both PerlOnJava backends with hard timeouts and captured output:

```bash
timeout 60 ./jperl path/to/probe.pl > /tmp/feature-audit-jvm.txt 2>&1
timeout 60 ./jperl --interpreter path/to/probe.pl > /tmp/feature-audit-interpreter.txt 2>&1
```

Use longer, explicit timeouts only where the feature genuinely needs them.
Never run potentially hanging `jperl`, `jcpan`, or `prove` commands without a
timeout, and inspect for unexpected orphaned Java processes after an audit
session.

### 4. Classify results consistently

- **Supported**: both PerlOnJava backends match the native behavior for the
  tested contract.
- **Partial**: the common path works but a documented subcase differs.
- **Unsupported**: the feature is deliberately absent or consistently fails.
- **Regression**: the feature is documented as supported but differs from
  native Perl unexpectedly; route this to a code-fix investigation.
- **JVM limitation**: the semantic difference follows from the JVM execution
  model, such as true OS-level `fork` or native Perl GC/destructor timing.

Do not classify a whole module as supported because only its common path
passes. Record unsupported APIs and native-resource limitations separately.

## Documentation Updates

For each audited item:

1. Update the status and notes in `docs/reference/feature-matrix.md`.
2. Update the related reference page, module note, design document, or
   JSR-223 guide.
3. Update `docs/about/roadmap.md` only for genuinely outstanding work.
4. Remove stale duplicate claims or replace them with links to the canonical
   entry.
5. Link ambiguous status entries to the probe or captured evidence.
6. Re-run repository-wide searches for contradictory status language.

Keep JVM limitations in the JVM-compatibility section, and distinguish them
from features that are merely pending implementation. Keep CPAN compatibility
reports as dated test snapshots; do not infer language-feature support from a
single module result without a focused probe.

## Test and Evidence Deliverables

The audit itself should produce:

- focused probes under the project’s existing test/probe locations;
- native Perl validation output for every new probe;
- JVM-backend and interpreter-backend output;
- a status table or linked evidence for every audited gap; and
- regression tests for any behavior that is promoted from partial or missing
  to supported.

New unit tests must first pass under system Perl, as required by `AGENTS.md`.
Existing tests must not be modified or deleted. Full project verification is
required for implementation PRs, but is intentionally outside this
documentation-only planning change.

## Phases

### Phase 1: Inventory and reconciliation

- Extract the current matrix gaps and duplicate documentation claims.
- Mark stale roadmap claims, especially claims for already-delivered regex,
  multiplicity, ithreads, and destruction behavior.
- Produce the initial feature-to-probe-to-document map.

### Phase 2: Core language and runtime probes

- Audit operators, statements, pragmas, compiler flags, special variables,
  taint behavior, `caller`, overload, smartmatch, and process behavior.
- Compare native Perl, JVM backend, and interpreter backend results.

### Phase 3: Regex, I/O, and JVM-boundary probes

- Audit remaining regex conditional and Unicode edge cases.
- Audit encoded-file positioning, process/DBM behavior, destructor timing,
  native resources, and `fork` limitations.

### Phase 4: Modules, XS, and embedding

- Audit partial core/bundled modules and representative CPAN modules by API
  surface, not only aggregate pass rate.
- Audit XS fallback boundaries, direct Java interoperation, and all JSR-223
  interfaces.

### Phase 5: Documentation synchronization

- Apply evidence-backed status changes to all relevant documentation.
- Check links and search for contradictory claims.
- Update this document’s progress tracking with completed phases, evidence,
  blockers, and next steps.

## Progress Tracking

### Current Status: Plan created; audit not started

### Completed Phases

- [x] Audit workflow defined, including native Perl as the behavior oracle.
- [x] JVM and interpreter commands defined with timeout and output capture.
- [x] Documentation synchronization rules defined.
- [x] CPAN compatibility reports identified as dated snapshots rather than
  feature-level proof.

### Next Steps

1. Build the feature-to-probe inventory from the feature matrix.
2. Reconcile stale and duplicate documentation claims.
3. Add and validate minimal probes with system Perl.
4. Run the probes on both PerlOnJava backends.
5. Update the matrix and related documentation from the evidence.

### Open Questions and Blockers

- Perl version differences must be recorded when native behavior varies.
- Features requiring external services, native libraries, or platform-specific
  privileges need explicitly documented test environments.
- A failing probe that contradicts a supported claim requires a separate
  regression-fix decision; this audit should not silently change implementation
  behavior.
