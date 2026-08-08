# PAGI on PerlOnJava

This is a minimal asynchronous HTTP application running on the reference
`PAGI::Server`. The application uses native `async`/`await` syntax and returns
one response for `/` plus a `404` for other paths.

## Install the server

From the PerlOnJava repository root:

```bash
./jcpan -T PAGI::Server
```

`-T` skips distribution tests that exercise Perl process forking, which
PerlOnJava does not implement. The single-process HTTP server does not require
`fork`.

## Run it

```bash
./jperl examples/pagi/server.pl
```

In another terminal:

```bash
curl -i http://127.0.0.1:5000/
curl -i http://127.0.0.1:5000/missing
```

Set `PAGI_HOST` or `PAGI_PORT` to select another listener:

```bash
PAGI_PORT=8080 ./jperl examples/pagi/server.pl
```

## Smoke test

The smoke test invokes the PAGI application contract directly and verifies its
response events:

```bash
./jperl examples/pagi/smoke-test.pl
```
