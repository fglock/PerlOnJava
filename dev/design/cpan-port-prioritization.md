# CPAN port and bundling prioritization

## Purpose

PerlOnJava has more CPAN distributions than can be ported or validated one at
a time. This document defines a repeatable way to identify modules with the
largest downstream impact, decide whether each one should be ported or
bundled, and turn the result into an actionable upgrade queue.

The ranking is an input to engineering decisions, not proof that a module
needs a Java implementation. A high dependant count may indicate a small
pure-Perl compatibility fix, a native module that needs a Java provider, a
module that is already effectively supported, or a failure caused by an
unrelated dependency.

## Definitions

- **Direct dependant**: a released CPAN distribution whose runtime metadata
  declares the module as a requirement.
- **Candidate**: a module present in the CPAN failure report and absent from
  the pass report.
- **Implemented**: a module listed in the bundled-module or XS compatibility
  inventories. These are excluded by default because they already have a
  PerlOnJava provider or port.
- **Port**: make the upstream Perl implementation work on PerlOnJava, usually
  by fixing compiler, runtime, CPAN tooling, or pure-Perl compatibility.
- **Bundle**: ship a Perl implementation, Java-backed provider, compatibility
  shim, or supported replacement as part of PerlOnJava so dependency
  resolution can satisfy consumers without installing an incompatible
  upstream implementation.

## Recreating the dependant list

Run the tool from a checkout containing the current CPAN reports:

```bash
timeout 300 perl dev/tools/cpan_port_priorities.pl \
  --top 100 \
  --bulk-pages 2 \
  --cache /tmp/perlonjava-cpan-port-priorities.json \
  > /tmp/cpan-port-priorities.tsv 2> /tmp/cpan-port-priorities.err
```

The output is TSV with these fields:

| Field | Meaning |
|---|---|
| `module` | Candidate module to investigate |
| `status` | CPAN report status, normally `FAIL` |
| `porting_signal` | Coarse signal from the failure text; it is not a diagnosis |
| `reverse_dependents` | Unique released distributions with a runtime requirement |
| `recent_dependents` | Dependants whose release date is within the recent window |
| `score` | `reverse_dependents + 2 * recent_dependents` |
| `summary` | CPAN tester failure summary |
| `examples` | Sample dependant distributions |

The default sort is descending `reverse_dependents`, with recent dependants
and module name as tie-breakers. Alternative views are available when useful:

```bash
# Prioritize current ecosystem pressure.
timeout 300 perl dev/tools/cpan_port_priorities.pl --sort recent

# Prioritize total plus recency weighting.
timeout 300 perl dev/tools/cpan_port_priorities.pl --sort score

# Include skipped reports after they have been reviewed as candidates.
timeout 300 perl dev/tools/cpan_port_priorities.pl --include-skip

# Restrict investigation to native/XS-looking failures.
timeout 300 perl dev/tools/cpan_port_priorities.pl --native-only

# Verify one module with the per-module reverse-dependency endpoint.
timeout 300 perl dev/tools/cpan_port_priorities.pl --targeted --module DBD::mysql
```

The bulk mode fetches latest released distribution metadata and counts
dependencies locally. It deliberately does not ask MetaCPAN to sort the
records. The cache avoids repeating requests, and the default two 5,000-row
pages limit load on MetaCPAN. If the tool reports truncation, increase
`--bulk-pages` deliberately and retain the warning with the generated result.
The request headers should be inspected before increasing the rate: honor
`Retry-After` or any provider-specific rate-limit headers if they appear.

The result is a bounded prioritization list, not a complete CPAN census. The
current query window, latest-release filtering, dependency phase, and
MetaCPAN metadata quality all affect the numbers. Record the command, date,
cache scope, and report revision whenever a list is used for planning.

## Filtering and validation

The default list applies these filters:

1. Exclude modules in `cpan-compatibility-pass.dat`.
2. Exclude modules listed in `docs/reference/bundled-modules.md` or
   `docs/reference/xs-compatibility.md`.
3. Exclude skipped, timeout, and unknown-outcome records unless explicitly
   requested.
4. Count only runtime `requires` dependencies, not build- or test-only
   relationships.
5. Deduplicate dependants by distribution rather than counting every release
   or repeated dependency declaration.

Before accepting a row as a porting target, check:

- the failure log and its classification;
- the same distribution under system Perl;
- whether the failure is in the target, dependency installation, or harness;
- whether a provider already exists under another module name;
- whether the dependant count is stable across a fresh cache and a larger
  bulk window;
- whether the module is a runtime dependency or mostly a test-only utility.

The ranking should be enriched with a small decision record:

```yaml
module: Example::Module
dependants: 42
report_status: FAIL
failure_class: native-extension
recommended_action: bundle
provider: java-xs
confidence: medium
reason: "Required by database and ORM distributions; no portable backend"
next_step: "Prototype provider and run direct upstream tests"
```

## Choosing port, bundle, or defer

Use the following order of questions:

1. **Is the failure a PerlOnJava semantic or tooling defect?** Fix the
   compiler, runtime, module provider, or CPAN resolver. This is preferred
   when the upstream implementation is otherwise portable because one fix can
   unblock many modules.
2. **Is the module pure Perl with a small platform assumption?** Port the
   compatibility behavior or add a narrow capability policy. Keep the
   upstream module and tests as the conformance source.
3. **Does it require XS or a native library with a stable Java equivalent?**
   Bundle a Java-backed provider or compatibility shim. The provider manifest
   must declare its version, API surface, and shadow policy.
4. **Does it wrap a database or external service?** Prefer a deliberately
   scoped shim that preserves the common DBI/API surface and defers operations
   to a supported Java driver. Do not claim full compatibility until the
   relevant upstream tests pass.
5. **Is the capability unavailable on the JVM?** Defer it with an explicit
   capability policy and a precise failure classification. Do not hide the
   failure or mark an unrun test as passing.

Priority should combine impact and tractability. A useful initial rubric is:

| Factor | Question |
|---|---|
| Impact | How many runtime distributions depend on it? |
| Breadth | Does it unblock unrelated application areas? |
| Severity | Is it a hard install blocker or a narrow test failure? |
| Leverage | Will fixing PerlOnJava behavior help other modules too? |
| Feasibility | Is there a portable implementation or Java provider? |
| Maintenance | Can the provider track upstream releases and behavior? |
| Confidence | Do system-Perl and independent metadata checks agree? |

High-impact, high-leverage, high-confidence rows should become project issues.
Native modules with many dependants should normally receive a bundling design
issue before implementation. Low-impact modules with difficult native APIs
should remain visible in the queue but be deferred.

## Automated module upgrades

The long-term goal is a controlled upgrade pipeline rather than manually
copying individual CPAN modules into the repository.

### 1. Maintain provider and inventory metadata

Create a machine-readable provider manifest containing, for each bundled or
ported module:

```yaml
module: XML::LibXML
provider: java-xs
version: 2.021
source: bundled
shadow_policy: forbidden
conformance_command: "jcpan -t XML::LibXML"
owner: runtime-modules
```

Generate the existing documentation tables from this manifest where
practical. The prioritization tool should consume the same source so that a
module is not ranked as missing merely because one inventory document was not
updated.

### 2. Refresh reports and metadata on a schedule

A scheduled job should:

1. refresh CPAN compatibility records using the existing tester;
2. capture the report revision and current PerlOnJava commit;
3. update the bounded MetaCPAN cache with throttling and conditional requests
   where supported;
4. generate a sorted TSV and a compact Markdown summary;
5. compare the result with the previous run and identify new, resolved, and
   materially changed candidates.

The job must use one writer for report files, preserve full logs as artifacts,
and avoid concurrent CPAN tester processes that share intermediate `.dat`
files.

### 3. Generate upgrade proposals, not automatic merges

For each new or materially higher-priority candidate, automation should open
or update an issue containing:

- the dependant count and query date;
- the CPAN report and failure-log path;
- the proposed port/bundle/defer classification;
- system-Perl comparison results;
- affected provider or inventory entries;
- a bounded acceptance plan.

Automation may prepare a branch with a version bump, provider manifest update,
or refreshed pure-Perl source, but it must not merge a module upgrade without
the conformance gates below.

### 4. Upgrade and conformance gates

Every proposed upgrade should run in an isolated worktree and verify:

- dependency resolution with the intended provider;
- upstream distribution tests under system Perl;
- the same tests under the JVM backend and interpreter backend;
- focused PerlOnJava regression coverage for any discovered defect;
- no accidental shadowing of a bundled provider;
- report classification changes are intentional;
- the provider manifest and generated inventories agree.

Native providers additionally need API-surface checks and a documented
unsupported-capability policy. A failed gate should update the issue with
evidence and leave the previous provider/version active.

### 5. Lifecycle and rollback

Keep the previous provider and metadata available until the new version passes
the acceptance gate. Record source distribution, version, checksum, patch
set, and test evidence. If a scheduled upgrade regresses dependants, revert
the provider manifest selection first, then investigate the upstream or
PerlOnJava defect in a separate change.

## Proposed implementation phases

### Phase 1: Reproducible ranking

- Keep `cpan_port_priorities.pl` cacheable and bounded.
- Add a generated Markdown summary beside the TSV output.
- Record query metadata and truncation status.
- Validate the ranking against several targeted reverse-dependency queries.

### Phase 2: Provider inventory

- Define the provider manifest schema.
- Reconcile bundled-module and XS compatibility documentation with it.
- Make the resolver consume provider entries and enforce shadow policy.

### Phase 3: Upgrade proposals

- Add a scheduled metadata/report refresh.
- Generate or update GitHub issues for priority changes.
- Attach logs, ranking evidence, and proposed acceptance gates.

### Phase 4: Controlled upgrade branches

- Generate isolated upgrade branches for selected pure-Perl modules and
  providers.
- Run the full conformance matrix.
- Require human review for native shims, database adapters, and capability
  policy changes.

## Progress tracking

### Current status: Phase 1 in progress

Completed:

- Added `dev/tools/cpan_port_priorities.pl`.
- Added bounded bulk MetaCPAN lookup with local dependant counting.
- Added pass/implemented filtering and local sorting modes.
- Added the initial top-dependant report workflow to PR #1109.

Next steps:

1. Validate bulk counts against targeted queries for a representative sample.
2. Define and check in the provider manifest schema.
3. Generate a compact Markdown report suitable for issue updates.
4. Design the scheduled refresh and upgrade-proposal job.

Open questions:

- Should the recent-dependant window remain three years, or use release-rate
  percentiles?
- Should test-only dependencies receive a separate ranking?
- Which provider versions and API surfaces are stable enough for automated
  upgrades?

Related material: `dev/tools/cpan_port_priorities.pl`,
`dev/design/cpan-workaround-elimination.md`,
`docs/reference/bundled-modules.md`, and
`docs/reference/xs-compatibility.md`.
