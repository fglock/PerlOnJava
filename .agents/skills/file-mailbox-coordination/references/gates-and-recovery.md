# Gate evidence, leases and recovery

Read before starting shared gates or assigning a lease; consult the relevant
recovery/transfer/exit section when those transitions occur.

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
