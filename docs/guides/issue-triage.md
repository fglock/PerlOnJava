# Issue reporting and triage

The minimal workflow from [#1895](https://github.com/fglock/PerlOnJava/issues/1895)
uses a [planning index](../../dev/design/issue-queue.md), explicit dependencies,
and temporary agent claims. It supersedes #1593's proposed large rollout; see
[the implementation design](../../dev/design/issue-organization.md).

## Report an actionable issue

Search open and closed issues for the behavior, diagnostic and affected module.
Describe the failing behavior and include:

- Minimal reproducer, expected/actual output, version or commit, Java, OS and
  system Perl version.
- System Perl, JVM and interpreter results; mark untested combinations.
- For CPAN: distribution/version, run identifier, failing tests, prerequisites,
  durable evidence and affected dependants when known.
- For regressions: last known working revision when available.
- For performance: workload/input size, timing or timeout, resource settings and
  matched comparison conditions. A timeout alone does not establish a hang.

Follow [repository testing requirements](../../AGENTS.md#testing). Route support
and early ideas to [Discussions](https://github.com/fglock/PerlOnJava/discussions)
and vulnerabilities through the [security policy](../../SECURITY.md).

## Classification and planning

The checked-in planning index records `Kind`, priority, next action and readiness
for reviewed work. Every indexed item has one kind:

| Kind | Requested outcome |
|---|---|
| Organizational | Select, coordinate, qualify, report or release work; examples: #1895, port-selection plan #1110, qualification tracker #1646 |
| Project | Deliver software behavior, a bug fix, feature, compatibility port, performance improvement or executable tooling; examples: updater #1114/#1278 and Maven artifact publication #32 |

Classify by outcome. `area:release` can describe either kind. An organizational
tracker links independently actionable project fixes rather than absorbing them.
Issues outside the index remain unreviewed for this convention, not implicitly
project work. Add or revise a row when selecting new work.

Retain existing primary type labels (`bug`, `enhancement`, `documentation`) and
at least one responsible `area:*` on triaged issues. A CPAN discovery is not
necessarily a port defect; use `area:cpan-port` for providers/install/port work.
`area:backend` identifies backend code, not merely failure on both backends.
Use `high-impact` only with named consumers or dependency evidence. Keep
`help wanted`/`good first issue` for work with scope, evidence and review support.
No new label taxonomy or Project board is required.

Priority in the index is a recommendation with rationale: **high** for severe
confirmed failures or broad blockers, **normal** for bounded routine work,
**low** for narrow/exploratory work. Assess workarounds and evidence rather than
issue age alone. Readiness is independent:

| State | Meaning |
|---|---|
| Investigate | Reproduction, root cause, scope or prerequisite remains unresolved |
| Ready | Scope, acceptance and next implementation action are clear; refresh the reproducer on the intended base before changing code |
| Active | Current claim or linked live PR records execution; identify which evidence supports this |
| Blocked | A named prerequisite prevents the recorded next action; state an observable unblock condition |
| Verify | Candidate or reported delivery needs acceptance checks or reconciliation with issue state |

Live claims and PRs override a dated planning snapshot. Recheck before claiming
work; do not maintain a second live status in a Project. If a Project is adopted
later, designate one source of truth and make the index a dated selection aid.
Milestones are optional and represent agreed finite delivery/release scope.

## Dependencies and useful groups

Record `Blocked by #N: <required delivered behavior/milestone>` in the issue or
current checkpoint, with a reference in the index. A related issue is not a
blocker without evidence of a prerequisite. Distinguish confirmed prerequisites
from suspected overlap, and identify independent investigation that can proceed.

#1649 is active. Work requiring its ownership paths waits for those paths and
their acceptance evidence; it need not wait for every later phase if its own
prerequisite is delivered. Do not run a parallel replacement of the same cleanup
paths. An independent bug sharing an area remains independently actionable.

Group issues around shared code, test infrastructure or concrete delivery
prerequisites. A routing group does not authorize a combined fix or concurrent
edits. Create parent/sub-issues only for a finite shared deliverable with scope,
dependency order and completion criteria. Group sizes follow the work.

## Agent work claims

Agents execute work without permanent subsystem owners. GitHub assignees are
optional. The shared default is **one marked current claim comment per agent
attempt on the issue**; its URL is the stable checkpoint location. Read all
marked claims and linked PRs before publishing a new one. Claim only authorized
work and scope it before implementation.

```text
<!-- agent-work-claim -->
State: active | blocked | handed-off | released
Agent/session: <unique attempt identifier>
Scope: #<issue>, intended outcome and overlapping files/subsystems
Work: <branch>, <source commit>, <PR or investigation reference>
Checkpoint: <UTC timestamp>; <current result>
Evidence: <commands/source identity, outcomes, links to full logs>
Blocked by: <issue + required milestone, or none/unknown>
Next: <immediate action>
```

Edit only your own designated comment as state changes; when editing it is
unavailable, append one concise superseding checkpoint with the previous URL.
Never change another agent's record. GitHub comments are advisory: reread after
posting and check for simultaneous or overlapping claims. They do not provide
atomic exclusion. If overlap appears, stop overlapping mutations, preserve work,
and agree a split or handoff. Use separate worktrees for concurrent writers.

For a file-coordinated pool, follow the
[mailbox protocol](../../.agents/skills/file-mailbox-coordination/SKILL.md):
its coordinator, leases and fences govern assignment. Link the issue checkpoint
to that coordination record; do not create a competing scheduler. Local paths
may help local agents but cannot be the only durable evidence in a public issue.

A quiet or old claim prompts reconciliation: inspect PR/branch changes and any
known process or pool state, then contact the agent/coordinator through an
available authorized channel. Require explicit release/handoff or authorized
recovery; elapsed time alone never grants ownership. Record unresolved claims
as blocked when coordination is unavailable. No authority to merge, publish or
expand scope is granted by a claim.

On handoff, identify the successor and source/evidence boundary; the successor
accepts in its own claim before execution. On completion or stopping, release
the claim, identify surviving changes, logs and processes, and record any work
left. A released implementation claim can leave an issue awaiting verification.

## Concise current checkpoints

Use [AGENTS.md's checkpoint rule](../../AGENTS.md#agent-claims-and-concise-checkpoints):
normally 200–400 words, self-contained current state, not a session transcript.
Refresh after a material result and before handoff, context compaction or exit.
Keep full build/test logs as separate evidence; include commands, source identity,
results and durable links rather than raw output. Preserve necessary decisions,
failing-parent evidence and unresolved findings regardless of the word guideline.

For append-only coordination, retain immutable messages and maintain a separate
summary with the last processed message ID. Resume from that summary plus new
messages; replay older entries only to resolve a specific uncertainty. Never
truncate a journal or rewrite another agent's messages to shorten context.

## Completion

A fix links its PR and permanent project regression coverage. Apply the
repository requirements for system Perl validation, unfixed-parent evidence,
both backends, direct engine coverage where applicable, and immutable build
and integration gates. Upstream suites support, but do not replace, focused
coverage. Documentation-only work follows the link-check exception.

Use closing references only for full resolution. Partial deliveries identify
remaining work. Close duplicates only with a confirmed canonical cause and
preserved evidence; do not automatically close confirmed bugs because of age.
Existing reporting and test rules continue to apply without the superseded
mandatory human-owner limit, label migration or scheduled review rollout.
