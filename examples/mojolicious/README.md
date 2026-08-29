# Mojolicious live task board

A small single-process Mojolicious application for UAT. It serves an HTML task
board, a JSON API, and a timer-driven chunked activity feed. It is intentionally
loopback-only by default and deliberately has no persistent state.

## Install Mojolicious

From the PerlOnJava repository root, install into PerlOnJava's private CPAN
home (normally `~/.perlonjava`):

```bash
./jcpan -i Mojolicious
```

If your environment does not automatically add the private installation to
`@INC`, set `PERL5LIB` explicitly before running the example:

```bash
export PERLONJAVA_HOME="${PERLONJAVA_HOME:-$HOME/.perlonjava}"
export PERL5LIB="$PERLONJAVA_HOME/lib${PERL5LIB:+:$PERL5LIB}"
```

The project must have been built with `make` so `./jperl` uses the current
PerlOnJava JAR. Mojolicious is pure Perl; no CPAN source patches are required.

## Run

```bash
MOJO_LISTEN=http://127.0.0.1:3000 timeout 180 \
  ./jperl examples/mojolicious/task_board.pl daemon
```

In another terminal:

```bash
curl http://127.0.0.1:3000/health
curl http://127.0.0.1:3000/api/tasks
curl --no-buffer http://127.0.0.1:3000/activity
```

The application keeps tasks in memory, so it is suitable for UAT rather than
production deployment. Run the same command with `./jperl --interpreter` to
exercise the interpreter backend.

Mojolicious developer modes that require real process `fork`/prefork servers
(such as hypnotoad and prefork) are not supported by PerlOnJava. Use the
single-process daemon mode shown above; pseudo-fork support is tracked in
issue #1144.
