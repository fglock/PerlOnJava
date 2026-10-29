# Reviewed issue queues

Snapshot: 2026-10-29, from live open issues, PRs and the #1649 phase contract.
This is a bounded selection aid, not the complete backlog or fresh runtime
validation. Follow the [triage and claim protocol](../../docs/guides/issue-triage.md)
and recheck live issue comments/PRs before starting. Unlisted work is unreviewed
for this index. Priorities below are recommendations, not GitHub labels.

Kind is explicit by table: **Organizational** or **Project**, independent of
area labels. States and priorities use the guide's definitions. Rows sharing a
code/test boundary help route work; only established causes justify batching.

## Organizational queue

| Issue | Priority / state | Rationale, next action and dependency |
|---|---|---|
| [#1895](https://github.com/fglock/PerlOnJava/issues/1895) Agent coordination | Normal / Active | Implement this workflow and validate links/skills. Claim: Codex `root-1895-20261027`, branch `docs/issue-1895-agent-coordination`; [current checkpoint](https://github.com/fglock/PerlOnJava/issues/1895#issuecomment-6437578415). |
| [#1110](https://github.com/fglock/PerlOnJava/issues/1110) CPAN port selection | Normal / Investigate | Refresh dependant ranking and choose the next bounded ports. Missing evidence: current targeted counts and provider inventory; no proven #1649 prerequisite for planning. |
| [#1646](https://github.com/fglock/PerlOnJava/issues/1646) Release qualification | High / Investigate | Reconcile old suite/setup observations with current master and [PR #1877](https://github.com/fglock/PerlOnJava/pull/1877). Independent setup can proceed. Final lifecycle qualification waits for the relevant ownership/cleanup candidate and unchanged acceptance suites; old UAT is insufficient. |

## Project shortlist

Ready means the existing report supplies scope and acceptance; first refresh its
reproducer on the chosen base. Investigate means missing diagnosis/scope must be
resolved before implementation. No new pass or failure is asserted here.

| Code/test boundary | Issue | Priority / state | Evidence, next action and blocker |
|---|---|---|---|
| File-handle EOF state | [#1922](https://github.com/fglock/PerlOnJava/issues/1922) `IO::Handle::clearerr` | Normal / Active | `Log::Procmail` 0.14 passes on system Perl but failed on both PerlOnJava backends when reading appended data after EOF. The shared runtime fix and permanent regression are in [draft PR #1924](https://github.com/fglock/PerlOnJava/pull/1924); the full make gate and all 281 CPAN tests pass. Await review and CI. |
| File metadata | [#1912](https://github.com/fglock/PerlOnJava/issues/1912) `Time::HiRes::stat` | Normal / Active | Daiku 1.004 passes on system Perl and exposed the missing runtime function; the implementation and both-backend regression are in [PR #1913](https://github.com/fglock/PerlOnJava/pull/1913). |
| XML provider APIs | [#1892](https://github.com/fglock/PerlOnJava/issues/1892) XSLT output | High / Active | [PR #1894](https://github.com/fglock/PerlOnJava/pull/1894) is open; verify its serialization fix against HTML::ToDocBook. Coordinate through that work rather than start a duplicate. |
| XML provider APIs | [#1323](https://github.com/fglock/PerlOnJava/issues/1323) `indexElements` | Normal / Ready | Missing Document method is identified. Add standard-Perl-validated focused coverage and Cindy acceptance; check XML provider overlap with #1894. No established ownership prerequisite. |
| Frontend constants/quoting | [#1481](https://github.com/fglock/PerlOnJava/issues/1481) Literal control characters | Normal / Ready | One upstream failure on both backends, Perl passes. Reduce literal `qw` operands before choosing frontend versus regex code; require focused and upstream acceptance. |
| Frontend constants/quoting | [#1376](https://github.com/fglock/PerlOnJava/issues/1376) Constant hash-reference dereference | High / Investigate | Both-backend suite failures are reported. Minimize constant folding/dereference behavior and establish a failing current parent before selecting the fix. |
| Parser syntax | [#1427](https://github.com/fglock/PerlOnJava/issues/1427) Test::Unit / Error.pm | Normal / Investigate | Separate catch-syntax behavior from example-path normalization; retain the full passing Perl comparison and identify independently fixable causes. |
| Calls and value operations | [#1448](https://github.com/fglock/PerlOnJava/issues/1448) Audit-wrapper recursion | High / Investigate | Runaway recursion on both backends with a passing Perl suite. Trace captured original coderef identity and wrapper replacement under a timeout; no proven #1649 dependency. |
| Calls and value operations | [#1342](https://github.com/fglock/PerlOnJava/issues/1342) Relational overload dispatch | Normal / Ready | Report identifies an unwanted `cmp` dispatch. Add direct relational-overload coverage and require Morpheus acceptance on both backends. |
| Calls and value operations | [#1428](https://github.com/fglock/PerlOnJava/issues/1428) Decimal stringification | Normal / Ready | One DateTime::Format::Epoch mismatch with passing Perl suite. Reduce the numeric conversion and cover large integral results without scientific notation. |
| Warnings category tables | [#1208](https://github.com/fglock/PerlOnJava/issues/1208), [#1219](https://github.com/fglock/PerlOnJava/issues/1219) Experimental categories | Normal / Ready | Unknown valid categories prevent module load. Check shared registration code, add category-specific focused tests and upstream load acceptance; batch only if one registration mechanism owns both. |
| Regex matcher performance | [#1174](https://github.com/fglock/PerlOnJava/issues/1174) Anchored full match | High / Investigate | Reported 475–550x gap has a bounded workload. Reproduce/profile exact source and JAR, then define semantics-preserving engine coverage. No ownership rewrite prerequisite is established. |
| Call-path measurement | [#1885](https://github.com/fglock/PerlOnJava/issues/1885) Memory and ordinary calls | Normal / Investigate | Follow up #1870 with matched allocation/live-heap/RSS and general-call evidence. Coordinate any frame/argument ownership edits with #1649; measurements alone can proceed. |
| Call-path measurement | [#1863](https://github.com/fglock/PerlOnJava/issues/1863) Throughput acceptance | Normal / Verify | Portfolio targets are reported met by #1870. Reconcile merged-source evidence and remaining criteria with #1885 before scheduling more optimizations. |
| Scalar string growth | [#1298](https://github.com/fglock/PerlOnJava/issues/1298) Repeated `.=` | Normal / Investigate | Establish current scaling and alias/encoding requirements; keep attribution separate from #1174's matcher cost. Check scalar-representation overlap with #1649 before implementation. |
| CPAN update tooling | [#1114](https://github.com/fglock/PerlOnJava/issues/1114), [#1278](https://github.com/fglock/PerlOnJava/issues/1278) Port updater/manifest | Normal / Investigate | Project features: executable import/update behavior. Compare scope and agree one shared manifest boundary, then separate deliverables and required gates. Related proposals are not yet confirmed duplicates. |

## Ownership work and holds (Project)

These are outside the ready implementation queue. A hold on conflicting edits
still permits an independent read-only reduction or evidence review. A delivered
path can unblock an issue before the entire #1649 project is complete.

| Issue | State / relationship | Required condition before independent implementation or final verification |
|---|---|---|
| [#1649](https://github.com/fglock/PerlOnJava/issues/1649) Ownership rewrite | Active; user-confirmed, [PR #1816](https://github.com/fglock/PerlOnJava/pull/1816) | Consult current claim/candidate. Do not infer a permanent owner from GitHub assignees. Default-enabled phase acceptance, not dormant primitives, establishes delivery. |
| [#1618](https://github.com/fglock/PerlOnJava/issues/1618) HTML::Tree refloop | Blocked by #1649 aggregate ownership path (phase 2); PR #1816 reports candidate coverage | Integrated default path, exact-count/focused parent regression evidence and unchanged `t/refloop.t` 8/8 on both backends. Candidate success alone is not merged delivery. |
| [#1860](https://github.com/fglock/PerlOnJava/issues/1860) Growing lexical root scans | Blocked implementation: same cleanup/root-snapshot migration addressed by #1649 | Coordinate completion of the relevant call/temporary and cleanup paths (phases 3–5 contract); establish deterministic bounded traversal and unchanged Data-Tumbler stress success on both backends. This is a scheduling overlap, not a proven API prerequisite. |
| [#1794](https://github.com/fglock/PerlOnJava/issues/1794) Mojo controller/WebSocket lifetimes | Verify within active PR #1816, not a new parallel fix | Integrate the candidate's request/callback/interpreter ownership path; rerun unchanged affected suites on the integrated commit before closing. |
| [#1621](https://github.com/fglock/PerlOnJava/issues/1621) Resource::Silo destruction order | Investigate; suspected overlap, not a proven #1649 dependency | Reduce the destruction-order case and compare against #1649's scope. Coordinate before editing cleanup; require focused and upstream acceptance rather than assuming the rewrite fixes it. |

## Maintenance

The current claim on each issue records execution. Update affected rows when
selecting new work or delivering an unblock condition; do not append status
history here. Preserve full evidence in PRs/artifacts and retain only decisive
references in this snapshot. Closed #1891 is intentionally absent from the queue;
its fix merged in [PR #1893](https://github.com/fglock/PerlOnJava/pull/1893).

[Design and progress](issue-organization.md) ·
[Agent checkpoint rules](../../AGENTS.md#agent-claims-and-concise-checkpoints)
