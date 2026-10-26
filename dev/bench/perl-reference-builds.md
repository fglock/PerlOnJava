# Perl reference builds

The #1863 acceptance baseline is optimized Perl blead. The stable release is a
separate context report and must not replace blead in the acceptance decision.
Both references below were built from exact source commits with the same
threaded `-O3` configuration on macOS arm64.

## Primary: optimized blead

| Field | Value |
| --- | --- |
| Perl version | 5.45.5 |
| Source revision | `83a6c8b26a8823eb966652414864eb07a3d2c6d4` |
| Source archive SHA-256 | `4cfab19e86b9bd5cae70e6c7a66bb68e1b04ada40ea419e7886d95660472f437` |
| Executable SHA-256 | `771ab5e8a0f1eacfa03ef9884d3b351367e3b010c4068b097ac99ff4179ab2f4` |
| Architecture | `darwin-thread-multi-2level` |
| Threading | `useithreads=define`, `usemultiplicity=define` |
| Compiler and optimization | Apple LLVM 21.0.0, `-O3` |
| Configure options | `-des -Dusedevel -Dusethreads -Duseshrplib -Doptimize=-O3` |

## Secondary: stable Perl

| Field | Value |
| --- | --- |
| Perl version | 5.42.2 |
| Source tag and revision | `v5.42.2`, `b62845c7186b0b6a8e4e83419e6b5ef64ceef3ed` |
| Source archive SHA-256 | `6d9a4bc9a2563d0b5430e8e86d862e74c2088ad7cb31792b29ec00b01b0e4bb5` |
| Executable SHA-256 | `4a1f586187dd4df47546c51bb8fc4bcac13d9f6909bd47ce9e10350695ac3e91` |
| Architecture | `darwin-thread-multi-2level` |
| Threading | `useithreads=define`, `usemultiplicity=define` |
| Compiler and optimization | Apple LLVM 21.0.0, `-O3` |
| Configure options | `-des -Dusethreads -Duseshrplib -Doptimize=-O3` |

## Reproduction

Fetch the exact commit from the Perl source repository, export it with `git
archive`, and build outside the source checkout. Use `-Dusedevel` only for the
blead build. Set `PERL_BUILD_PREFIX` to an empty temporary install path and
`PERL_SOURCE_DIR` to the extracted source:

```sh
git -C "$PERL_REPO" archive --format=tar "$PERL_COMMIT" > "$PERL_SOURCE_TAR"
mkdir -p "$PERL_SOURCE_DIR"
tar -xf "$PERL_SOURCE_TAR" -C "$PERL_SOURCE_DIR"
cd "$PERL_SOURCE_DIR"
./Configure -des -Dusethreads -Duseshrplib -Doptimize=-O3 \
  -Dprefix="$PERL_BUILD_PREFIX" -Dinstallprefix="$PERL_BUILD_PREFIX"
nice -n 19 timeout 1800 make
nice -n 19 timeout 1800 make install
```

For blead, add `-Dusedevel` to the `Configure` command. Record `cc --version`,
the complete executable `-V` output, source archive and executable hashes, and
the build logs. Before benchmarking, run the method and closure workers with a
short `seconds` request and confirm checksums `400080000` and `1440000`.

Run the benchmark in separate blead and stable reports. Unset ambient Perl
library overrides so the executable uses the pinned build's own libraries:

```sh
env -u PERL5LIB -u PERL5OPT python3 dev/bench/performance_over_perl.py \
  --perl /path/to/blead-prefix/bin/perl5.45.5 \
  --perl-source-revision 83a6c8b26a8823eb966652414864eb07a3d2c6d4 \
  --jperl-jar target/perlonjava-5.44.1.jar \
  --workloads method_anchor closure_anchor life \
  --output /tmp/performance-over-perl-blead.json
```

Repeat with the stable executable path and `--perl-source-revision v5.42.2`.
The optimized blead report is the primary #1863 parity evidence.
