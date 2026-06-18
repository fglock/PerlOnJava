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
#   perl dev/tools/cpan_random_tester.pl --modules Foo::Bar,Baz::Qux  # Test specific modules
#   perl dev/tools/cpan_random_tester.pl --modules list.txt # Test modules from file
#   perl dev/tools/cpan_random_tester.pl --report-only      # Regenerate .md from .dat
#   perl dev/tools/cpan_random_tester.pl --timeout 120      # 2 min soft timeout
#   perl dev/tools/cpan_random_tester.pl --max-runtime 0    # disable 90 min hard cap
#   perl dev/tools/cpan_random_tester.pl --jobs 8           # Parallelize CPAN test files
#   perl dev/tools/cpan_random_tester.pl --install           # Install mode (deps stay)
#
# Output:
#   - dev/cpan-reports/cpan-compatibility.md        (human-readable report)
#   - dev/cpan-reports/cpan-compatibility-pass.dat  (machine-readable pass list)
#   - dev/cpan-reports/cpan-compatibility-fail.dat  (machine-readable fail list)
#   - dev/cpan-reports/cpan-compatibility-skip.dat  (machine-readable skip list)
#   - Per-module logs to /tmp/cpan_random_logs/<Run-ID>/
#
# Run with `perl` (not jperl) because this script uses fork.
#
# Prerequisites:
#   - Build PerlOnJava first: make dev
#   - CPAN index must exist: ~/.cpan/sources/modules/02packages.details.txt.gz
#     (run `./jcpan` once interactively if missing)

use strict;
use warnings;
$| = 1;  # autoflush STDOUT so progress is visible when redirected to a file
use File::Basename;
use File::Spec;
use File::Path qw(make_path);
use Fcntl qw(:flock);
use Getopt::Long;
use IO::Select;
use POSIX qw(strftime WNOHANG);
use Time::Local qw(timelocal);

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
my $report_lock  = File::Spec->catfile(File::Spec->tmpdir, report_lock_name($project_root));
my $log_root     = '/tmp/cpan_random_logs';
my $run_id       = strftime('%Y%m%d-%H%M%S', localtime) . "-$$";
my $log_dir      = File::Spec->catdir($log_root, $run_id);
my $KILL_AFTER          = 10;   # seconds between SIGTERM and SIGKILL (used by run_with_timeout)
my $DEFAULT_MAX_RUNTIME = 5400; # 90 minutes — hard cap per target (install or test)
my $MAX_CAPTURE_BYTES   = 1_000_000; # keep only this much child output in memory

# jcpan -t soft timeouts (seconds): distribution root module -> timeout.
# Overrides --timeout for that target only (heavy test suites).
# Hard --max-runtime still applies regardless of soft timeout or activity.
my %MODULE_TIMEOUT_SECONDS = (
    'DBIx::Class'     => 3600,
    'Image::ExifTool' => 3600,
);

# CPAN package index
my $packages_gz  = glob('~/.cpan/sources/modules/02packages.details.txt.gz');

# ──────────────────────────────────────────────────────────────────────
# CLI options
# ──────────────────────────────────────────────────────────────────────
my $count       = 10;
my $timeout        = 2400;  # soft wall-clock timeout; progress can extend it
my $activity_grace = 600;   # after soft timeout, allow this many idle seconds
my $max_runtime    = $DEFAULT_MAX_RUNTIME;  # hard cap per target module
my $progress_interval = 60;
my $jcpan_jobs  = 1;      # passed through as `jcpan --jobs N`
my $report_only = 0;
my $install     = 0;      # --install: use jcpan (install) instead of jcpan -t
my $retest_age  = 0;      # --retest-age DAYS: include modules tested N+ days ago
my $modules_arg = '';     # --modules: comma-separated list or file path
my $help        = 0;
my $seed;

GetOptions(
    'count|n=i'    => \$count,
    'timeout=i'    => \$timeout,
    'activity-grace=i' => \$activity_grace,
    'max-runtime=i' => \$max_runtime,
    'progress-interval=i' => \$progress_interval,
    'jobs=i'       => \$jcpan_jobs,
    'report-only'  => \$report_only,
    'install'      => \$install,
    'retest-age=i' => \$retest_age,
    'modules=s'    => \$modules_arg,
    'seed=i'       => \$seed,
    'help|h'       => \$help,
) or die "Error in command line arguments\n";

if ($help) {
    print_usage();
    exit 0;
}

die "--timeout must be a positive integer\n" unless $timeout > 0;
die "--activity-grace must be a positive integer\n" unless $activity_grace > 0;
die "--max-runtime must be 0 or a positive integer\n" unless $max_runtime >= 0;
die "--progress-interval must be 0 or a positive integer\n" unless $progress_interval >= 0;
die "--jobs must be a positive integer\n" unless $jcpan_jobs > 0;

sub effective_timeout_for {
    my ($module) = @_;
    my $secs = $MODULE_TIMEOUT_SECONDS{$module} // $timeout;
    if ($max_runtime && $secs > $max_runtime) {
        $secs = $max_runtime;
    }
    return $secs;
}

# ──────────────────────────────────────────────────────────────────────
# Setup
# ──────────────────────────────────────────────────────────────────────
make_path($report_dir) unless -d $report_dir;

srand($seed) if defined $seed;

# Get current git commit hash (recorded with PASS results for bisecting)
my $git_commit = `git -C '$project_root' rev-parse --short HEAD 2>/dev/null`;
chomp $git_commit;
$git_commit ||= 'unknown';

# Load existing results
my (%pass_modules, %fail_modules, %skip_modules);
with_report_lock(sub {
    reload_report_state();
});

if ($report_only) {
    with_report_lock(sub {
        reload_report_state();
        generate_report();
    });
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
    next if /^\s*$/ || /^[A-Za-z-]+:\s/ || /^\s/;  # skip header
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
# If --retest-age is set, re-test existing report entries tested N+ days ago.
my @candidates;

if ($modules_arg) {
    # User provided specific module list
    @candidates = parse_module_list($modules_arg);
    printf "Testing %d user-specified modules\n", scalar @candidates;
} elsif ($retest_age > 0) {
    # Restrict to modules last tested N+ days ago (for concurrent instance work).
    # Use the report records directly instead of @all_modules: the report also
    # contains dependency modules, not just the distribution-root modules chosen
    # from the CPAN index.
    my $cutoff_date = cutoff_date_for_days_ago($retest_age);
    my %seen;
    for my $mod (sort (keys %pass_modules, keys %fail_modules)) {
        next if $seen{$mod}++;
        next if $skip_modules{$mod};

        my $record;
        if ($pass_modules{$mod}) {
            $record = $pass_modules{$mod};
        } elsif ($fail_modules{$mod}) {
            $record = $fail_modules{$mod};
        } else {
            next;  # Skip untested
        }

        my $test_date = $record->{date} // '';
        push @candidates, $mod if !$test_date || $test_date le $cutoff_date;
    }
    printf "Candidates at least %d days old: %d\n", $retest_age, scalar @candidates;
} else {
    # Default: untested + failures (in case their deps got installed)
    for my $mod (@all_modules) {
        next if $pass_modules{$mod};
        next if $skip_modules{$mod};
        push @candidates, $mod;
    }
    printf "Candidates (not yet passing): %d\n", scalar @candidates;
}

# Shuffle candidates to ensure randomization (they may be in alphabetical order from @all_modules)
for my $i (0 .. scalar(@candidates) - 1) {
    my $j = $i + int(rand(scalar(@candidates) - $i));
    @candidates[$i, $j] = @candidates[$j, $i];
}

if (!@candidates) {
    print "All modules have been tested! Use --report-only to regenerate the report.\n";
    with_report_lock(sub {
        reload_report_state();
        generate_report();
    });
    exit 0;
}

# ──────────────────────────────────────────────────────────────────────
# Randomly select modules to test
# ──────────────────────────────────────────────────────────────────────
my @selected;
if ($modules_arg) {
    # Already selected by user
    @selected = @candidates;
} elsif ($count >= scalar @candidates) {
    @selected = @candidates;
} else {
    my @pool = @candidates;
    for my $i (0 .. $count - 1) {
        my $j = $i + int(rand(scalar(@pool) - $i));
        @pool[$i, $j] = @pool[$j, $i];
    }
    @selected = @pool[0 .. $count - 1];
}

make_path($log_dir) unless -d $log_dir;

printf "\nTesting %d randomly selected modules (soft timeout: %ds, activity grace: %ds, max runtime: %ds, jcpan jobs: %d, commit: %s):\n",
    scalar @selected, $timeout, $activity_grace, $max_runtime, $jcpan_jobs, $git_commit;
if (%MODULE_TIMEOUT_SECONDS) {
    print "Per-module soft timeouts: ",
        join(', ', map { "$_=${MODULE_TIMEOUT_SECONDS{$_}}s" } sort keys %MODULE_TIMEOUT_SECONDS),
        "\n";
}
print "=" x 70, "\n\n";

# ──────────────────────────────────────────────────────────────────────
# Test each module and harvest results for all deps too
# ──────────────────────────────────────────────────────────────────────
my $target_count = 0;
my $selected_index = 0;
my $new_pass     = 0;
my $new_fail     = 0;
my $new_skip     = 0;
my $upgraded     = 0;   # FAIL→PASS transitions
my $regressed    = 0;   # PASS→FAIL transitions from explicit re-tests
my $record_pass_regressions = ($retest_age > 0 || $modules_arg ne '');

for my $module (@selected) {
    $selected_index++;
    my ($skip_target, $skip_reason) = should_skip_selected_module($module);
    if ($skip_target) {
        printf "[%d/%d] %s (%s; skipped)\n\n",
            $selected_index, scalar @selected, $module, $skip_reason;
        next;
    }

    $target_count++;
    my $module_timeout = effective_timeout_for($module);
    my @cmd = jcpan_command_for($module);
    printf "[%d/%d] %s (soft timeout %ds, activity grace %ds)\n",
        $selected_index, scalar @selected, command_label(@cmd), $module_timeout, $activity_grace;

    my $start = time();
    my $log_path = log_path_for($module);
    my ($output_tail, $timed_out, $timeout_error) = run_with_timeout(\@cmd, $module_timeout, $log_path);

    my $elapsed = sprintf('%.1f', time() - $start);

    # Parse ALL module results from the output (target + deps)
    my @all_results = parse_all_module_results_from_file($log_path);
    if (!@all_results && (!defined $log_path || !-s $log_path) && length $output_tail) {
        @all_results = parse_all_module_results($output_tail);
    }

    # A timed-out target can still have parseable dependency results in the
    # output. Preserve those, but make sure the target itself is recorded too.
    if ($timed_out && !grep { ($_->{module} // '') eq $module } @all_results) {
        push @all_results, {
            module => $module, status => 'FAIL',
            tests => undef, pass_count => undef,
            error => $timeout_error || "TIMEOUT (>${module_timeout}s)",
        };
    }

    # If nothing parsed, check for special cases before recording failure
    if (!@all_results) {
        if (output_file_contains($log_path, qr/\Q$module\E is up to date/)
            || $output_tail =~ /\Q$module\E is up to date/) {
            # Already installed, jcpan skipped it — not a failure
            printf "  (already installed, skipped)\n\n";
            next;
        } else {
            # Check for PerlOnJava-specific errors in the raw output
            my $error = classify_output_error_from_file($log_path);
            $error = classify_output_error($output_tail)
                if $error eq 'No parseable output' && length $output_tail;
            push @all_results, {
                module => $module, status => 'FAIL',
                tests => undef, pass_count => undef,
                error => $error,
            };
        }
    }

    my ($changes, $events) = persist_module_results(\@all_results, $record_pass_regressions);
    $new_pass  += $changes->{new_pass};
    $new_fail  += $changes->{new_fail};
    $new_skip  += $changes->{new_skip};
    $upgraded  += $changes->{upgraded};
    $regressed += $changes->{regressed};
    print "$_\n" for @$events;

    printf "  (%ss, %d modules in output)\n\n", $elapsed, scalar @all_results;
}

# ──────────────────────────────────────────────────────────────────────
# Summary
# ──────────────────────────────────────────────────────────────────────
with_report_lock(sub {
    reload_report_state();
    generate_report();
});

print "=" x 70, "\n";
printf "This run:   %d targets | +%d pass | +%d fail | +%d skip | %d upgraded (FAIL->PASS) | %d regressed (PASS->FAIL)\n",
    $target_count, $new_pass, $new_fail, $new_skip, $upgraded, $regressed;
printf "Cumulative: %d pass | %d fail | %d skip | %d total\n",
    scalar keys %pass_modules, scalar keys %fail_modules,
    scalar keys %skip_modules,
    scalar(keys %pass_modules) + scalar(keys %fail_modules) + scalar(keys %skip_modules);

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

        my $total_tests = 0;

        if ($text =~ /Files=\d+, Tests=(\d+)/) {
            $total_tests = $1;
        }

        my %failure_counts = parse_harness_failure_counts($text);

        if (($text =~ /All tests successful/ || $text =~ /Result: PASS/)
            && $text !~ /Result: FAIL/
            && $text !~ /(?:make|Build) test -- NOT OK/) {
            $r{status}     = 'PASS';
            $r{tests}      = $total_tests || undef;
            $r{pass_count} = $total_tests || undef;
            push @results, \%r;
            next;
        }

        if (is_bundled_skip_output($text)) {
            $r{status} = 'SKIP';
            $r{reason} = 'bundled';
            push @results, \%r;
            next;
        }

        if (is_perlonjava_distropref_skip_output($text)) {
            $r{status} = 'SKIP';
            $r{reason} = 'distroprefs';
            push @results, \%r;
            next;
        }

        if ($text =~ /Result: FAIL/ || $text =~ /(?:make|Build) test -- NOT OK/) {
            $r{status} = 'FAIL';
            if ($total_tests > 0) {
                $r{tests} = $total_tests;
                if (defined $failure_counts{subtests_failed}
                    && defined $failure_counts{subtests_total}) {
                    $r{pass_count} = $failure_counts{subtests_failed} > 0
                        ? $failure_counts{subtests_total} - $failure_counts{subtests_failed}
                        : undef;
                }
            }

            $r{error} = format_harness_failure_error(%failure_counts);

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
    my %pending_skip;
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

        # Bundled primary modules generate a no-op test target. Some CPAN
        # output shapes may omit the standard make-test block, so keep this
        # fallback too. Defer recording until after the scan so a later
        # configure/build failure still wins.
        $pending_skip{$last_mod} = 'bundled'
            if $last_mod && !$seen{$last_mod} && is_bundled_skip_output($line);

        $pending_skip{$last_mod} ||= 'distroprefs'
            if $last_mod && !$seen{$last_mod} && is_perlonjava_distropref_skip_output($line);
    }

    for my $mod (sort keys %pending_skip) {
        next if $seen{$mod}++;
        my %r = (
            module => $mod, status => 'SKIP',
            tests => undef, pass_count => undef,
            error => '', reason => $pending_skip{$mod},
        );
        push @results, \%r;
    }

    return @results;
}

sub parse_all_module_results_from_file {
    my ($path) = @_;
    return () unless defined $path && -f $path;

    # Pass 1: map dist paths to module names.
    my %dist_to_mod;
    my $last_mod;
    if (open my $fh, '<', $path) {
        while (my $line = <$fh>) {
            if ($line =~ /Running (?:test|install) for module '([^']+)'/) {
                $last_mod = $1;
            }
            if ($last_mod && $line =~ m{Configuring \S+/(\S+)\.tar\.gz}) {
                $dist_to_mod{$1} = $last_mod;
            }
        }
        close $fh;
    } else {
        warn "Cannot read log '$path': $!\n";
        return ();
    }

    # Pass 2: parse contiguous "Running make/Build test for ..." blocks
    # without retaining the whole block in memory.
    my @results;
    my %seen;
    my $block;
    if (open my $fh, '<', $path) {
        while (my $line = <$fh>) {
            if ($line =~ m{Running (?:make|Build) test for \S+/(\S+)\.tar\.gz}) {
                finish_streamed_test_block($block, \%dist_to_mod, \%seen, \@results)
                    if $block;
                $block = new_streamed_test_block($1);
                update_streamed_test_block($block, $line);
            } elsif ($block) {
                update_streamed_test_block($block, $line);
                if ($line =~ /(?:make|Build) test -- (?:OK|NOT OK)/) {
                    finish_streamed_test_block($block, \%dist_to_mod, \%seen, \@results);
                    $block = undef;
                }
            }
        }
        finish_streamed_test_block($block, \%dist_to_mod, \%seen, \@results)
            if $block;
        close $fh;
    } else {
        warn "Cannot read log '$path': $!\n";
        return @results;
    }

    # Pass 3: catch modules that never reached the test phase.
    my %pending_skip;
    $last_mod = undef;
    if (open my $fh, '<', $path) {
        while (my $line = <$fh>) {
            if ($line =~ /Running (?:test|install) for module '([^']+)'/) {
                $last_mod = $1;
            }

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

            $pending_skip{$last_mod} = 'bundled'
                if $last_mod && !$seen{$last_mod} && is_bundled_skip_output($line);

            $pending_skip{$last_mod} ||= 'distroprefs'
                if $last_mod && !$seen{$last_mod} && is_perlonjava_distropref_skip_output($line);
        }
        close $fh;
    }

    for my $mod (sort keys %pending_skip) {
        next if $seen{$mod}++;
        my %r = (
            module => $mod, status => 'SKIP',
            tests => undef, pass_count => undef,
            error => '', reason => $pending_skip{$mod},
        );
        push @results, \%r;
    }

    return @results;
}

sub new_streamed_test_block {
    my ($dist) = @_;
    return {
        dist                 => $dist,
        total_tests          => 0,
        failure_counts       => {},
        all_tests_successful => 0,
        result_pass          => 0,
        result_fail          => 0,
        test_not_ok          => 0,
        bundled_skip         => 0,
        distropref_skip      => 0,
        missing_pm           => undef,
        stack_overflow       => 0,
        out_of_memory        => 0,
        syntax_error         => 0,
    };
}

sub update_streamed_test_block {
    my ($block, $line) = @_;
    return unless $block;

    if ($line =~ /Files=\d+, Tests=(\d+)/) {
        $block->{total_tests} = $1;
    }

    update_harness_failure_counts_from_line($block->{failure_counts}, $line);

    $block->{all_tests_successful} = 1 if $line =~ /All tests successful/;
    $block->{result_pass}          = 1 if $line =~ /Result: PASS/;
    $block->{result_fail}          = 1 if $line =~ /Result: FAIL/;
    $block->{test_not_ok}          = 1 if $line =~ /(?:make|Build) test -- NOT OK/;
    $block->{bundled_skip}         = 1 if is_bundled_skip_output($line);
    $block->{distropref_skip}      = 1 if is_perlonjava_distropref_skip_output($line);
    $block->{stack_overflow}       = 1 if $line =~ /StackOverflowError/;
    $block->{out_of_memory}        = 1 if $line =~ /OutOfMemoryError/;
    $block->{syntax_error}         = 1 if $line =~ /syntax error/i;

    if (!defined $block->{missing_pm} && $line =~ /Can't locate (\S+\.pm)/) {
        $block->{missing_pm} = $1;
    }
}

sub update_harness_failure_counts_from_line {
    my ($counts, $line) = @_;
    my $summary = 0;

    if ($line =~ /Failed\s+(\d+)\/(\d+)\s+test programs?\.\s+(\d+)\/(\d+)\s+subtests failed\./) {
        @{$counts}{qw(test_programs_failed test_programs_total subtests_failed subtests_total)}
            = ($1, $2, $3, $4);
        $summary = 1;
    } else {
        if ($line =~ /Failed\s+(\d+)\/(\d+)\s+test programs?\./) {
            @{$counts}{qw(test_programs_failed test_programs_total)} = ($1, $2);
        }
        if ($line =~ /(\d+)\/(\d+)\s+subtests failed\./) {
            @{$counts}{qw(subtests_failed subtests_total)} = ($1, $2);
        }
    }

    if (!$summary && !defined $counts->{subtests_failed}
        && $line =~ /Failed\s+(\d+)\/(\d+)\s+subtests\b/) {
        $counts->{subtests_failed_in_files} += $1;
    }
}

sub finish_streamed_test_block {
    my ($block, $dist_to_mod, $seen, $results) = @_;
    return unless $block && $block->{dist};

    my $dist = $block->{dist};
    my $mod  = $dist_to_mod->{$dist};
    unless ($mod) {
        ($mod = $dist) =~ s/-[\d.]+$//;
        $mod =~ s/-/::/g;
    }
    return if $seen->{$mod}++;

    my %r = (
        module     => $mod,
        status     => 'UNKNOWN',
        tests      => undef,
        pass_count => undef,
        error      => '',
        reason     => '',
    );

    my $total_tests = $block->{total_tests} || 0;

    if (($block->{all_tests_successful} || $block->{result_pass})
        && !$block->{result_fail}
        && !$block->{test_not_ok}) {
        $r{status}     = 'PASS';
        $r{tests}      = $total_tests || undef;
        $r{pass_count} = $total_tests || undef;
        push @$results, \%r;
        return;
    }

    if ($block->{bundled_skip}) {
        $r{status} = 'SKIP';
        $r{reason} = 'bundled';
        push @$results, \%r;
        return;
    }

    if ($block->{distropref_skip}) {
        $r{status} = 'SKIP';
        $r{reason} = 'distroprefs';
        push @$results, \%r;
        return;
    }

    if ($block->{result_fail} || $block->{test_not_ok}) {
        $r{status} = 'FAIL';
        if ($total_tests > 0) {
            $r{tests} = $total_tests;
            my $counts = $block->{failure_counts};
            if (defined $counts->{subtests_failed}
                && defined $counts->{subtests_total}) {
                $r{pass_count} = $counts->{subtests_failed} > 0
                    ? $counts->{subtests_total} - $counts->{subtests_failed}
                    : undef;
            }
        }

        $r{error} = format_harness_failure_error(%{$block->{failure_counts}});

        if (!$r{error}) {
            if (defined $block->{missing_pm}) {
                $r{error} = "Missing: $block->{missing_pm}";
            } elsif ($block->{stack_overflow}) {
                $r{error} = 'StackOverflowError';
            } elsif ($block->{out_of_memory}) {
                $r{error} = 'OutOfMemoryError';
            } elsif ($block->{syntax_error}) {
                $r{error} = 'Syntax error';
            }
        }
        push @$results, \%r;
        return;
    }

    $r{status} = 'FAIL';
    $r{error}  = 'Unknown test outcome';
    push @$results, \%r;
}

sub is_bundled_skip_output {
    my ($text) = @_;
    return 1 if $text =~ /PerlOnJava:\s+.+?\s+is bundled in the JAR;\s+skipping upstream test suite/i;
    return 1 if $text =~ /NOTE:\s+\S+\.pm\s+is bundled;\s+upstream test suite will be skipped/i;
    return 0;
}

sub is_perlonjava_distropref_skip_output {
    my ($text) = @_;
    return $text =~ /PERLONJAVA_SKIP -- (?:configure|make|test|install) phase skipped/ ? 1 : 0;
}

sub parse_harness_failure_counts {
    my ($text) = @_;
    my %counts;

    if ($text =~ /Failed\s+(\d+)\/(\d+)\s+test programs?\.\s+(\d+)\/(\d+)\s+subtests failed\./) {
        @counts{qw(test_programs_failed test_programs_total subtests_failed subtests_total)}
            = ($1, $2, $3, $4);
        return %counts;
    }

    if ($text =~ /Failed\s+(\d+)\/(\d+)\s+test programs?\./) {
        @counts{qw(test_programs_failed test_programs_total)} = ($1, $2);
    }
    if ($text =~ /(\d+)\/(\d+)\s+subtests failed\./) {
        @counts{qw(subtests_failed subtests_total)} = ($1, $2);
    }

    if (!defined $counts{subtests_failed}) {
        my $failed_in_files = 0;
        while ($text =~ /Failed\s+(\d+)\/(\d+)\s+subtests\b/g) {
            $failed_in_files += $1;
        }
        $counts{subtests_failed_in_files} = $failed_in_files
            if $failed_in_files;
    }

    return %counts;
}

sub format_harness_failure_error {
    my (%counts) = @_;
    my ($subtest_part, $program_part);

    if (defined $counts{subtests_failed} && defined $counts{subtests_total}) {
        $subtest_part = sprintf('%d/%d subtests failed',
            $counts{subtests_failed}, $counts{subtests_total});
    } elsif (defined $counts{subtests_failed_in_files}) {
        $subtest_part = sprintf('%d subtests failed in test files',
            $counts{subtests_failed_in_files});
    }

    if (defined $counts{test_programs_failed} && defined $counts{test_programs_total}) {
        $program_part = sprintf('%d/%d test programs failed',
            $counts{test_programs_failed}, $counts{test_programs_total});
    }

    my @parts;
    if (($counts{subtests_failed} // 0) == 0 && defined $program_part) {
        @parts = grep { defined } ($program_part, $subtest_part);
    } else {
        @parts = grep { defined } ($subtest_part, $program_part);
    }

    return join('; ', @parts);
}

sub result_count_label {
    my ($r) = @_;
    return undef unless defined $r->{tests} && $r->{tests} ne '';
    return undef unless defined $r->{pass_count} && $r->{pass_count} ne '';
    return "$r->{pass_count}/$r->{tests}";
}


# ══════════════════════════════════════════════════════════════════════
# Helpers
# ══════════════════════════════════════════════════════════════════════

# Parse --modules argument: either comma-separated list or file path
sub parse_module_list {
    my ($arg) = @_;
    my @modules;

    if (-f $arg) {
        open my $fh, '<', $arg or die "Cannot read module list file '$arg': $!\n";
        while (<$fh>) {
            chomp;
            s/^\s+|\s+$//g;  # trim whitespace
            next if !$_ || /^#/;  # skip empty lines and comments
            push @modules, $_;
        }
        close $fh;
    } else {
        # Treat as comma-separated list
        @modules = split /,/, $arg;
        for my $m (@modules) {
            $m =~ s/^\s+|\s+$//g;  # trim whitespace
        }
    }

    die "No modules specified in '$arg'\n" unless @modules;
    return @modules;
}

sub jcpan_command_for {
    my ($module) = @_;
    my @cmd = ($jcpan);
    push @cmd, '--jobs', $jcpan_jobs if $jcpan_jobs > 1;
    push @cmd, '-t' unless $install;
    push @cmd, $module;
    return @cmd;
}

sub command_label {
    return join ' ', map { command_arg_label($_) } @_;
}

sub command_arg_label {
    my ($arg) = @_;
    return "''" if !defined($arg) || $arg eq '';
    return $arg if $arg =~ /\A[A-Za-z0-9_:\.\/=+-]+\z/;
    $arg =~ s/'/'"'"'/g;
    return "'$arg'";
}

# Run a command with a progress-aware timeout.  The per-module timeout is a
# soft wall clock: after it expires, output activity can keep the run alive
# until --max-runtime (default 90 minutes) or --activity-grace idle seconds.
# Once timed out, the process group is killed and any perlonjava JVMs that
# escaped into a different group but are still descendants of our jcpan child
# are mopped up (user-started jperl processes elsewhere are untouched).
# Returns ($output, $timed_out, $timeout_error).
sub run_with_timeout {
    my ($cmd, $secs, $log_path) = @_;
    my @cmd = ref($cmd) eq 'ARRAY' ? @$cmd : ('/bin/sh', '-c', $cmd);

    my $output          = '';
    my $timed_out       = 0;
    my $timeout_error   = '';
    my %run_descendants = ();

    pipe(my $pipe, my $writer) or do {
        warn "pipe failed: $!\n";
        return ('', 0, '');
    };

    my $pid = fork();
    if (!defined $pid) {
        warn "fork failed: $!\n";
        close $pipe;
        close $writer;
        return ('', 0, '');
    }

    if ($pid == 0) {
        close $pipe;
        # Child: run in its own process group so kill(-pid) reaches
        # the entire tree (jcpan → jperl → java, make, etc.)
        setpgrp(0, 0);
        open STDOUT, '>&', $writer or exit 127;
        open STDERR, '>&', \*STDOUT;
        close $writer;
        exec @cmd or do {
            print STDERR "exec failed for " . command_label(@cmd) . ": $!\n";
            exit 127;
        };
    }

    close $writer;

    my $log_fh;
    if (defined $log_path) {
        if (open my $fh, '>', $log_path) {
            binmode $fh;
            $log_fh = $fh;
        } else {
            warn "Cannot write log '$log_path': $!\n";
        }
    }

    my $selector      = IO::Select->new($pipe);
    my $start         = time();
    my $last_output   = $start;
    my $soft_deadline = $start + $secs;
    my $next_progress = $progress_interval ? $start + $progress_interval : 0;
    my $pipe_open     = 1;
    my $child_done    = 0;
    my $term_sent_at  = 0;
    my $kill_sent     = 0;

    while ($pipe_open || !$child_done) {
        my $now = time();

        if (!$timed_out) {
            if ($max_runtime && ($now - $start) >= $max_runtime) {
                $timed_out = 1;
                $timeout_error = sprintf(
                    'TIMEOUT (runtime >%ds; last output %ds ago)',
                    $max_runtime, $now - $last_output
                );
                $term_sent_at = $now;
                note_run_descendants($pid, \%run_descendants);
                terminate_process_group($pid, 'TERM');
            } elsif ($now >= $soft_deadline && ($now - $last_output) >= $activity_grace) {
                $timed_out = 1;
                $timeout_error = sprintf(
                    'TIMEOUT (soft limit %ds exceeded; no output for %ds)',
                    $secs, $now - $last_output
                );
                $term_sent_at = $now;
                note_run_descendants($pid, \%run_descendants);
                terminate_process_group($pid, 'TERM');
            }
        }

        if ($timed_out && !$child_done && !$kill_sent
            && $term_sent_at && (time() - $term_sent_at) >= $KILL_AFTER) {
            note_run_descendants($pid, \%run_descendants);
            terminate_process_group($pid, 'KILL');
            $kill_sent = 1;
        }

        my @ready;
        if ($pipe_open) {
            @ready = $selector->can_read(1);
        } else {
            select(undef, undef, undef, 1);
        }
        for my $fh (@ready) {
            my $got = sysread $fh, my $chunk, 8192;
            if (!defined $got) {
                next if $!{EINTR};
                $selector->remove($fh);
                $pipe_open = 0;
                last;
            }
            if ($got == 0) {
                $selector->remove($fh);
                $pipe_open = 0;
                last;
            }
            write_log_chunk($log_fh, $chunk) if $log_fh;
            $output = append_bounded_output($output, $chunk);
            $last_output = time();
        }

        if (!$child_done) {
            my $reaped = waitpid($pid, WNOHANG);
            if ($reaped == $pid) {
                $child_done = 1;
            }
        }

        if (!$timed_out && $progress_interval && time() >= $next_progress) {
            print_progress_line($start, $last_output, $secs);
            $next_progress += $progress_interval
                while $next_progress && $next_progress <= time();
        }

        # If SIGKILL somehow did not close the pipe, do not let the monitor
        # become the new hang.  The process group was already force-killed.
        if ($timed_out && $kill_sent && !$child_done
            && $term_sent_at && (time() - $term_sent_at) >= ($KILL_AFTER + 5)) {
            last;
        }
    }

    close $pipe;    # always close to avoid FD leak
    close $log_fh if $log_fh;
    waitpid($pid, WNOHANG) unless $child_done;

    if ($timed_out) {
        note_run_descendants($pid, \%run_descendants);
        cleanup_run_perlonjava_jvms(\%run_descendants);
    }

    return ($output // '', $timed_out, $timeout_error);
}

sub append_bounded_output {
    my ($output, $chunk) = @_;
    $output .= $chunk;
    return $output if length($output) <= $MAX_CAPTURE_BYTES;
    return substr($output, -$MAX_CAPTURE_BYTES);
}

sub write_log_chunk {
    my ($fh, $chunk) = @_;
    my $offset = 0;
    my $length = length($chunk);

    while ($offset < $length) {
        my $written = syswrite($fh, $chunk, $length - $offset, $offset);
        if (!defined $written) {
            warn "log write failed: $!\n";
            last;
        }
        last if $written == 0;
        $offset += $written;
    }
}

sub terminate_process_group {
    my ($pid, $signal) = @_;
    if ($^O eq 'MSWin32') {
        kill $signal, $pid;
        return;
    }
    kill $signal, -$pid;
    kill $signal,  $pid;
}

# Build pid => ppid map from ps (one snapshot per call).
sub read_ppid_map {
    my %ppid;
    my $ps;
    my $ok;
    {
        local $SIG{__WARN__} = sub {
            warn @_ unless $_[0] =~ /^Can't exec "ps":/;
        };
        $ok = open $ps, '-|', 'ps', '-axo', 'pid=,ppid=';
    }
    return %ppid unless $ok;
    while (<$ps>) {
        my ($p, $pp) = split;
        next unless defined $p && defined $pp;
        $ppid{$p} = $pp;
    }
    close $ps;
    return %ppid;
}

# Record every descendant of $root (including $root) into $seen.
sub note_run_descendants {
    my ($root, $seen) = @_;
    return unless $root;

    my %ppid = read_ppid_map();
    my @frontier = ($root);
    $seen->{$root} = 1;

    while (@frontier) {
        my @next;
        for my $parent (@frontier) {
            for my $p (keys %ppid) {
                next unless ($ppid{$p} // -1) == $parent;
                next if $seen->{$p};
                $seen->{$p} = 1;
                push @next, $p;
            }
        }
        @frontier = @next;
    }
}

sub is_perlonjava_java_pid {
    my ($pid) = @_;
    return 0 unless $pid && kill 0, $pid;
    my $ps;
    my $ok;
    {
        local $SIG{__WARN__} = sub {
            warn @_ unless $_[0] =~ /^Can't exec "ps":/;
        };
        $ok = open $ps, '-|', 'ps', '-p', $pid, '-o', 'command=';
    }
    return 0 unless $ok;
    my $cmd = <$ps>;
    close $ps;
    return 0 unless defined $cmd;
    return $cmd =~ /perlonjava.*\.jar|org\.perlonjava\.app\.cli\.Main/ ? 1 : 0;
}

# Parallel prove --jobs workers can land outside the jcpan process group, so
# SIGKILL on the group may leave JVM descendants behind. Only kill JVMs we
# tracked as descendants of our forked jcpan child — not unrelated user jperl.
sub cleanup_run_perlonjava_jvms {
    my ($descendants) = @_;
    for my $pid (sort { $a <=> $b } keys %$descendants) {
        next unless is_perlonjava_java_pid($pid);
        kill 9, $pid;
    }
}

sub print_progress_line {
    my ($start, $last_output, $soft_secs) = @_;
    my $now = time();
    my $extra = $now >= ($start + $soft_secs) ? ', past soft timeout' : '';
    printf "  ... still running (%s elapsed, %ds since output%s)\n",
        format_duration($now - $start), $now - $last_output, $extra;
}

sub format_duration {
    my ($seconds) = @_;
    $seconds = int($seconds);
    my $hours = int($seconds / 3600);
    my $mins  = int(($seconds % 3600) / 60);
    my $secs  = $seconds % 60;
    return sprintf('%dh%02dm%02ds', $hours, $mins, $secs) if $hours;
    return sprintf('%dm%02ds', $mins, $secs) if $mins;
    return sprintf('%ds', $secs);
}

sub log_path_for {
    my ($module) = @_;
    (my $safe = $module) =~ s/::/-/g;
    $safe =~ s/[^A-Za-z0-9_.-]/_/g;
    return File::Spec->catfile($log_dir, "${safe}.log");
}

sub output_file_contains {
    my ($path, $regex) = @_;
    return 0 unless defined $path && -f $path;
    open my $fh, '<', $path or return 0;
    while (my $line = <$fh>) {
        if ($line =~ $regex) {
            close $fh;
            return 1;
        }
    }
    close $fh;
    return 0;
}

sub classify_output_error_from_file {
    my ($path) = @_;
    return 'No parseable output' unless defined $path && -f $path;

    my %saw;
    if (open my $fh, '<', $path) {
        while (my $line = <$fh>) {
            $saw{too_many_registers} = 1 if $line =~ /Too many registers/;
            $saw{stack_overflow}     = 1 if $line =~ /StackOverflowError/;
            $saw{out_of_memory}      = 1 if $line =~ /OutOfMemoryError/;
            $saw{syntax_error}       = 1 if $line =~ /Syntax error[^\n]*/i;
            $saw{missing_pm}       ||= $1 if $line =~ /Can't locate (\S+\.pm)/;
        }
        close $fh;
    }

    return 'PerlOnJava: register limit exceeded' if $saw{too_many_registers};
    return 'StackOverflowError'                  if $saw{stack_overflow};
    return 'OutOfMemoryError'                    if $saw{out_of_memory};
    return "Missing: $saw{missing_pm}"           if $saw{missing_pm};
    return 'Syntax error'                        if $saw{syntax_error};
    return 'No parseable output';
}

sub classify_output_error {
    my ($output) = @_;
    return 'No parseable output' unless defined $output && length $output;
    return 'PerlOnJava: register limit exceeded' if $output =~ /Too many registers/;
    return 'StackOverflowError'                  if $output =~ /StackOverflowError/;
    return 'OutOfMemoryError'                    if $output =~ /OutOfMemoryError/;
    return "Missing: $1"                         if $output =~ /Can't locate (\S+\.pm)/m;
    return 'Syntax error'                        if $output =~ /Syntax error[^\n]*/mi;
    return 'No parseable output';
}

# ──────────────────────────────────────────────────────────────────────
# Persistent .dat file I/O
# Format: module<TAB>status<TAB>tests<TAB>pass_count<TAB>error<TAB>date<TAB>reason<TAB>git_commit
# ──────────────────────────────────────────────────────────────────────
sub report_lock_name {
    my ($root) = @_;
    my $name = File::Spec->rel2abs($root);
    $name =~ s/[^A-Za-z0-9_.-]+/_/g;
    $name =~ s/^_+//;
    $name = substr($name, -140) if length($name) > 140;
    return "cpan-random-tester-$name.lock";
}

sub with_report_lock {
    my ($code) = @_;
    open my $lock_fh, '>>', $report_lock
        or die "Cannot open report lock $report_lock: $!\n";
    flock($lock_fh, LOCK_EX)
        or die "Cannot lock report state $report_lock: $!\n";

    my $ok = eval {
        $code->();
        1;
    };
    my $err = $@;

    flock($lock_fh, LOCK_UN);
    close $lock_fh;

    die $err unless $ok;
}

sub reload_report_state {
    %pass_modules = load_dat($pass_dat);
    %fail_modules = load_dat($fail_dat);
    %skip_modules = load_dat($skip_dat);
}

sub save_report_state {
    save_dat($pass_dat, \%pass_modules);
    save_dat($fail_dat, \%fail_modules);
    save_dat($skip_dat, \%skip_modules);
    generate_report();
}

sub persist_module_results {
    my ($results, $record_pass_regressions) = @_;
    my %changes = (
        new_pass  => 0,
        new_fail  => 0,
        new_skip  => 0,
        upgraded  => 0,
        regressed => 0,
    );
    my @events;

    with_report_lock(sub {
        reload_report_state();

        for my $raw (@$results) {
            my $mod = $raw->{module};
            next unless $mod;

            my $r = { %$raw };
            $r->{date} = strftime('%Y-%m-%d', localtime);

            if (($r->{status} // '') eq 'PASS') {
                $r->{git_commit} = $git_commit;
                delete $skip_modules{$mod};

                if ($fail_modules{$mod}) {
                    delete $fail_modules{$mod};
                    $changes{upgraded}++;
                    my $line = sprintf "  ^ UPGRADE %-38s FAIL -> PASS", $mod;
                    $line .= sprintf " (%s subtests)", $r->{tests} if $r->{tests};
                    push @events, $line;
                } elsif ($pass_modules{$mod}) {
                    $pass_modules{$mod} = $r;
                    next;
                } else {
                    $changes{new_pass}++;
                    my $line = sprintf "  + PASS    %-38s", $mod;
                    $line .= sprintf " (%s subtests)", $r->{tests} if $r->{tests};
                    push @events, $line;
                }
                $pass_modules{$mod} = $r;

            } elsif (($r->{status} // '') eq 'SKIP') {
                delete $pass_modules{$mod};
                delete $fail_modules{$mod};
                if ($skip_modules{$mod}) {
                    $skip_modules{$mod} = $r;
                    next;
                }
                $skip_modules{$mod} = $r;
                $changes{new_skip}++;
                push @events, sprintf "  - SKIP    %-38s (%s)", $mod, $r->{reason} // '';

            } else {
                delete $skip_modules{$mod};

                # Default runs can observe transient dependency failures while
                # testing another target, so keep known PASS entries stable there.
                # Explicit module/retest-age runs are intentional re-tests and
                # should record regressions.
                if ($pass_modules{$mod}) {
                    next unless $record_pass_regressions;

                    delete $pass_modules{$mod};
                    $fail_modules{$mod} = $r;
                    $changes{regressed}++;
                    push @events, fail_event_line('  ! REGRESS', $mod, $r, 'PASS -> FAIL');
                    next;
                }

                if ($fail_modules{$mod}) {
                    $fail_modules{$mod} = $r;
                    next;
                }

                $changes{new_fail}++;
                $fail_modules{$mod} = $r;
                push @events, fail_event_line('  - FAIL   ', $mod, $r, undef);
            }
        }

        save_report_state();
    });

    return (\%changes, \@events);
}

sub fail_event_line {
    my ($prefix, $mod, $r, $transition) = @_;
    my $line = sprintf "%s %-38s", $prefix, $mod;
    $line .= " $transition" if defined $transition;
    if (my $counts = result_count_label($r)) {
        $line .= " ($counts)";
    }
    if ($r->{error}) {
        my $err = $r->{error};
        $err = substr($err, 0, 45) . '...' if length($err) > 48;
        $line .= " [$err]";
    }
    return $line;
}

sub should_skip_selected_module {
    my ($module) = @_;
    return (0, '') if $modules_arg ne '';

    my $reason = '';
    with_report_lock(sub {
        reload_report_state();

        if ($retest_age > 0) {
            if (my $r = $skip_modules{$module}) {
                $reason = 'already skipped by another instance';
                return;
            }

            my $record = $pass_modules{$module} || $fail_modules{$module};
            return unless $record;
            my $date = $record->{date} // '';
            return if !$date || $date le cutoff_date_for_days_ago($retest_age);

            $reason = "already tested on $date by another instance";
            return;
        }

        if ($pass_modules{$module}) {
            $reason = 'already marked PASS by another instance';
        } elsif ($skip_modules{$module}) {
            $reason = 'already marked SKIP by another instance';
        }
    });

    return ($reason ne '' ? 1 : 0, $reason);
}

sub write_file_atomic {
    my ($file, $writer) = @_;
    my ($volume, $dir, $base) = File::Spec->splitpath($file);
    my $tmp = File::Spec->catpath(
        $volume,
        $dir,
        ".$base.$$." . time() . "." . int(rand(1_000_000)) . ".tmp"
    );

    open my $fh, '>', $tmp or die "Cannot write $tmp: $!\n";
    my $ok = eval {
        $writer->($fh);
        close $fh or die "Cannot close $tmp: $!\n";
        1;
    };
    my $err = $@;

    if (!$ok) {
        close $fh if fileno($fh);
        unlink $tmp;
        die $err;
    }

    rename $tmp, $file or do {
        my $rename_error = $!;
        unlink $tmp;
        die "Cannot replace $file with $tmp: $rename_error\n";
    };
}

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
    write_file_atomic($file, sub {
        my ($fh) = @_;
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
    });
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

    write_file_atomic($report_md, sub {
        my ($fh) = @_;

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
| **Skipped** | $total_skip |

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
                    my $tests = result_count_label($r) // '';
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
            print $fh "## Skipped Modules\n\n";
            print $fh "These modules were recognized as intentionally skipped by the tester.\n\n";
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

# Run CPAN test files in parallel inside each module
perl dev/tools/cpan_random_tester.pl --jobs 8 --count 20

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
- `/tmp/cpan_random_logs/<Run-ID>/<Module-Name>.log` — Per-module test output
FOOTER
    });
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

sub cutoff_date_for_days_ago {
    my ($days_ago) = @_;
    my $cutoff_seconds = time() - ($days_ago * 86400);
    return strftime('%Y-%m-%d', localtime($cutoff_seconds));
}

sub print_usage {
    print <<'USAGE';
cpan_random_tester.pl - Random CPAN Module Tester for PerlOnJava

Usage:
  perl dev/tools/cpan_random_tester.pl [options]

Options:
  --count N, -n N  Number of random target modules to test (default: 10)
                   Dependencies are tested too, so actual module count is higher.
  --modules LIST   Test specific modules instead of random selection.
                   Can be:
                     - Comma-separated: --modules Foo::Bar,Baz::Qux
                     - File path: --modules modules.txt (one per line, # for comments)
  --timeout N      Default timeout per target module in seconds (default: 2400).
                   This is a soft wall clock: after this time, runs continue
                   while they keep producing output.
                   Known-slow distributions use a larger timeout wired in this script.
  --activity-grace N
                   After --timeout has elapsed, kill the target if it produces
                   no output for this many seconds (default: 600).
  --max-runtime N  Hard cap per target module in seconds (default: 5400,
                   90 minutes). Stops chatty installs/tests that never finish.
                   Pass 0 to disable the hard cap.
  --progress-interval N
                   Print a progress heartbeat every N seconds while a target is
                   still running (default: 60; 0 disables).
  --jobs N         Pass `--jobs N` to jcpan so CPAN test files run in parallel
                   inside each target module (default: 1).
  --install        Use jcpan (install) instead of jcpan -t (test only).
                   Deps stay installed for future runs, but already-installed
                   modules are skipped (no re-test).
  --retest-age N   Include modules tested N or more days ago (re-randomizes pick).
                   Useful for detecting regressions or improvements over time.
  --report-only    Regenerate .md report from existing .dat files
  --seed N         Random seed for reproducible module selection
  --help           Show this help

Behavior:
  - Default: uses jcpan -t (always runs tests, even for installed modules).
  - Targets are randomly chosen from modules that haven't passed yet
    (or from --modules if specified).
  - Dependencies discovered during a run are recorded too (PASS/FAIL).
  - A few heavy targets (e.g. DBIx::Class) have a higher per-module timeout in the script.
  - Long targets are not killed merely for crossing --timeout if their output
    is still active; they time out after --activity-grace seconds without
    output, or unconditionally at --max-runtime (default 90 minutes).
  - On timeout, stray perlonjava JVMs that are still descendants of the
    forked jcpan child are cleaned up; unrelated user jperl runs are left alone.
  - --jobs parallelizes test files within a single jcpan run. This script keeps
    target modules sequential to avoid CPAN build/install directory contention.
  - If a previously-failed module now passes (e.g., its deps got
    installed), the record is upgraded from FAIL to PASS.
  - PASS results include the git commit hash for regression bisecting.
  - Results accumulate across runs (never discarded).
  - Multiple instances can run concurrently. Report updates are protected
    by a lock, reload the latest shared state before each write, and replace
    files atomically so results from parallel runs are not lost.
  - Random/default runs re-check each selected target before starting it and
    skip targets another instance has already marked PASS or SKIP.

Examples:
  perl dev/tools/cpan_random_tester.pl                   # 10 targets
  perl dev/tools/cpan_random_tester.pl --count 50        # 50 targets
  perl dev/tools/cpan_random_tester.pl --modules Foo::Bar,Baz::Qux  # Specific modules
  perl dev/tools/cpan_random_tester.pl --modules list.txt # From file
  perl dev/tools/cpan_random_tester.pl --jobs 8 -n 20      # parallel test files
  perl dev/tools/cpan_random_tester.pl --activity-grace 600 -n 20  # tolerate quiet phases
  perl dev/tools/cpan_random_tester.pl --seed 42 -n 20   # reproducible
  perl dev/tools/cpan_random_tester.pl --report-only     # regen report
  perl dev/tools/cpan_random_tester.pl --retest-age 7 -n 30  # test modules not tested in 7 days

Output:
  dev/cpan-reports/cpan-compatibility.md         Markdown report
  dev/cpan-reports/cpan-compatibility-pass.dat   Pass list (TSV)
  dev/cpan-reports/cpan-compatibility-fail.dat   Fail list (TSV)
  dev/cpan-reports/cpan-compatibility-skip.dat   Skip list (TSV)
  /tmp/cpan_random_logs/<Run-ID>/           Per-module logs

Concurrent Execution:
  Yes, multiple instances can run simultaneously. They will:
  - Independently randomize which modules to test
  - Lock, reload, merge, and atomically rewrite the shared report files
  - Re-check random/default targets before starting to reduce duplicate work
  - Write logs under separate run-specific directories

USAGE
}
