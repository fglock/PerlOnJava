---
name: file-mailbox-coordination
description: Coordinate multiple local coding agents through an append-only file mailbox, including first-agent coordinator election, worker registration, task assignment, polling, heartbeats, leases, fencing, crash recovery, shared-load limits, and safe shutdown. Use when agents in separate terminals or worktrees must collaborate through shared files such as /tmp/*-handoff.md, when joining an existing file-coordinated worker pool, or when creating a new local coordinator/worker pool without native agent-to-agent messaging.
---

# File Mailbox Coordination

Use shared files as an asynchronous control plane for coding agents. Treat the
mailbox as an authoritative event journal, not as informal notes.

## Establish the mailbox

Resolve the mailbox path from the user's prompt or the current task context. Do
not guess between multiple plausible mailboxes. Keep the mailbox outside Git
worktrees so agents can coordinate without creating repository changes or merge
conflicts.

Support either layout:

1. An existing append-only handoff file such as `/tmp/regex-implementation-handoff.md`.
2. A mailbox directory with one immutable message per file. Prefer this layout
   when starting a new pool:

```text
<mailbox>/
├── COORDINATOR.md
├── STATUS.md
├── agents/
├── messages/new/
├── messages/acknowledged/
├── messages/completed/
├── tasks/
├── heartbeats/
└── artifacts/
```

Preserve every message. Never rewrite or delete another agent's entry. For a
single handoff file, append timestamped sections at the physical end of the
file and verify the resulting tail. “Append-only” is about byte position, not
merely preserving old text: inserting a message above EOF is invisible to a
worker whose `tail -F` cursor already passed that position.

Before every write, read the current final unique lines and use them as the
patch anchor. After the write, read the tail again and require the new message
to be the final section. If repeated patch context inserted it earlier,
immediately append one consolidated superseding message at physical EOF; do
not assume the watcher saw the misplaced copy. Prefer a unique current-EOF
message identifier over generic state fields as the append anchor.
For a mailbox directory, create immutable uniquely named message files and use
atomic rename or creation for state transitions.

Read the repository's `AGENTS.md` before inspecting or touching any checkout.
Obey its dirty-tree, Git, testing, timeout, and process-cleanup rules. Give each
worker a separate worktree and branch. Exchange commit SHAs, patches, log paths,
and artifact checksums instead of letting agents edit one worktree concurrently.

## Join the pool

### Elect the first agent as coordinator

Make the first agent that joins a new pool its coordinator. Use an atomic claim
so two simultaneously starting agents cannot both become coordinator.

1. Inspect the mailbox for an active coordinator declaration and heartbeat.
2. If one exists, join as a worker.
3. If the pool is new, atomically create a sidecar coordinator-lock directory,
   for example `<mailbox>.coordinator-lock` or `<mailbox>/coordinator-lock`.
4. If creation succeeds, become coordinator with term `1`, create the initial
   mailbox state, and append a coordinator registration.
5. If creation fails, reread the coordinator state and join as a worker.

Do not self-promote merely because a coordinator is slow. If an existing
coordinator is stale, require explicit user or supervisor authorization before
moving the old lock aside and claiming a higher term.

Record coordinator registration with:

```yaml
agent_id: perlonjava2-coordinator
role: coordinator
coordinator_term: 1
checkout: /absolute/worktree/path
branch: coordination/phase-name
sha: full-commit-sha
joined_at: 2026-08-18T10:00:00+02:00
state: ACTIVE
last_processed_message: 0
```

Maintain exactly one active coordinator term. Include the term in every
assignment and authorization. Make workers ignore messages from older terms.

### Register later agents as workers

Generate a stable unique agent ID. Read the complete mailbox before registering,
then append:

```yaml
agent_id: perlonjava6-regex-tester
role: worker
coordinator_term: 1
checkout: /absolute/worktree/path
branch: wip/phase-name
sha: full-commit-sha
capabilities:
  - focused JVM tests
  - interpreter parity tests
state: AVAILABLE
authority:
  - run focused tests
  - commit only to the owned branch
restrictions:
  - no full build without checking the declared global concurrency limit
  - no merge, push, or shared-branch rebase without authorization
joined_at: 2026-08-18T10:05:00+02:00
last_processed_message: 12
```

Wait for an explicit assignment. Never infer ownership from visible unfinished
work.

## Exchange messages

Give every message a unique monotonic sequence or sortable unique ID. Include:

```yaml
id: msg-000013
from: perlonjava2-coordinator
to: perlonjava6-regex-tester
coordinator_term: 1
type: assignment
task_id: regex-implementation-break-validation
attempt: 1
lease_token: 01J61F...
reply_to: null
created_at: 2026-08-18T10:06:00+02:00
priority: normal
```

Follow the header with a concise Markdown body containing the observed state,
exact SHA, decision or action requested, allowed mutations, expected evidence,
and completion conditions.

Address messages to an agent ID, `coordinator`, or `broadcast`. Acknowledge every
assignment, authorization, resource claim, and blocking question. Record
`last_processed_message` so agents process each message once. Use idempotency
keys for actions that may be retried.

Do not treat silence as approval. After 10–15 minutes without an expected reply,
send a liveness reminder and continue only read-only checks or already-owned
work.

## Coordinate tasks

Make the coordinator:

- maintain the dependency graph and current authoritative state;
- issue assignments with exact inputs and authority boundaries;
- serialize overlapping file ownership and integration decisions;
- keep integration review, conflict resolution, authoritative-state updates,
  regression triage, and publication on the coordinator's serial critical path;
- delegate independent long read-only gates when immutable private inputs and
  the published resource limit permit parallel execution;
- declare bounded shared-resource limits and require workers to self-monitor the
  global active count immediately before starting expensive work;
- validate worker evidence before accepting completion;
- fence expired attempts and reassign recoverable work;
- publish status summaries without erasing the event history.

Make each worker:

1. verify its checkout, branch, SHA, and dirty-tree state;
2. acknowledge and atomically claim the assigned task;
3. work only within the assignment and owned worktree;
4. report questions before making scope-changing decisions;
5. capture complete test output and exact command outcomes;
6. publish commits, logs, artifacts, and remaining risks;
7. release leases and return to `AVAILABLE` or `OFFLINE`.

Use task states:

```text
READY -> CLAIMED -> WORKING -> BLOCKED -> COMPLETED
                     |                     |
                     +-> STALE ------------+
```

Never report completion until required validation and externally observable
state agree with the claim.

### Use bounded execution envelopes

Authorize a cohesive outcome in one assignment instead of requiring a new
coordinator ping after each diagnosis, focused test, build, commit, or push.
Record the exact base, worktree, branch, lease, owned and excluded files,
ordered actions, required gates and logs, resource policy, allowed correction
budget, stop conditions, and post-delivery state. Size the lease around the
cohesive outcome, not one assertion or routine command; renew it during long
gates.

A dependency handoff may self-advance only after the worker verifies the
reported SHA, file scope, and validation against the assignment. Bounded
authority never pre-authorizes scope growth, destructive recovery, merges,
force-pushes, or unrelated external writes.

### Scope commands to new worktrees

`git worktree add` does not change the caller's working directory. Scope every
subsequent Git command with `git -C /exact/new/worktree` and give every build or
test that worktree as its explicit cwd. Verify both checkouts before and after
the first mutation. If a command lands in the wrong checkout, stop and preserve
it under the repository's dirty-tree recovery rules.

## Keep monitoring alive

Remain in monitoring mode until explicitly released, replaced, or authorized to
stop. Poll after 1 minute, then 2 minutes, then every 5 minutes while idle. Add
up to 15 seconds of jitter. Reset to 1 minute whenever relevant activity occurs.

Poll additionally:

- before claiming a task or starting an expensive shared-resource operation;
- before starting an expensive build or corpus run;
- after every long-running command;
- before commit, push, rebase, PR publication, or merge;
- immediately after completing or blocking a task.

Prefer a lightweight external watcher that checks file metadata and resumes the
agent only when content changes. An instruction cannot wake an agent after its
process exits. If the environment lacks recurring monitoring, keep a supervisor
or native scheduled continuation active. Do not claim to be monitoring when no
wake mechanism exists.

Avoid one opaque sleep or wait longer than the environment permits. Preserve the
1/2/5-minute schedule through a recurring monitor or several bounded waits.

## Freeze executable inputs for long gates

Never run acceptance from a JAR, launcher, generated tree, or other executable
input inside another checkout's live `target/` or `build/` directory. A correct
embedded source SHA does not make that path immutable: a later build can replace
the same bytes while the gate is running.

Wait for the owning build process to exit successfully, verify stable hashes
and embedded source identity, then copy every executable input into the task's
private artifact directory. Hash the copies and run only against those copies.
Record those private paths and hashes in `GATE_STARTED`. Invalidate and rerun a
gate if any writer overlapped one of its input paths, even when the gate passed.
Keep private writable test state separate from the frozen executable inputs.

`GATE_STARTED` must include task and lease tokens, immutable SHA, exact command
class, checkout, private input paths and hashes, log path, PID or session, start
time, expected duration, and grace period. Advancing log metadata or output
within that window is evidence of health. Append `GATE_FINISHED` immediately
with exit status, summary, and final log path. If process inspection is
unavailable, record `UNKNOWN`; never infer absence from a failed inspection.

Independent read-only gates may run concurrently only from separate private
frozen inputs and private writable test state, within the resource limit. Never
start a reader while any writer can replace its inputs; timing-sensitive gates
remain isolated.

## Detect crashes with renewable leases

Emit a heartbeat at least every 15 minutes containing:

```yaml
agent_id: perlonjava6-regex-tester
coordinator_term: 1
state: WORKING
activity_kind: GATING
task_id: regex-implementation-break-validation
attempt: 1
lease_token: 01J61F...
checkout: /absolute/worktree/path
branch: wip/phase-name
sha: full-commit-sha
resources: []
last_processed_message: 18
heartbeat_at: 2026-08-18T10:20:00+02:00
```

Every `WORKING` update must name `PREPARING`, `IMPLEMENTING`, `GATING`, or
`INTEGRATING` and observable evidence: the next dependency or artifact;
worktree, base, and first edited file or commit; immutable SHA, process, and
log; or source commit and replay state. Queued work and repeated prose-only
heartbeats are not active implementation. Move through observable transitions
and report the exact failed command when progress stops.

Apply these defaults unless the coordinator records task-specific values:

- Mark an agent `SUSPECT` after 20 minutes without heartbeat.
- Mark it `STALE` after 30 minutes without heartbeat.
- Use a 30-minute renewable task lease.
- Give a long task its declared duration plus at least 15 minutes of grace.
- For expensive shared work, inspect the global active count immediately before
  launch. If the declared limit is reached, continue source review, reducers,
  or other non-build work and poll again later; do not wait idle for a slot.
- Serialize only the check-and-launch transition with an atomic, pool-specific
  launch mutex (for example an atomic lock-directory creation). After acquiring
  it, recount active owner roots plus accepted launch intents, launch only below
  the limit, and wait until the exact owned payload executable is visible with
  its intended cwd before releasing the mutex. A shell, `timeout`, or launcher
  ancestor is not a visibility fence: another worker can otherwise acquire the
  mutex before the payload appears and launch into the same last slot. Do not
  hold the mutex for the duration of the build. This prevents several
  autonomous workers from all observing the same free capacity and
  oversubscribing it simultaneously.
- Record mutex owner, acquisition time, and intended command beside the lock.
  Recover a stale launch mutex only after confirming that its owner and intended
  process are absent; append that recovery to the mailbox before proceeding.
- Announce expensive-work start and drain in the mailbox. Never kill another
  owner's valid process merely to lower the count.
- Require explicit authorization to replace a stale coordinator.

Include coordinator term, attempt number, and a unique lease token in every task
update and result. Once a lease expires or is revoked, invalidate its token. A
late worker must stop, reread the mailbox, register as available, and request a
new assignment. Retain late results as non-authoritative evidence.

Do not let an agent's blocking command suppress heartbeats. Use an external
supervisor heartbeat for long commands when possible.

## Recover a crashed worker

When a worker becomes stale:

1. Append the timeout evidence and revoke its task and resource leases.
2. Fence its attempt by issuing a new attempt and lease token.
3. Identify exact worker-owned processes by PID, command, worktree, and start
   time. Never kill from CPU usage or a broad Java-process match alone.
4. Inspect the abandoned worktree read-only.
5. Preserve dirty work exactly as required by `AGENTS.md`; never use stash,
   reset, clean, checkout, or restore to discard it.
6. Record surviving commits, logs, partial artifacts, and processes.
7. Assign recovery to another worker with explicit instructions about whether
   to validate, continue, or restart the partial attempt.

## Transfer coordinator ownership

Perform coordinator replacement as an explicit handover:

1. Append a release from the old coordinator with active tasks, leases,
   resources, branch heads, and last processed message.
2. Increment the coordinator term.
3. Move the old coordinator lock to a timestamped released or stale name instead
   of deleting historical evidence.
4. Atomically claim a new coordinator lock.
5. Append acceptance by the new coordinator.
6. Require all workers to acknowledge the new term before accepting new work.

If the old coordinator crashed, require the user or supervisor to authorize the
takeover. Never allow two active coordinator terms.

## Leave the pool safely

Before stopping, append final state and artifacts, release every task and
resource lease, and mark the agent `DRAINING` and then `OFFLINE`. Stop monitoring
only after explicit release or when the assignment explicitly authorizes exit.
Preserve the mailbox and historical messages.
