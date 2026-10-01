# Issue reporting and triage

Status: proposed workflow, 2026-10-01. Existing labels remain usable; the new
labels and planning conventions below require rollout. See the
[audit and implementation plan](../../dev/design/issue-organization.md).

## Reporting an actionable issue

Search open and closed issues for the behavior, diagnostic, and affected module.
Use a title describing the failing behavior. Include:

- A minimal reproducer, expected output, and actual output or diagnostic.
- PerlOnJava version or commit, Java version, OS, and system Perl version.
- System Perl, JVM backend, and interpreter backend results. Mark untested
  combinations explicitly.
- For CPAN failures: distribution/version, tester run ID, failing test and
  assertion, prerequisites, full log location, and affected dependants.
- For regressions: the last known working version or commit, if available.
- For performance: input size, wall time, timeout, resource settings, and
  comparison conditions. Distinguish a slow run from a confirmed hang.

Follow [testing requirements](../../AGENTS.md#testing) when collecting evidence,
including timeouts and capturing full output. Use
[Discussions](https://github.com/fglock/PerlOnJava/discussions) for support and
early ideas; move an agreed, actionable proposal into an issue. Follow the
[security policy](../../SECURITY.md) for vulnerability reports.

## Labels

Each triaged issue has one primary type and at least one area. Add other areas
only when evidence identifies shared ownership or affected code. A CPAN
discovery alone does not imply a defect in a module port.

| Dimension | Labels | Rule |
|---|---|---|
| Primary type | `bug`, `enhancement`, `documentation`, proposed `maintenance`, proposed `tracking` | Choose one; use `tracking` for a bounded parent with child issues |
| Area | Existing `area:*` labels | Choose the code responsible for the behavior; retain useful secondary areas |
| Triage | Proposed `needs-triage`, `needs-info`, `needs-repro` | Remove `needs-triage` after classification and next action are recorded; information requests may remain |
| Priority | Proposed `priority:critical`, `priority:high`, `priority:normal`, `priority:low` | Choose one for accepted actionable work; unset means undecided |
| Evidence | Proposed `regression`, `backend-parity` | Apply after confirming a previously working behavior or a backend difference |
| Impact | Existing `high-impact` | Document broad ecosystem impact with named consumers or dependency evidence |
| Participation | Existing `help wanted`, `good first issue` | Supply a reproducer, code pointers, acceptance criteria, and an available reviewer |

Priority records scheduling urgency. `high-impact` records ecosystem reach.
Critical means urgent severe failure such as data corruption or a release
blocker; high means a confirmed regression, common crash/hang, or substantial
compatibility blocker; normal means accepted routine work; low means a narrow
edge case or exploratory improvement. Record the rationale, including
workarounds and confidence. A high dependant count alone does not establish
critical priority.

Keep `area:backend` for backend implementation defects. Use `backend-parity`
for a verified difference between JVM and interpreter execution. Use
`area:cpan-port` for provider/shim/port/install compatibility work, and the
responsible compiler/runtime area for language defects found through CPAN.

## Triage and ownership

1. Check whether the report belongs here and whether it reproduces under
   system Perl and current PerlOnJava. Request the specific missing evidence.
2. Search for a common root cause. Link potentially related issues before
   deciding they are duplicates.
3. Set primary type, areas, evidence labels, and priority when justified.
4. Record the next action: minimize, investigate, design, implement, or verify.
5. Remove `needs-triage` and put accepted work in the planning backlog.

Assignment means someone has accepted responsibility for the next action.
Unassigned backlog issues are valid. Active work must have an owner and a
linked branch/PR or investigation note. Start with at most two active issues
per owner; review the limit after a month.

Review the incoming queue weekly and the accepted backlog monthly. Review
regressions, critical/high priority items, and missing evidence first. Revisit
old reports against the current version before closing. Confirmed compatibility
bugs stay open while actionable; elapsed time alone is not a closure reason.

## Planning

Use one GitHub Project for planning, with a single authoritative `Status` field:
`Inbox`, `Backlog`, `Ready`, `In progress`, `In review`, `Done`.

`Ready` means the reproducer or feature scope, acceptance criteria, and next
action are clear. Add a `Blocked by` reference when a dependency prevents
progress; leave the item in its current status. Use labels for priority so that
issue searches and the Project agree without maintaining a second priority
field. Use assignees for ownership.

Milestones represent a committed release or a bounded delivery with completion
criteria. Backlog items may have no milestone. Select a small release scope;
record the reason when moving unfinished work to a later milestone.

Create Project views for Inbox, Ready work by priority, active work by owner,
release scope, and work grouped by area or parent issue. If Project access is
unavailable, use issue searches and a bounded tracking issue until it is set up.

## Related failures and tracking issues

Keep one issue per independently fixable behavior. Add multiple CPAN
observations to the same issue when they demonstrate the same root cause.
Preserve distribution versions, test identities, and logs when consolidating.

Use parent issues and sub-issues for finite efforts such as Unicode property
compatibility or an XS feasibility study. A parent contains the goal, design
link, child issues, dependency order, acceptance criteria, and completion rule.
Close it when the agreed scope is delivered. Cross-link related work that does
not belong under one parent.

## Completion

A fix links its PR and permanent project regression test. Follow the repository
requirements for system Perl validation, failure evidence on the unfixed parent,
both backends, direct engine coverage when applicable, and integration gates.
Retain upstream CPAN results as supporting evidence.

Use a closing reference only when the PR resolves the full issue. Partial work
uses a related reference and leaves the remaining scope explicit. A merged fix
is complete on the development branch; the milestone identifies its intended
release. Close duplicates with a link to the canonical issue and close
unsupported requests with a reason. Avoid automatic closure of known bugs.

## Useful searches

Paste these into GitHub Issues after the proposed labels exist:

| Queue | Query |
|---|---|
| Incoming | `repo:fglock/PerlOnJava is:issue is:open label:needs-triage` |
| Missing all labels | `repo:fglock/PerlOnJava is:issue is:open no:label` |
| Regressions | `repo:fglock/PerlOnJava is:issue is:open label:regression` |
| High priority | `repo:fglock/PerlOnJava is:issue is:open label:priority:high` |
| Regex work | `repo:fglock/PerlOnJava is:issue is:open label:area:regex` |
| Community work | `repo:fglock/PerlOnJava is:issue is:open label:"help wanted" no:assignee` |

## Related guidance

- [Contributing](../../CONTRIBUTING.md)
- [CPAN prioritization](../../dev/design/cpan-port-prioritization.md)
- [CPAN failure classification skill](../../.agents/skills/classify-cpan-failures/SKILL.md)
- [Regex debugging skill](../../.agents/skills/debug-regex-engine/SKILL.md)
