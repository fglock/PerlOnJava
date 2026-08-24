# Catalyst on Plack::Handler::Netty

This example is an unmodified Catalyst application for the Netty PSGI server.
It demonstrates Catalyst action discovery, request parsing, response handling,
exception conversion, logging, PSGI adaptation, and the Netty server.

Install the runtime into an
[isolated PerlOnJava home](../../docs/guides/using-cpan-modules.md#isolated-installations),
then start the server with a hard timeout:

```bash
export PERLONJAVA_HOME=/path/to/isolated-home
CATALYST_NETTY_PORT=5099 timeout 180 \
  ./jperl examples/catalyst_netty/server.pl
```

While the server is running, exercise its routes from another terminal:

```bash
curl http://127.0.0.1:5099/
curl http://127.0.0.1:5099/local
curl http://127.0.0.1:5099/path/example
curl http://127.0.0.1:5099/api/item/example
```

The supported deployment mode is a single PerlOnJava process through
`Plack::Handler::Netty`. Catalyst development reloaders, prefork servers,
daemonization, Perl threads, and Catalyst::Devel are outside this fixture's
scope. JDBC-backed models can use PerlOnJava's DBI/JDBC support independently;
no database model is required by this example. See the
[database access guide](../../docs/guides/database-access.md) when adding a
Catalyst model backed by a JDBC driver.
