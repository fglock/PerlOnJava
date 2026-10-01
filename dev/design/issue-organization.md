# GitHub issue organization

Status: proposal. Audit and documentation completed 2026-10-01; GitHub rollout
is pending. This document records the observed backlog and implementation plan.
The proposed operating rules live in the
[issue triage guide](../../docs/guides/issue-triage.md).

Implementation is tracked in
[issue #1593](https://github.com/fglock/PerlOnJava/issues/1593).

## Audit

Read-only inspection used authenticated GitHub REST requests with extended
network access: repository metadata, all pages of open issues and labels, all
milestones, and the `.github` directory. Counts exclude pull requests. They
describe a snapshot, not a continuing guarantee.

| Observation | Snapshot |
|---|---|
| Open issues | 162 |
| Open pull requests included in repository open-item count | 4 |
| Labels defined | 22, including 12 `area:*` labels |
| Issues with no labels | 7 |
| Issues with no area label | 37 |
| Issues with no assignee | 160 |
| Issues with no milestone | 162 |
| Milestones, open or closed | 0 |
| Open issues labelled `bug` / `enhancement` | 128 / 27 |
| Open issues labelled `high-impact` | 9 |
| Open issues labelled `help wanted` / `good first issue` | 0 / 0 |
| Issue authors | 161 from `fglock`, 1 from another author |
| Open issues with no comments | 132 |
| Issue templates in default-branch `.github` listing | None; only `workflows` was listed |

The existing areas already cover parser, runtime, backend, regex, Unicode,
CPAN ports, I/O, performance, memory, platform, warnings, and release work.
The label set has no explicit triage state or priority. Many recent reports
contain system Perl comparisons, reproductions, CPAN versions, and backend
results. Preserve this evidence during migration.

The concentration of reports under one author makes a small, maintainable
workflow appropriate. Unassigned issues and low comment counts do not prove
that work is neglected; they show that ownership and decisions cannot reliably
be inferred from those fields.

Repository metadata enables Projects and Discussions. The Projects GraphQL
read failed because the token lacks `read:project`. Existing Project boards,
fields, automation, and item coverage were not verified. Inspect them before
creating a new board to avoid duplicate planning systems.

Audit sources: [issues](https://github.com/fglock/PerlOnJava/issues),
[labels](https://github.com/fglock/PerlOnJava/labels),
[milestones](https://github.com/fglock/PerlOnJava/milestones), and
[repository configuration](https://github.com/fglock/PerlOnJava/tree/master/.github).

## Practices adapted from large projects

These are recommendations for PerlOnJava, adapted from documented workflows.

| Project | Documented practice | Proposed adaptation |
|---|---|---|
| Rust | Distinct category, area, priority, status, regression, and evidence labels; check underlying causes before declaring duplicates | Keep existing areas; add a small priority and evidence vocabulary; distinguish shared symptoms from shared causes |
| CPython | Separate type, component, OS, topic, and affected-version classification | Classify code ownership independently from where a failure was discovered; collect versions and platform evidence in reports |
| VS Code | Incoming triage, feature-area routing, planned iteration milestones, and fix verification | Weekly inbox review, explicit ownership for active work, small committed release scope, and visible regression evidence |
| GitHub | Parent/sub-issue relationships and filterable Project fields | Use bounded tracking parents and one authoritative planning status |

Sources: [Rust issue triage](https://forge.rust-lang.org/release/issue-triaging.html),
[CPython labels](https://devguide.python.org/triage/labels/),
[CPython triage](https://devguide.python.org/triage/triaging/index.html),
[VS Code issue tracking](https://github.com/microsoft/vscode/wiki/Issue-Tracking),
[VS Code triaging](https://github.com/microsoft/vscode/wiki/Issues-Triaging),
[GitHub sub-issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/adding-sub-issues),
and [Project single select fields](https://docs.github.com/en/issues/planning-and-tracking-with-projects/understanding-fields/about-single-select-fields).

## Concrete backlog examples

These are review candidates based on issue descriptions, not fresh runtime
diagnoses or decisions to merge issues.

| Issues | Proposed review |
|---|---|
| [#1584](https://github.com/fglock/PerlOnJava/issues/1584), [#1524](https://github.com/fglock/PerlOnJava/issues/1524) | Add parser area after confirming ownership; #1524 also needs a primary type |
| [#1541](https://github.com/fglock/PerlOnJava/issues/1541) | Add performance area; retain benchmark evidence and distinguish timeout policy from a runtime defect |
| [#1579](https://github.com/fglock/PerlOnJava/issues/1579) | Review whether the requested deliverable is documentation or a runtime change before choosing primary type |
| [#1588](https://github.com/fglock/PerlOnJava/issues/1588), [#1556](https://github.com/fglock/PerlOnJava/issues/1556), [#1487](https://github.com/fglock/PerlOnJava/issues/1487) | Consider a Unicode regex compatibility parent; investigate alias lookup versus character-class composition separately |
| [#1502](https://github.com/fglock/PerlOnJava/issues/1502), [#1172](https://github.com/fglock/PerlOnJava/issues/1172) | Compare restricted-hash scope and implementation evidence before deciding whether one supersedes the other |
| [#1585](https://github.com/fglock/PerlOnJava/issues/1585), [#1586](https://github.com/fglock/PerlOnJava/issues/1586) | Cross-link shared FFM/value-conversion dependencies; keep Inline::C feasibility and Perl C API compatibility acceptance criteria explicit |
| [#1592](https://github.com/fglock/PerlOnJava/issues/1592) | Classify the standalone regex library proposal as a bounded tracking effort with feasibility, API, conformance, and release children |
| [#1577](https://github.com/fglock/PerlOnJava/issues/1577) | Retain documented broad impact; determine scheduling priority independently |

The first migration batch should cover the 7 unlabelled issues, then the other
30 lacking areas. Choose priority from evidence and next-release goals rather
than mechanically ranking issue age or applying high priority to all CPAN bugs.

## Rollout

### Phase 1: Document the proposal

Publish the audit, label definitions, reporting requirements, triage workflow,
completion criteria, and rollout plan. Link the guide from contributor docs.

### Phase 2: Normalize classification

Prepare a reviewable mapping of issue number, current labels, proposed labels,
confidence, and reason. Add the proposed labels defined in the guide with
descriptions and consistent colors by dimension. Preserve existing label names
and issue evidence. Confirm current state before applying each change; report
concurrent changes instead of overwriting them.

Classify the 37 issues lacking areas and review the type of the 7 unlabelled
issues. Put uncertain cases into `needs-triage`; do not mark the whole existing
backlog untriaged solely because the label is new. Set priority only after a
human decision or sufficiently clear evidence. Preserve the migration mapping
so erroneous changes can be reversed individually.

### Phase 3: Make planning visible

Inspect existing Projects with access that includes `read:project`; reuse a
suitable board or create one. Adopt the guide's Status field and saved views.
Use repository labels for priority and assignees for owners. Add a release
milestone only when its scope and delivery criteria have been selected. Review
the proposed active-work limit with current maintainers.

Review the example clusters before adding parent relationships. Choose finite
goals, attach existing issues, and add missing children only when they represent
independently actionable work. An issue linked to a design document remains
the work record; the design document remains the architectural explanation.

### Phase 4: Improve intake and maintain the queue

Add issue forms for bugs, CPAN compatibility, performance, and scoped feature
proposals using the reporting fields in the guide. Forms should accept unknown
backend results rather than demanding costly tests from every reporter. Add
links to Discussions and the security policy.

After a manual pilot, automate adding `needs-triage` on new issues and importing
issues into the Project. Keep automated classification suggestions reviewable.
Do not automate priority decisions, duplicate closure, or stale closure of
confirmed bugs. Extend tester-to-issue deduplication around confirmed root
behavior and existing canonical issues; retain every affected distribution's
evidence. Follow the existing CPAN classification skill before filing failures.

## Measures of improvement

Record a baseline again at rollout because the live backlog changes. Review
these monthly for the first three months:

- Incoming issues awaiting triage and age of the oldest actionable report.
- Fraction of triaged issues with one primary type and at least one area.
- Fraction of active items with an owner, next action, and linked work.
- Ready items without unresolved reproduction or scope questions.
- Release milestone scope delivered, deferred, and reasons for deferral.
- Confirmed bug fixes with linked permanent regression coverage and both
  backend results.
- Contributor-ready issues with code pointers and an available reviewer.

Aim for complete classification of triaged work and complete ownership of
active work. Use observations from the first month to set a sustainable triage
response target. The number of issues closed is insufficient as a success
measure because valid unresolved bugs must remain discoverable.

## Progress tracking

### Current status: Phase 1 completed; GitHub rollout pending

- [x] Phase 1: Audit and documentation (2026-10-01).
  - Read issues, labels, milestones, repository capabilities, and template listing.
  - Compared Rust, CPython, and VS Code practices using primary documentation.
  - Added this design document and `docs/guides/issue-triage.md`; linked the guide
    from `docs/README.md` and `CONTRIBUTING.md`.
  - Recommended retaining existing areas, separate priority/evidence labels,
    one Project Status field, and bounded parent issues.
  - Linked the triage guide from 12 relevant debugging, profiling, and porting
    skills; opened issue #1593 with rollout tasks and acceptance criteria.
- [ ] Phase 2: Label definitions and issue classification migration.
- [ ] Phase 3: Project, milestones, and reviewed issue relationships.
- [ ] Phase 4: Issue forms, manual pilot, and limited automation.

### Next steps

1. Review the proposed conventions and prepare the issue-by-issue migration map.
2. Verify the existing Projects setup with `read:project` access.
3. Apply classification changes, then select a small release scope.
4. Pilot weekly triage before adding intake automation.

### Open questions and blockers

- Existing Project configuration is unverified because of token scope.
- Which release should receive the first committed milestone?
- Who will own weekly triage and review contributor-ready issues?
- Which example clusters warrant finite parents after scope and root-cause review?

## Related material

- [Issue triage guide](../../docs/guides/issue-triage.md)
- [Project roadmap](../../docs/about/roadmap.md)
- [CPAN port prioritization](cpan-port-prioritization.md)
- [Standalone regex library RFC](perl-regex-library-rfc.md)
- [CPAN failure classification skill](../../.agents/skills/classify-cpan-failures/SKILL.md)
