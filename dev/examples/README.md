# Examples

Sample Perl code demonstrating PerlOnJava features and capabilities.

## Purpose

This directory contains a mix of **working examples** and **feature concept
sketches** for design exploration. Not all files are guaranteed to run —
some are prototypes written to explore API ideas before implementation.

## Contents by Category

### GUI
| File | Description |
|------|-------------|
| `GuiHelloWorld.pl` | Basic Swing GUI via Java interop |
| `GuiHelloWorld2.pl` | GUI variant with additional controls |
| `JavaGui.pm` | Shared GUI helper module |

### Networking
| File | Description |
|------|-------------|
| `GameServer.pl` | TCP game server |
| `GameClient.pl` | TCP game client |
| `Http.pl` | Simple HTTP client |
| `http_cookie.pl` | HTTP cookie handling |

### Concurrency
| File | Description |
|------|-------------|
| `TestThread.pl` | Basic threading via Java threads |
| `TestThread2.pl` | Thread variant |
| `TestThreadEval.pl` | Threads with eval |
| `TestConcurrent.pl` | Concurrent execution test |

### Type System / OOP
| File | Description |
|------|-------------|
| `Types.pl` | Type system exploration (concept) |
| `Types2.pl` | Type system variant |
| `class.pl` | `class` keyword example |
| `override.pl` / `override.java` | Method override examples |
| `core_global_override.pl` | Overriding CORE:: subroutines |
| `core_redefine.pl` | Redefining core builtins |

### Miscellaneous
| File | Description |
|------|-------------|
| `CallerTest.pl` / `CallerTest.pm` | `caller` built-in tests |
| `CalendarEval.pl` / `Calendar.pl` | Calendar with eval STRING |
| `Exporter_test.pl` | Exporter module usage |
| `Script.java` / `Sample.java` | Java-side scripting API examples |
| `Serialize.java` | Serialization example |
| `TypedIterator.pl` | Iterator with type annotations concept |
| `mapreduce.pl` / `mapreduce2.pl` / `mapreduce3.pl` | MapReduce patterns |

## Running Examples

```bash
# Most working examples
./jperl dev/examples/Http.pl

# Examples using Java interop
./jperl dev/examples/GuiHelloWorld.pl
```

## See Also

- `dev/sandbox/` — Quick proof-of-concept scripts (less polished)
- `dev/design/` — Design documents explaining features shown here
