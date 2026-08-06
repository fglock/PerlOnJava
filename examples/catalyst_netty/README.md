# Catalyst on Plack::Handler::Netty

This fixture is an unmodified Catalyst application used by the Catalyst support
acceptance tests. It covers Catalyst action discovery, request parsing, response
handling, exception conversion, logging, PSGI adaptation, and the Netty server.

Install the runtime into an isolated PerlOnJava home, run the direct dispatcher
suite, then start the server with a hard timeout:

```bash
export PERLONJAVA_HOME=/path/to/isolated-home
timeout 180 ./jperl examples/catalyst_netty/t/dispatch.t
CATALYST_NETTY_PORT=5099 timeout 180 \
  ./jperl examples/catalyst_netty/server.pl
PERLONJAVA_HOME="$PERLONJAVA_HOME" \
  examples/catalyst_netty/t/netty_e2e.sh
```

The supported deployment mode is a single PerlOnJava process through
`Plack::Handler::Netty`. Catalyst development reloaders, prefork servers,
daemonization, Perl threads, and Catalyst::Devel are outside this fixture's
scope. JDBC-backed models can use PerlOnJava's DBI/JDBC support independently;
no database model is required by the runtime acceptance gate. See the
[database access guide](../../docs/guides/database-access.md) when adding a
Catalyst model backed by a JDBC driver.
