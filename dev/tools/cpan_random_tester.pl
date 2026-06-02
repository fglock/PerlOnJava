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
#   perl dev/tools/cpan_random_tester.pl --jobs 8           # Parallelize CPAN test files
#   perl dev/tools/cpan_random_tester.pl --install           # Install mode (deps stay)
#
# Output:
#   - dev/cpan-reports/cpan-compatibility.md        (human-readable report)
#   - dev/cpan-reports/cpan-compatibility-pass.dat  (machine-readable pass list)
#   - dev/cpan-reports/cpan-compatibility-fail.dat  (machine-readable fail list)
#   - dev/cpan-reports/cpan-compatibility-skip.dat  (machine-readable skip list)
#   - Per-module logs to /tmp/cpan_random_logs/
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
my $log_dir      = '/tmp/cpan_random_logs';
my $KILL_AFTER   = 10;  # seconds between SIGTERM and SIGKILL (used by run_with_timeout)

# jcpan -t soft timeouts (seconds): distribution root module -> timeout.
# Overrides --timeout for that target only (heavy test suites).
my %MODULE_TIMEOUT_SECONDS = (
    'DBIx::Class' => 3600,
    'Image::ExifTool' => 3600,
);

# CPAN package index
my $packages_gz  = glob('~/.cpan/sources/modules/02packages.details.txt.gz');

# ──────────────────────────────────────────────────────────────────────
# CLI options
# ──────────────────────────────────────────────────────────────────────
my $count       = 10;
my $timeout     = 1200;   # soft wall-clock timeout; progress can extend it
my $activity_grace = 300; # after soft timeout, allow this many idle seconds
my $max_runtime = 0;      # 0 = no hard cap beyond activity timeout
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
    my $secs = $MODULE_TIMEOUT_SECONDS{$module};
    defined $secs ? $secs : $timeout;
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
    generate_report();
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

printf "\nTesting %d randomly selected modules (soft timeout: %ds, activity grace: %ds, jcpan jobs: %d, commit: %s):\n",
    scalar @selected, $timeout, $activity_grace, $jcpan_jobs, $git_commit;
printf "Hard max runtime: %ds\n", $max_runtime if $max_runtime;
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
my $new_pass     = 0;
my $new_fail     = 0;
my $new_skip     = 0;
my $upgraded     = 0;   # FAIL→PASS transitions
my $regressed    = 0;   # PASS→FAIL transitions from explicit re-tests
my $record_pass_regressions = ($retest_age > 0 || $modules_arg ne '');

for my $module (@selected) {
    $target_count++;
    my $module_timeout = effective_timeout_for($module);
    my @cmd = jcpan_command_for($module);
    printf "[%d/%d] %s (soft timeout %ds, activity grace %ds)\n",
        $target_count, scalar @selected, command_label(@cmd), $module_timeout, $activity_grace;

    my $start = time();
    my ($output, $timed_out, $timeout_error) = run_with_timeout(\@cmd, $module_timeout);

    my $elapsed = sprintf('%.1f', time() - $start);

    save_log($module, $output);

    # Parse ALL module results from the output (target + deps)
    my @all_results = parse_all_module_results($output);

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
        if ($output =~ /\Q$module\E is up to date/) {
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
            delete $skip_modules{$mod};

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
            delete $pass_modules{$mod};
            delete $fail_modules{$mod};
            if ($skip_modules{$mod}) {
                $skip_modules{$mod} = $r;
                next;
            }
            $skip_modules{$mod} = $r;
            $new_skip++;
            printf "  - SKIP    %-38s (%s)\n", $mod, $r->{reason} // '';

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
                $regressed++;
                printf "  ! REGRESS %-38s PASS -> FAIL", $mod;
                if (my $counts = result_count_label($r)) {
                    printf " (%s)", $counts;
                }
                if ($r->{error}) {
                    my $err = $r->{error};
                    $err = substr($err, 0, 45) . '...' if length($err) > 48;
                    printf " [%s]", $err;
                }
                print "\n";
                next;
            }
            # Already a known FAIL — update silently
            if ($fail_modules{$mod}) {
                $fail_modules{$mod} = $r;
                next;
            }
            $new_fail++;
            $fail_modules{$mod} = $r;
            printf "  - FAIL    %-38s", $mod;
            if (my $counts = result_count_label($r)) {
                printf " (%s)", $counts;
            }
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
    generate_report();  # keep .md in sync with .dat files
}

# ──────────────────────────────────────────────────────────────────────
# Summary
# ──────────────────────────────────────────────────────────────────────
print "=" x 70, "\n";
printf "This run:   %d targets | +%d pass | +%d fail | +%d skip | %d upgraded (FAIL->PASS) | %d regressed (PASS->FAIL)\n",
    $target_count, $new_pass, $new_fail, $new_skip, $upgraded, $regressed;
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
# soft wall clock: after it expires, output activity can keep the run alive.
# Once the child has been idle for --activity-grace seconds after that soft
# timeout, the whole process group is killed so no jperl/java child survives.
# Returns ($output, $timed_out, $timeout_error).
sub run_with_timeout {
    my ($cmd, $secs) = @_;
    my @cmd = ref($cmd) eq 'ARRAY' ? @$cmd : ('/bin/sh', '-c', $cmd);

    my $output        = '';
    my $timed_out     = 0;
    my $timeout_error = '';

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
                terminate_process_group($pid, 'TERM');
            } elsif ($now >= $soft_deadline && ($now - $last_output) >= $activity_grace) {
                $timed_out = 1;
                $timeout_error = sprintf(
                    'TIMEOUT (soft limit %ds exceeded; no output for %ds)',
                    $secs, $now - $last_output
                );
                $term_sent_at = $now;
                terminate_process_group($pid, 'TERM');
            }
        }

        if ($timed_out && !$child_done && !$kill_sent
            && $term_sent_at && (time() - $term_sent_at) >= $KILL_AFTER) {
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
            $output .= $chunk;
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
    waitpid($pid, WNOHANG) unless $child_done;

    return ($output // '', $timed_out, $timeout_error);
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
  --timeout N      Default timeout per target module in seconds (default: 1200).
                   This is a soft wall clock: after this time, runs continue
                   while they keep producing output.
                   Known-slow distributions use a larger timeout wired in this script.
  --activity-grace N
                   After --timeout has elapsed, kill the target if it produces
                   no output for this many seconds (default: 300).
  --max-runtime N  Optional hard cap per target module in seconds (default: 0,
                   disabled). Useful for chatty tests that never finish.
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
    is still active; they time out only after --activity-grace seconds without
    output, or after --max-runtime if one is set.
  - --jobs parallelizes test files within a single jcpan run. This script keeps
    target modules sequential to avoid CPAN build/install directory contention.
  - If a previously-failed module now passes (e.g., its deps got
    installed), the record is upgraded from FAIL to PASS.
  - PASS results include the git commit hash for regression bisecting.
  - Results accumulate across runs (never discarded).
  - Multiple instances can run concurrently; random selection minimizes
    duplicate work. Each instance updates .dat files after each target.

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
  /tmp/cpan_random_logs/                    Per-module logs

Concurrent Execution:
  Yes, multiple instances can run simultaneously. They will:
  - Independently randomize which modules to test
  - Each write results to the shared .dat files
  - Minimize duplicate work (low probability of picking the same module)
  - Share results across instances (each reads the latest .dat on startup)

USAGE
}
