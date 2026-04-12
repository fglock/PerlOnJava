#!/usr/bin/env perl
#
# cpan_random_tester.pl - Random CPAN Module Tester for PerlOnJava
#
# Picks random modules from the CPAN index, installs them with jcpan
# (which also installs and tests dependencies), and maintains a
# persistent Markdown report of pass/fail results.
#
# Key features:
#   - Every module tested during a run (including dependencies) is
#     recorded separately, so each run yields many data points.
#   - Results are updated on re-test: if a dep that previously failed
#     now passes (because its own deps got installed), the record moves
#     from FAIL → PASS.
#   - PASS results record the git commit hash so that PASS→FAIL
#     regressions can be bisected.
#
# Usage:
#   perl dev/tools/cpan_random_tester.pl                   # Test 10 random modules
#   perl dev/tools/cpan_random_tester.pl --count 50        # Test 50 random modules
#   perl dev/tools/cpan_random_tester.pl --report-only      # Regenerate .md from .dat
#   perl dev/tools/cpan_random_tester.pl --timeout 120      # 2 min timeout per module
#   perl dev/tools/cpan_random_tester.pl --install           # Install mode (deps stay)
#
# Output:
#   - dev/cpan-reports/cpan-compatibility.md        (human-readable report)
#   - dev/cpan-reports/cpan-compatibility-pass.dat  (machine-readable pass list)
#   - dev/cpan-reports/cpan-compatibility-fail.dat  (machine-readable fail list)
#   - dev/cpan-reports/cpan-compatibility-skip.dat  (machine-readable skip list)
#   - Per-module logs to /tmp/cpan_random_logs/
#
# Run with `perl` (not jperl) because this script uses fork and backticks.
#
# Prerequisites:
#   - Build PerlOnJava first: make dev
#   - CPAN index must exist: ~/.cpan/sources/modules/02packages.details.txt.gz
#     (run `./jcpan` once interactively if missing)

use strict;
use warnings;
use File::Basename;
use File::Spec;
use File::Path qw(make_path);
use Getopt::Long;
use POSIX qw(strftime WNOHANG);

# ──────────────────────────────────────────────────────────────────────
# Paths
# ──────────────────────────────────────────────────────────────────────
my $script_dir   = dirname(File::Spec->rel2abs($0));
my $project_root = File::Spec->catdir($script_dir, '..', '..');
my $jcpan        = File::Spec->catfile($project_root, 'jcpan');
my $report_dir   = File::Spec->catdir($project_root, 'dev', 'cpan-reports');
my $report_md    = File::Spec->catfile($report_dir, 'cpan-compatibility.md');
my $pass_dat     = File::Spec->catfile($report_dir, 'cpan-compatibility-pass.dat');
my $fail_dat     = File::Spec->catfile($report_dir, 'cpan-compatibility-fail.dat');
my $skip_dat     = File::Spec->catfile($report_dir, 'cpan-compatibility-skip.dat');
my $log_dir      = '/tmp/cpan_random_logs';

# CPAN package index
my $packages_gz  = glob('~/.cpan/sources/modules/02packages.details.txt.gz');

# ──────────────────────────────────────────────────────────────────────
# CLI options
# ──────────────────────────────────────────────────────────────────────
my $count       = 10;
my $timeout     = 300;
my $report_only = 0;
my $install     = 0;      # --install: use jcpan (install) instead of jcpan -t
my $help        = 0;
my $seed;

GetOptions(
    'count|n=i'    => \$count,
    'timeout=i'    => \$timeout,
    'report-only'  => \$report_only,
    'install'      => \$install,
    'seed=i'       => \$seed,
    'help|h'       => \$help,
) or die "Error in command line arguments\n";

if ($help) {
    print_usage();
    exit 0;
}

# ──────────────────────────────────────────────────────────────────────
# Setup
# ──────────────────────────────────────────────────────────────────────
make_path($report_dir) unless -d $report_dir;
make_path($log_dir)    unless -d $log_dir;

srand($seed) if defined $seed;

# Get current git commit hash (recorded with PASS results for bisecting)
my $git_commit = `git -C '$project_root' rev-parse --short HEAD 2>/dev/null`;
chomp $git_commit;
$git_commit ||= 'unknown';

# Load existing results
my %pass_modules = load_dat($pass_dat);
my %fail_modules = load_dat($fail_dat);
my %skip_modules = load_dat($skip_dat);

if ($report_only) {
    generate_report();
    print "Report updated: $report_md\n";
    exit 0;
}

# ──────────────────────────────────────────────────────────────────────
# Load CPAN index and extract distribution-level module names
# ──────────────────────────────────────────────────────────────────────
print "Loading CPAN package index...\n";
die "CPAN index not found at $packages_gz\nRun ./jcpan once to download it.\n"
    unless -f $packages_gz;

my %dist_to_module;
my %module_to_dist;

open my $gz, '-|', "gzcat '$packages_gz'" or die "Cannot read $packages_gz: $!\n";
while (<$gz>) {
    next if /^\s*$/ || /^[A-Z][a-z-]+:/ || /^\s/;  # skip header
    chomp;
    my ($module, $version, $dist) = split /\s+/, $_, 3;
    next unless $module && $dist;

    next if $module =~ /^[a-z]/;          # pragmas
    next if $module =~ /^perl$/i;
    next if $module =~ /^Acme::/;         # joke modules
    next if $module =~ /^Bundle::/;
    next if $module =~ /^Task::/;

    $module_to_dist{$module} = $dist;
    $dist_to_module{$dist} //= $module;
}
close $gz;

my @all_modules = sort values %dist_to_module;
printf "Loaded %d unique distributions (%d total packages)\n",
    scalar @all_modules, scalar keys %module_to_dist;

# Remove already-tested modules (only PASS — re-test FAILs in case deps are now available)
my @candidates;
for my $mod (@all_modules) {
    next if $pass_modules{$mod};
    next if $skip_modules{$mod};
    push @candidates, $mod;
}
printf "Candidates (not yet passing): %d\n", scalar @candidates;

if (!@candidates) {
    print "All modules have been tested! Use --report-only to regenerate the report.\n";
    generate_report();
    exit 0;
}

# ──────────────────────────────────────────────────────────────────────
# Randomly select modules to test
# ──────────────────────────────────────────────────────────────────────
my @selected;
if ($count >= scalar @candidates) {
    @selected = @candidates;
} else {
    my @pool = @candidates;
    for my $i (0 .. $count - 1) {
        my $j = $i + int(rand(scalar(@pool) - $i));
        @pool[$i, $j] = @pool[$j, $i];
    }
    @selected = @pool[0 .. $count - 1];
}
@selected = sort @selected;

printf "\nTesting %d randomly selected modules (timeout: %ds, commit: %s):\n",
    scalar @selected, $timeout, $git_commit;
print "=" x 70, "\n\n";

# ──────────────────────────────────────────────────────────────────────
# Test each module and harvest results for all deps too
# ──────────────────────────────────────────────────────────────────────
my $target_count = 0;
my $new_pass     = 0;
my $new_fail     = 0;
my $upgraded     = 0;   # FAIL→PASS transitions

for my $module (@selected) {
    $target_count++;
    my $mode = $install ? '' : '-t';
    printf "[%d/%d] jcpan %s %s\n", $target_count, scalar @selected, $mode, $module;

    my $start = time();
    my $cmd = $install ? "$jcpan $module" : "$jcpan -t $module";

    my ($output, $timed_out) = run_with_timeout($cmd, $timeout);

    my $elapsed = sprintf('%.1f', time() - $start);

    save_log($module, $output);

    # Parse ALL module results from the output (target + deps)
    my @all_results = parse_all_module_results($output);

    # If nothing parsed, check for special cases before recording failure
    if (!@all_results) {
        if ($timed_out) {
            push @all_results, {
                module => $module, status => 'FAIL',
                tests => undef, pass_count => undef,
                error => "TIMEOUT (>${timeout}s)",
            };
        } elsif ($output =~ /\Q$module\E is up to date/) {
            # Already installed, jcpan skipped it — not a failure
            printf "  (already installed, skipped)\n\n";
            next;
        } else {
            # Check for PerlOnJava-specific errors in the raw output
            my $error = 'No parseable output';
            if ($output =~ /Too many registers/) {
                $error = 'PerlOnJava: register limit exceeded';
            } elsif ($output =~ /StackOverflowError/) {
                $error = 'StackOverflowError';
            } elsif ($output =~ /OutOfMemoryError/) {
                $error = 'OutOfMemoryError';
            } elsif ($output =~ /Can't locate (\S+\.pm)/m) {
                $error = "Missing: $1";
            } elsif ($output =~ /Syntax error[^\n]*/mi) {
                $error = 'Syntax error';
            }
            push @all_results, {
                module => $module, status => 'FAIL',
                tests => undef, pass_count => undef,
                error => $error,
            };
        }
    }

    # Record each discovered module
    for my $r (@all_results) {
        my $mod = $r->{module};
        next unless $mod;

        $r->{date} = strftime('%Y-%m-%d', localtime);

        if ($r->{status} eq 'PASS') {
            $r->{git_commit} = $git_commit;

            # Was it previously a FAIL? That's an upgrade.
            if ($fail_modules{$mod}) {
                delete $fail_modules{$mod};
                $upgraded++;
                printf "  ^ UPGRADE %-38s FAIL -> PASS", $mod;
            } elsif ($pass_modules{$mod}) {
                # Already known PASS — update date/commit silently
                $pass_modules{$mod} = $r;
                next;
            } else {
                $new_pass++;
                printf "  + PASS    %-38s", $mod;
            }
            printf " (%s subtests)", $r->{tests} if $r->{tests};
            print "\n";
            $pass_modules{$mod} = $r;

        } elsif ($r->{status} eq 'SKIP') {
            next if $skip_modules{$mod};
            $skip_modules{$mod} = $r;
            printf "  - SKIP    %-38s (%s)\n", $mod, $r->{reason} // '';

        } else {
            # Don't downgrade a PASS to FAIL (would need --retest-pass)
            next if $pass_modules{$mod};
            # Already a known FAIL — update silently
            if ($fail_modules{$mod}) {
                $fail_modules{$mod} = $r;
                next;
            }
            $new_fail++;
            $fail_modules{$mod} = $r;
            printf "  - FAIL    %-38s", $mod;
            printf " (%s/%s)", $r->{pass_count} // '?', $r->{tests} if $r->{tests};
            if ($r->{error}) {
                my $err = $r->{error};
                $err = substr($err, 0, 45) . '...' if length($err) > 48;
                printf " [%s]", $err;
            }
            print "\n";
        }
    }

    printf "  (%ss, %d modules in output)\n\n", $elapsed, scalar @all_results;

    # Save after each target (crash-safe)
    save_dat($pass_dat, \%pass_modules);
    save_dat($fail_dat, \%fail_modules);
    save_dat($skip_dat, \%skip_modules);
}

# ──────────────────────────────────────────────────────────────────────
# Summary
# ──────────────────────────────────────────────────────────────────────
print "=" x 70, "\n";
printf "This run:   %d targets | +%d pass | +%d fail | %d upgraded (FAIL->PASS)\n",
    $target_count, $new_pass, $new_fail, $upgraded;
printf "Cumulative: %d pass | %d fail | %d skip | %d total\n",
    scalar keys %pass_modules, scalar keys %fail_modules,
    scalar keys %skip_modules,
    scalar(keys %pass_modules) + scalar(keys %fail_modules) + scalar(keys %skip_modules);

generate_report();
print "\nReport: $report_md\n";
print "Logs:   $log_dir/\n";


# ══════════════════════════════════════════════════════════════════════
# Output parser — extracts per-module results from full jcpan output
#
# CPAN.pm output is NOT contiguous per module: it starts installing
# module A, discovers a dep B, switches to B, finishes B, then comes
# back to A.  So we use two strategies:
#
#  1) "Running make test for AUTHOR/Dist-Name-Ver.tar.gz" blocks —
#     these ARE contiguous and contain Files=/Result:/make test lines.
#     We map dist-name back to the module name.
#
#  2) "Running install for module 'Foo::Bar'" + configure failures —
#     catches modules that never reached the test phase.
# ══════════════════════════════════════════════════════════════════════

sub parse_all_module_results {
    my ($output) = @_;
    my @results;
    my %seen;   # module => 1, to avoid duplicates

    # --- Pass 1: map dist paths to module names ---
    # "Running install for module 'Foo::Bar'" is followed later by
    # "Configuring A/AU/AUTHOR/Foo-Bar-1.0.tar.gz with ..."
    my %dist_to_mod;   # "Foo-Bar-1.0" => "Foo::Bar"
    my $last_mod;
    for my $line (split /\n/, $output) {
        if ($line =~ /Running (?:test|install) for module '([^']+)'/) {
            $last_mod = $1;
        }
        # "Configuring A/AU/AUTHOR/Dist-Name-1.0.tar.gz with ..."
        if ($last_mod && $line =~ m{Configuring \S+/(\S+)\.tar\.gz}) {
            $dist_to_mod{$1} = $last_mod;
        }
    }

    # --- Pass 2: parse "Running make test for ..." blocks ---
    # These are contiguous and contain the actual test results.
    # Format: "Running make test for AUTHOR/Dist-Name-1.0.tar.gz"
    # (also: "Running Build test for ...")
    my @test_blocks;
    {
        my $cur_dist;
        my $cur_text = '';
        for my $line (split /\n/, $output) {
            if ($line =~ m{Running (?:make|Build) test for \S+/(\S+)\.tar\.gz}) {
                if ($cur_dist) {
                    push @test_blocks, { dist => $cur_dist, text => $cur_text };
                }
                $cur_dist = $1;
                $cur_text = "$line\n";
            } elsif ($cur_dist) {
                $cur_text .= "$line\n";
                # End block on the definitive "make test -- OK/NOT OK" line
                if ($line =~ /(?:make|Build) test -- (?:OK|NOT OK)/) {
                    push @test_blocks, { dist => $cur_dist, text => $cur_text };
                    $cur_dist = undef;
                    $cur_text = '';
                }
            }
        }
        # Save final block if still open
        if ($cur_dist) {
            push @test_blocks, { dist => $cur_dist, text => $cur_text };
        }
    }

    for my $block (@test_blocks) {
        my $dist = $block->{dist};
        my $text = $block->{text};
        my $mod  = $dist_to_mod{$dist};

        # If we couldn't map dist→module, derive from dist name
        unless ($mod) {
            ($mod = $dist) =~ s/-[\d.]+$//;   # strip version
            $mod =~ s/-/::/g;                  # Foo-Bar → Foo::Bar
        }
        next if $seen{$mod}++;

        my %r = (
            module     => $mod,
            status     => 'UNKNOWN',
            tests      => undef,
            pass_count => undef,
            error      => '',
            reason     => '',
        );

        my $total_tests   = 0;
        my $subtests_fail = 0;

        if ($text =~ /Files=\d+, Tests=(\d+)/) {
            $total_tests = $1;
        }
        while ($text =~ /Failed\s+(\d+)\/(\d+)\s+subtests/g) {
            $subtests_fail += $1;
        }

        if ($text =~ /All tests successful/ || $text =~ /Result: PASS/) {
            $r{status}     = 'PASS';
            $r{tests}      = $total_tests || undef;
            $r{pass_count} = $total_tests || undef;
            push @results, \%r;
            next;
        }

        if ($text =~ /Result: FAIL/ || $text =~ /(?:make|Build) test -- NOT OK/) {
            $r{status} = 'FAIL';
            if ($total_tests > 0) {
                $r{tests}      = $total_tests;
                $r{pass_count} = $total_tests > $subtests_fail
                    ? $total_tests - $subtests_fail : 0;
                $r{error} = sprintf('%d/%d subtests failed',
                    $subtests_fail, $total_tests) if $subtests_fail;
            }
            if (!$r{error}) {
                if ($text =~ /Can't locate (\S+\.pm)/m) {
                    $r{error} = "Missing: $1";
                } elsif ($text =~ /StackOverflowError/) {
                    $r{error} = 'StackOverflowError';
                } elsif ($text =~ /OutOfMemoryError/) {
                    $r{error} = 'OutOfMemoryError';
                } elsif ($text =~ /syntax error/i) {
                    $r{error} = 'Syntax error';
                }
            }
            push @results, \%r;
            next;
        }

        # Fallback
        $r{status} = 'FAIL';
        $r{error}  = 'Unknown test outcome';
        push @results, \%r;
    }

    # --- Pass 3: catch modules that never reached the test phase ---
    # (configure failures, build failures, etc.)
    for my $line (split /\n/, $output) {
        if ($line =~ /Running (?:test|install) for module '([^']+)'/) {
            $last_mod = $1;
        }

        # Configure failed
        if ($last_mod && !$seen{$last_mod}
            && $line =~ /(?:Makefile\.PL|Build\.PL) -- NOT OK/) {
            $seen{$last_mod}++;
            my %r = (
                module => $last_mod, status => 'FAIL',
                tests => undef, pass_count => undef,
                error => 'Configure failed', reason => '',
            );
            push @results, \%r;
        }

        # Build failed (not test — that's caught in Pass 2)
        if ($last_mod && !$seen{$last_mod}
            && $line =~ /(?:jperl|perl) Build -- NOT OK/) {
            $seen{$last_mod}++;
            my %r = (
                module => $last_mod, status => 'FAIL',
                tests => undef, pass_count => undef,
                error => 'Build failed', reason => '',
            );
            push @results, \%r;
        }
    }

    return @results;
}


# ══════════════════════════════════════════════════════════════════════
# Helpers
# ══════════════════════════════════════════════════════════════════════

# Run a command with a hard timeout.  On timeout, kills the entire
# process group so no orphaned jperl/java children survive.
# Returns ($output, $timed_out).
#
# Strategy (borrowed from perl_test_runner.pl):
#   1. Prefer external `timeout` / `gtimeout` — they send SIGTERM to the
#      process group and handle cleanup natively.
#   2. Fallback: fork + setpgrp + kill(-$pid) for platforms without
#      coreutils.
sub run_with_timeout {
    my ($cmd, $secs) = @_;

    # --- Strategy 1: external timeout command ---
    my $timeout_cmd = _find_timeout_cmd();
    if ($timeout_cmd) {
        my $full = "$timeout_cmd ${secs}s $cmd 2>&1";
        my $output = `$full`;
        my $exit_code = $? >> 8;
        my $timed_out = ($exit_code == 124);  # timeout exits 124
        return ($output // '', $timed_out);
    }

    # --- Strategy 2: fork + process-group kill ---
    my $output    = '';
    my $timed_out = 0;

    my $pid = open my $pipe, '-|';
    if (!defined $pid) {
        warn "fork failed: $!\n";
        return ('', 0);
    }

    if ($pid == 0) {
        # Child: run in its own process group so kill(-pid) reaches
        # the entire tree (jcpan → jperl → java, make, etc.)
        setpgrp(0, 0);
        open STDERR, '>&', \*STDOUT;
        exec('/bin/sh', '-c', $cmd);
        exit 127;
    }

    # Parent: read with alarm timeout
    eval {
        local $SIG{ALRM} = sub { die "TIMEOUT\n" };
        alarm($secs);
        local $/;
        $output = <$pipe>;
        alarm(0);
    };

    if ($@ && $@ =~ /TIMEOUT/) {
        $timed_out = 1;
        # Kill the entire process group (negative PID)
        kill 'TERM', -$pid;
        # Give children a moment to exit, then force-kill
        my $reaped = 0;
        for (1..10) {
            if (waitpid($pid, WNOHANG) > 0) {
                $reaped = 1;
                last;
            }
            select(undef, undef, undef, 0.2);
        }
        unless ($reaped) {
            kill 'KILL', -$pid;
            waitpid($pid, 0);
        }
    }

    close $pipe;    # always close to avoid FD leak
    # Reap child if not already reaped (close may have done it, but
    # waitpid on an already-reaped pid is harmless)
    waitpid($pid, WNOHANG) unless $timed_out;

    return ($output // '', $timed_out);
}

{
    my $_timeout_cmd;       # cached result (undef = not checked yet)
    my $_checked = 0;

    sub _find_timeout_cmd {
        return $_timeout_cmd if $_checked;
        $_checked = 1;
        for my $candidate (qw(timeout gtimeout)) {
            if (system("which $candidate >/dev/null 2>&1") == 0) {
                $_timeout_cmd = $candidate;
                return $_timeout_cmd;
            }
        }
        return undef;
    }
}

sub save_log {
    my ($module, $output) = @_;
    (my $safe = $module) =~ s/::/-/g;
    my $path = "$log_dir/${safe}.log";
    if (open my $fh, '>', $path) {
        print $fh $output;
        close $fh;
    }
}

# ──────────────────────────────────────────────────────────────────────
# Persistent .dat file I/O
# Format: module<TAB>status<TAB>tests<TAB>pass_count<TAB>error<TAB>date<TAB>reason<TAB>git_commit
# ──────────────────────────────────────────────────────────────────────
sub load_dat {
    my ($file) = @_;
    my %data;
    return %data unless -f $file;
    open my $fh, '<', $file or return %data;
    while (<$fh>) {
        chomp;
        my ($mod, $status, $tests, $pass, $error, $date, $reason, $commit)
            = split /\t/, $_, 8;
        next unless $mod;
        $data{$mod} = {
            module     => $mod,
            status     => $status // 'UNKNOWN',
            tests      => (defined $tests  && $tests  ne '') ? $tests  : undef,
            pass_count => (defined $pass   && $pass   ne '') ? $pass   : undef,
            error      => $error  // '',
            date       => $date   // '',
            reason     => $reason // '',
            git_commit => $commit // '',
        };
    }
    close $fh;
    return %data;
}

sub save_dat {
    my ($file, $data) = @_;
    open my $fh, '>', $file or die "Cannot write $file: $!\n";
    for my $mod (sort keys %$data) {
        my $r = $data->{$mod};
        printf $fh "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
            $mod,
            $r->{status}     // '',
            $r->{tests}      // '',
            $r->{pass_count} // '',
            $r->{error}      // '',
            $r->{date}       // '',
            $r->{reason}     // '',
            $r->{git_commit} // '';
    }
    close $fh;
}

# ──────────────────────────────────────────────────────────────────────
# Markdown report generator
# ──────────────────────────────────────────────────────────────────────
sub generate_report {
    my $now = strftime('%Y-%m-%d %H:%M:%S', localtime);
    my $total_pass = scalar keys %pass_modules;
    my $total_fail = scalar keys %fail_modules;
    my $total_skip = scalar keys %skip_modules;
    my $total      = $total_pass + $total_fail + $total_skip;
    my $pass_pct   = $total > 0 ? sprintf('%.1f', $total_pass / $total * 100) : '0.0';

    open my $fh, '>', $report_md or die "Cannot write $report_md: $!\n";

    print $fh <<HEADER;
# CPAN Module Compatibility Report for PerlOnJava

> Auto-generated by `dev/tools/cpan_random_tester.pl` on $now
>
> Modules are randomly selected from the full CPAN index and tested
> with `./jcpan -t`. Dependencies are tested too; every module that
> appears in the output is recorded.

## Summary

| Metric | Count |
|--------|-------|
| **Modules Tested** | $total |
| **Pass** | $total_pass ($pass_pct%) |
| **Fail** | $total_fail |
| **Skipped (XS-only)** | $total_skip |

HEADER

    # ── Pass list ──
    print $fh "## Modules That Pass All Tests\n\n";
    if ($total_pass > 0) {
        print $fh "| Module | Subtests | Date | Git Commit |\n";
        print $fh "|--------|----------|------|------------|\n";
        for my $mod (sort keys %pass_modules) {
            my $r = $pass_modules{$mod};
            my $tests  = defined $r->{tests} ? $r->{tests} : '-';
            my $date   = $r->{date}       // '';
            my $commit = $r->{git_commit} // '';
            print $fh "| $mod | $tests | $date | $commit |\n";
        }
    } else {
        print $fh "_No modules have passed yet._\n";
    }
    print $fh "\n";

    # ── Fail list grouped by error type ──
    print $fh "## Modules That Fail Tests\n\n";
    if ($total_fail > 0) {
        my %by_error;
        for my $mod (sort keys %fail_modules) {
            my $r = $fail_modules{$mod};
            my $cat = categorize_error($r);
            push @{$by_error{$cat}}, $r;
        }

        for my $cat (sort keys %by_error) {
            my @mods = @{$by_error{$cat}};
            printf $fh "### %s (%d modules)\n\n", $cat, scalar @mods;
            print $fh "| Module | Pass/Total | Error | Date |\n";
            print $fh "|--------|-----------|-------|------|\n";
            for my $r (sort { $a->{module} cmp $b->{module} } @mods) {
                my $tests = '';
                if (defined $r->{tests} && $r->{tests} > 0) {
                    $tests = ($r->{pass_count} // '?') . '/' . $r->{tests};
                }
                my $error = $r->{error} // '';
                $error =~ s/\|/\\|/g;
                my $date = $r->{date} // '';
                print $fh "| $r->{module} | $tests | $error | $date |\n";
            }
            print $fh "\n";
        }
    } else {
        print $fh "_No failures recorded yet._\n";
    }

    # ── Skip list ──
    if ($total_skip > 0) {
        print $fh "## Skipped Modules (XS-only)\n\n";
        print $fh "These modules require XS/C extensions and cannot work with PerlOnJava ";
        print $fh "unless a Java backend is implemented.\n\n";
        print $fh "| Module | Reason | Date |\n";
        print $fh "|--------|--------|------|\n";
        for my $mod (sort keys %skip_modules) {
            my $r = $skip_modules{$mod};
            print $fh "| $mod | $r->{reason} | $r->{date} |\n";
        }
        print $fh "\n";
    }

    print $fh <<FOOTER;
## How to Reproduce

```bash
# Test 10 random modules (default — uses jcpan -t)
perl dev/tools/cpan_random_tester.pl

# Test more modules
perl dev/tools/cpan_random_tester.pl --count 50

# Install mode (deps stay installed for future runs)
perl dev/tools/cpan_random_tester.pl --install --count 20

# Regenerate this report from existing data
perl dev/tools/cpan_random_tester.pl --report-only

# Reproducible random seed
perl dev/tools/cpan_random_tester.pl --seed 42 --count 20
```

## Data Files

- `dev/cpan-reports/cpan-compatibility-pass.dat` — Pass list (TSV, includes git commit)
- `dev/cpan-reports/cpan-compatibility-fail.dat` — Fail list (TSV)
- `dev/cpan-reports/cpan-compatibility-skip.dat` — Skip list (TSV)
- `/tmp/cpan_random_logs/<Module-Name>.log` — Per-module test output
FOOTER

    close $fh;
}

sub categorize_error {
    my ($r) = @_;
    my $err = $r->{error} // '';

    return 'Timeout'              if $err =~ /TIMEOUT/i;
    return 'Missing Dependencies' if $err =~ /Missing|Can't locate/i;
    return 'Configure Failed'     if $err =~ /Configure/i;
    return 'Stack/Memory'         if $err =~ /StackOverflow|OutOfMemory/i;
    return 'PerlOnJava Limits'    if $err =~ /register limit/i;
    return 'Syntax Error'         if $err =~ /Syntax error/i;
    return 'Test Failures'        if $err =~ /subtests failed/i
                                  || (defined $r->{tests} && $r->{tests} > 0);
    return 'Other';
}

sub print_usage {
    print <<'USAGE';
cpan_random_tester.pl - Random CPAN Module Tester for PerlOnJava

Usage:
  perl dev/tools/cpan_random_tester.pl [options]

Options:
  --count N, -n N  Number of random target modules to test (default: 10)
                   Dependencies are tested too, so actual module count is higher.
  --timeout N      Timeout per target module in seconds (default: 300)
  --install        Use jcpan (install) instead of jcpan -t (test only).
                   Deps stay installed for future runs, but already-installed
                   modules are skipped (no re-test).
  --report-only    Regenerate .md report from existing .dat files
  --seed N         Random seed for reproducible module selection
  --help           Show this help

Behavior:
  - Default: uses jcpan -t (always runs tests, even for installed modules).
  - Targets are randomly chosen from modules that haven't passed yet.
  - Dependencies discovered during a run are recorded too (PASS/FAIL).
  - If a previously-failed module now passes (e.g., its deps got
    installed), the record is upgraded from FAIL to PASS.
  - PASS results include the git commit hash for regression bisecting.
  - Results accumulate across runs (never discarded).

Examples:
  perl dev/tools/cpan_random_tester.pl                   # 10 targets
  perl dev/tools/cpan_random_tester.pl --count 50        # 50 targets
  perl dev/tools/cpan_random_tester.pl --seed 42 -n 20   # reproducible
  perl dev/tools/cpan_random_tester.pl --report-only     # regen report

Output:
  dev/cpan-reports/cpan-compatibility.md         Markdown report
  dev/cpan-reports/cpan-compatibility-pass.dat   Pass list (TSV)
  dev/cpan-reports/cpan-compatibility-fail.dat   Fail list (TSV)
  dev/cpan-reports/cpan-compatibility-skip.dat   Skip list (TSV)
  /tmp/cpan_random_logs/                    Per-module logs

USAGE
}
