#!/usr/bin/env perl
#
# cpan_smoke_test.pl - CPAN Module Smoke Tester for PerlOnJava
#
# Runs jcpan -t on a curated list of CPAN modules and reports:
#   - Installation success/failure
#   - Test pass/fail counts
#   - Whether XS is needed/missing
#   - Regressions from previous runs
#
# Usage:
#   perl dev/tools/cpan_smoke_test.pl              # Run all modules
#   perl dev/tools/cpan_smoke_test.pl --quick      # Known-good only
#   perl dev/tools/cpan_smoke_test.pl --jobs 3     # Run 3 in parallel
#   perl dev/tools/cpan_smoke_test.pl Moo DateTime # Specific modules
#   perl dev/tools/cpan_smoke_test.pl --list       # List modules and exit
#
# Output:
#   - Summary table to stdout
#   - Detailed log to cpan_smoke_YYYYMMDD_HHMMSS.log
#
# Run with `perl` (not jperl) because this script uses fork.

use strict;
use warnings;
use File::Basename;
use File::Spec;
use Getopt::Long;
use Time::HiRes qw(time);
use POSIX qw(strftime WNOHANG);

my $script_dir = dirname(File::Spec->rel2abs($0));
my $project_root = File::Spec->catdir($script_dir, '..', '..');
my $jcpan = File::Spec->catfile($project_root, 'jcpan');
my $jperl = File::Spec->catfile($project_root, 'jperl');

# ──────────────────────────────────────────────────────────────────────
# Module registry
#
# Each entry: [module_name, category, xs_status, env_vars, notes]
#   category:  'known-good' | 'partial' | 'blocked' | 'untested'
#   xs_status: 'pure-perl' | 'java-xs' | 'xs-with-pp-fallback' | 'xs-required'
#   env_vars:  hashref of env vars to set, or undef
#   notes:     short description
# ──────────────────────────────────────────────────────────────────────
my @MODULE_REGISTRY = (
    # ── Known-good: pure Perl modules ──
    ['Test::Deep',              'known-good', 'pure-perl',         undef, '1266/1268 subtests'],
    ['Try::Tiny',               'partial',    'pure-perl',         undef, '91/94 subtests (fork test fails)'],
    ['Test::Fatal',             'known-good', 'pure-perl',         undef, 'Test exception helpers'],
    ['MIME::Base32',            'known-good', 'pure-perl',         undef, 'Base32 encoding'],
    ['HTML::Tagset',            'known-good', 'pure-perl',         undef, 'HTML element classification'],
    ['Test::Warn',              'partial',    'pure-perl',         undef, 'Dep test issues with Sub::Uplevel'],
    ['Path::Tiny',              'partial',    'pure-perl',         undef, '1489/1542 subtests'],
    ['namespace::clean',        'known-good', 'pure-perl',         undef, 'Namespace cleanup'],

    # ── Known-good: Java XS implementations ──
    ['DateTime',                'known-good', 'java-xs',           undef, 'Date/time (Java java.time backend)'],
    ['Spreadsheet::ParseExcel', 'known-good', 'java-xs',           undef, '32/32 tests pass'],
    ['IO::Stringy',             'known-good', 'pure-perl',         undef, '8/8 tests pass'],

    # ── Known-good: XS with PP fallback ──
    ['Moo',                     'known-good', 'xs-with-pp-fallback', undef, '96% tests pass'],

    # ── Partial: modules that install but have test failures ──
    ['MIME::Base64',            'partial',    'java-xs',           undef, '$VERSION undef, missing url variants'],
    ['URI',                     'partial',    'pure-perl',         undef, '896/947 subtests, UTF-8 encoding issues'],
    ['IO::HTML',                'partial',    'pure-perl',         undef, '32/52 subtests, File::Temp close + encoding names'],
    ['LWP::MediaTypes',         'partial',    'pure-perl',         undef, '41/47 subtests, MIME type differences'],
    ['Test::Needs',             'partial',    'pure-perl',         undef, '200/227 subtests, exit code handling'],
    ['Test::Warnings',          'partial',    'pure-perl',         undef, '86/88 subtests'],
    ['Encode::Locale',          'partial',    'pure-perl',         undef, 'Unknown encoding: locale'],
    ['Log::Log4perl',           'partial',    'pure-perl',         undef, 'Mostly works'],

    # ── Blocked: need fixes before they can work ──
    ['Devel::Cover',            'blocked',    'xs-required',       undef, 'Blocked on HTML::Entities dep chain'],
    ['HTTP::Message',           'blocked',    'pure-perl',         undef, 'Blocked on Clone::PP missing'],
    ['HTML::Parser',            'blocked',    'xs-required',       undef, 'XS module, no Java backend'],
    ['IO::Compress::Gzip',      'blocked',    'xs-required',       undef, 'Needs Compress::Raw::Zlib'],
    ['Moose',                   'blocked',    'xs-required',       undef, 'Needs B module subroutine names'],

    # ── XS with PP fallback, need env vars ──
    ['Params::Util',            'partial',    'xs-with-pp-fallback', { PERL_PARAMS_UTIL_PP => 1 }, 'Needs PP env var'],
    ['Class::Load',             'partial',    'xs-with-pp-fallback', { PERL_PARAMS_UTIL_PP => 1 }, 'Needs Params::Util PP'],
);

# ──────────────────────────────────────────────────────────────────────
# CLI options
# ──────────────────────────────────────────────────────────────────────
my $quick_mode = 0;
my $list_mode = 0;
my $timeout = 300;
my $jobs = 1;       # sequential by default (jcpan uses jperl which is heavy)
my $output_file;
my $compare_file;
my $help = 0;

GetOptions(
    'quick'     => \$quick_mode,
    'list'      => \$list_mode,
    'timeout=i' => \$timeout,
    'jobs|j=i'  => \$jobs,
    'output=s'  => \$output_file,
    'compare=s' => \$compare_file,
    'help|h'    => \$help,
) or die "Error in command line arguments\n";

if ($help) {
    print_usage();
    exit 0;
}

# Select modules to test
my @modules;
if (@ARGV) {
    # Specific modules from command line
    my %registry = map { $_->[0] => $_ } @MODULE_REGISTRY;
    for my $name (@ARGV) {
        if ($registry{$name}) {
            push @modules, $registry{$name};
        } else {
            # Unknown module - test it anyway as 'untested'
            push @modules, [$name, 'untested', 'unknown', undef, 'User-specified'];
        }
    }
} elsif ($quick_mode) {
    @modules = grep { $_->[1] eq 'known-good' } @MODULE_REGISTRY;
} else {
    @modules = @MODULE_REGISTRY;
}

if ($list_mode) {
    print_module_list();
    exit 0;
}

if (!@modules) {
    die "No modules selected. Use --help for usage.\n";
}

# ──────────────────────────────────────────────────────────────────────
# Setup log file
# ──────────────────────────────────────────────────────────────────────
my $timestamp = strftime('%Y%m%d_%H%M%S', localtime);
$output_file //= "cpan_smoke_${timestamp}.log";
open my $log_fh, '>', $output_file or die "Cannot open $output_file: $!\n";

log_msg("CPAN Smoke Test - " . strftime('%Y-%m-%d %H:%M:%S', localtime));
log_msg("Project root: $project_root");
log_msg("Modules to test: " . scalar(@modules));
log_msg("Timeout per module: ${timeout}s");
log_msg("=" x 70);

# ──────────────────────────────────────────────────────────────────────
# Load previous results for comparison
# ──────────────────────────────────────────────────────────────────────
my %previous;
if ($compare_file && -f $compare_file) {
    %previous = load_previous_results($compare_file);
    log_msg("Loaded previous results from $compare_file (" . scalar(keys %previous) . " modules)");
}

# ──────────────────────────────────────────────────────────────────────
# Run tests
# ──────────────────────────────────────────────────────────────────────
my @results;
my $total_start = time();

if ($jobs > 1) {
    @results = run_parallel(\@modules, $jobs);
} else {
    @results = run_sequential(\@modules);
}

# Check for regressions
for my $result (@results) {
    if ($previous{$result->{module}}) {
        my $prev = $previous{$result->{module}};
        if ($result->{status} eq 'FAIL' && $prev->{status} eq 'PASS') {
            $result->{regression} = 'REGRESSED';
        } elsif ($result->{status} eq 'PASS' && $prev->{status} eq 'FAIL') {
            $result->{regression} = 'FIXED';
        } elsif (($result->{pass_count} // 0) < ($prev->{pass_count} // 0)) {
            $result->{regression} = 'WORSE';
        } elsif (($result->{pass_count} // 0) > ($prev->{pass_count} // 0)) {
            $result->{regression} = 'IMPROVED';
        }
    }
}

my $total_elapsed = sprintf('%.1f', time() - $total_start);

# ──────────────────────────────────────────────────────────────────────
# Print summary
# ──────────────────────────────────────────────────────────────────────
print "\n";
print_summary(\@results, $total_elapsed);

# Also write summary to log
my $summary = format_summary(\@results, $total_elapsed);
print $log_fh $summary;

# Write machine-readable results for future --compare
write_results_data(\@results);

close $log_fh;
print "\nDetailed log: $output_file\n";
print "Results data: cpan_smoke_${timestamp}.dat\n";

# Exit with non-zero if any known-good module failed
my @regressions = grep { $_->{regression} && $_->{regression} eq 'REGRESSED' } @results;
my @known_good_fails = grep { $_->{category} eq 'known-good' && $_->{status} ne 'PASS' } @results;
exit(1) if @regressions || @known_good_fails;
exit(0);


# ══════════════════════════════════════════════════════════════════════
# Test runners
# ══════════════════════════════════════════════════════════════════════

sub run_sequential {
    my ($modules) = @_;
    my @results;

    for my $mod (@$modules) {
        my ($name, $category, $xs_status, $env_vars, $notes) = @$mod;
        my $start = time();

        print "Testing $name ... ";
        log_msg("\n" . "-" x 70);
        log_msg("MODULE: $name ($category, $xs_status)");

        my $result = run_jcpan_test($name, $env_vars);
        $result->{module}    = $name;
        $result->{category}  = $category;
        $result->{xs_status} = $xs_status;
        $result->{notes}     = $notes;
        $result->{elapsed}   = sprintf('%.1f', time() - $start);

        push @results, $result;
        print_inline_status($result);

        log_msg("  Status: $result->{status}");
        log_msg("  Configure: $result->{configure}") if $result->{configure};
        log_msg("  Tests: $result->{pass_count}/$result->{total_count} subtests") if defined $result->{total_count};
        log_msg("  XS needed: $result->{xs_detected}") if $result->{xs_detected};
        log_msg("  Error: $result->{error}") if $result->{error};
        log_msg("  Elapsed: $result->{elapsed}s");
    }

    return @results;
}

sub run_parallel {
    my ($modules, $max_jobs) = @_;
    my @results;
    my %children;  # pid => { mod => ..., tmpfile => ... }
    my $running = 0;

    require File::Temp;

    print "Running $max_jobs jobs in parallel...\n\n";
    log_msg("Parallel mode: $max_jobs jobs");

    my @queue = @$modules;

    while (@queue || $running > 0) {
        # Launch jobs up to $max_jobs
        while (@queue && $running < $max_jobs) {
            my $mod = shift @queue;
            my ($name, $category, $xs_status, $env_vars, $notes) = @$mod;

            # Create temp file for child output (module name in filename for debugging)
            (my $safe_name = $name) =~ s/::/_/g;
            my $tmpfile = File::Temp::tmpnam() . "_${safe_name}.out";

            my $pid = fork();
            if (!defined $pid) {
                warn "fork failed for $name: $!\n";
                push @results, {
                    module => $name, category => $category, xs_status => $xs_status,
                    notes => $notes, status => 'FORK_FAIL', error => "fork: $!",
                    elapsed => '0', configure => '', pass_count => undef,
                    total_count => undef, xs_detected => '',
                };
                next;
            }

            if ($pid == 0) {
                # ── Child process ──
                my $start = time();
                my $result = run_jcpan_test($name, $env_vars);
                $result->{module}    = $name;
                $result->{category}  = $category;
                $result->{xs_status} = $xs_status;
                $result->{notes}     = $notes;
                $result->{elapsed}   = sprintf('%.1f', time() - $start);

                # Write result to temp file as tab-separated values
                open my $fh, '>', $tmpfile or exit(1);
                print $fh join("\t",
                    $result->{module}      // '',
                    $result->{status}      // 'UNKNOWN',
                    $result->{configure}   // '',
                    $result->{pass_count}  // '',
                    $result->{total_count} // '',
                    $result->{xs_detected} // '',
                    $result->{error}       // '',
                    $result->{elapsed}     // '0',
                    $result->{category}    // '',
                    $result->{xs_status}   // '',
                    $result->{notes}       // '',
                ), "\n";
                close $fh;
                exit(0);
            }

            # ── Parent process ──
            $children{$pid} = { mod => $mod, tmpfile => $tmpfile, start => time() };
            $running++;
            print "  Started: $name (pid $pid)\n";
        }

        # Wait for at least one child to finish
        if ($running > 0) {
            my $pid = waitpid(-1, 0);
            if ($pid > 0 && $children{$pid}) {
                $running--;
                my $info = delete $children{$pid};
                my ($name, $category, $xs_status, $env_vars, $notes) = @{$info->{mod}};

                my $result;
                if (-f $info->{tmpfile}) {
                    $result = read_child_result($info->{tmpfile});
                    unlink $info->{tmpfile};
                } else {
                    $result = {
                        module => $name, status => 'CHILD_FAIL', configure => '',
                        pass_count => undef, total_count => undef,
                        xs_detected => '', error => 'Child produced no output',
                        elapsed => sprintf('%.1f', time() - $info->{start}),
                        category => $category, xs_status => $xs_status, notes => $notes,
                    };
                }

                push @results, $result;
                print_inline_status($result);

                log_msg("\n" . "-" x 70);
                log_msg("MODULE: $name ($category, $xs_status)");
                log_msg("  Status: $result->{status}");
                log_msg("  Configure: $result->{configure}") if $result->{configure};
                log_msg("  Tests: $result->{pass_count}/$result->{total_count} subtests") if defined $result->{total_count};
                log_msg("  XS needed: $result->{xs_detected}") if $result->{xs_detected};
                log_msg("  Error: $result->{error}") if $result->{error};
                log_msg("  Elapsed: $result->{elapsed}s");
            }
        }
    }

    return @results;
}

sub read_child_result {
    my ($tmpfile) = @_;
    open my $fh, '<', $tmpfile or return { status => 'READ_FAIL', error => "Cannot read $tmpfile: $!" };
    my $line = <$fh>;
    close $fh;
    chomp $line if defined $line;
    return { status => 'READ_FAIL', error => 'Empty result file' } unless $line;

    my @fields = split /\t/, $line, -1;
    return {
        module      => $fields[0],
        status      => $fields[1] || 'UNKNOWN',
        configure   => $fields[2] || '',
        pass_count  => $fields[3] ne '' ? $fields[3] : undef,
        total_count => $fields[4] ne '' ? $fields[4] : undef,
        xs_detected => $fields[5] || '',
        error       => $fields[6] || '',
        elapsed     => $fields[7] || '0',
        category    => $fields[8] || '',
        xs_status   => $fields[9] || '',
        notes       => $fields[10] || '',
    };
}

sub print_inline_status {
    my ($result) = @_;
    my $status_str = $result->{status};
    if ($result->{pass_count} || $result->{total_count}) {
        $status_str .= " ($result->{pass_count}/$result->{total_count} subtests)";
    }
    if ($result->{regression}) {
        $status_str .= " [$result->{regression}]";
    }
    printf "  %-28s %s (%ss)\n", $result->{module}, $status_str, $result->{elapsed};
}


# ══════════════════════════════════════════════════════════════════════
# Subroutines
# ══════════════════════════════════════════════════════════════════════

sub run_jcpan_test {
    my ($module, $env_vars) = @_;

    my %result = (
        status      => 'UNKNOWN',
        configure   => '',
        pass_count  => undef,
        total_count => undef,
        xs_detected => '',
        error       => '',
    );

    # Build command with optional env vars
    my $env_prefix = '';
    if ($env_vars && ref $env_vars eq 'HASH') {
        $env_prefix = join(' ', map { "$_=$env_vars->{$_}" } keys %$env_vars) . ' ';
    }

    my $cmd = "${env_prefix}$jcpan -t $module 2>&1";

    # Run with timeout using fork
    my $output = '';
    my $exit_code;
    eval {
        local $SIG{ALRM} = sub { die "TIMEOUT\n" };
        alarm($timeout);

        $output = `$cmd`;
        $exit_code = $? >> 8;

        alarm(0);
    };
    if ($@ && $@ =~ /TIMEOUT/) {
        $result{status} = 'TIMEOUT';
        $result{error} = "Exceeded ${timeout}s timeout";
        log_msg("  OUTPUT (truncated - timeout):\n$output") if $output;
        return \%result;
    }

    # Log full output
    log_msg("  FULL OUTPUT:");
    for my $line (split /\n/, $output) {
        print $log_fh "    $line\n";
    }

    # Parse output
    parse_jcpan_output($output, \%result, $exit_code);

    return \%result;
}

sub parse_jcpan_output {
    my ($output, $result, $exit_code) = @_;

    # Check configuration status
    if ($output =~ /Makefile\.PL -- OK/) {
        $result->{configure} = 'OK';
    } elsif ($output =~ /Makefile\.PL -- NOT OK/) {
        $result->{configure} = 'FAIL';
        # Extract the error
        if ($output =~ /^(.+)\n.*Makefile\.PL -- NOT OK/m) {
            ($result->{error}) = $output =~ /^((?:Can't|"[^"]+"|syntax error).+)$/m;
        }
    }

    # Detect XS
    if ($output =~ /XS MODULE:/) {
        $result->{xs_detected} = 'yes';
        # Capture XS files
        my @xs_files;
        while ($output =~ /^\s+-\s+(\S+\.(?:xs|c))$/mg) {
            push @xs_files, $1;
        }
        $result->{xs_files} = \@xs_files if @xs_files;
    }

    # Detect XS load failures in tests
    if ($output =~ /Can't load module|loadable object|XSLoader::load/) {
        $result->{xs_detected} ||= 'load-failure';
    }

    # Parse test results from Test::Harness output
    # IMPORTANT: jcpan -t runs tests for dependencies too, so we may see
    # multiple "Result:" and "Files=" lines. We isolate the LAST test block
    # (after the last "Running make test") which corresponds to the target module.
    my $last_test_block = $output;
    if ($output =~ /Running make test/) {
        my @blocks = split /Running make test[^\n]*\n/, $output;
        $last_test_block = $blocks[-1] if @blocks > 1;
    }

    my $total = 0;
    my $last_result = '';

    # Find "Files=N, Tests=M" from the last test block only
    if ($last_test_block =~ /Files=(\d+), Tests=(\d+)/) {
        $total = $2;
    }

    # Find the last "Result:" line from the last test block
    my @result_lines;
    while ($last_test_block =~ /^\s*(Result:\s+\S+)/mg) {
        push @result_lines, $1;
    }
    $last_result = $result_lines[-1] || '';

    # Count passed/failed test FILES from harness output
    # Harness formats: "t/foo.t ... ok" (same line) or "t/foo.t ...\n  Failed X/Y" (next line)
    my $test_files_pass = 0;
    my $test_files_fail = 0;
    my $test_files_skip = 0;
    my $subtests_fail = 0;
    # Single-line format: "t/foo.t ... ok" or "t/foo.t ... Failed X/Y subtests"
    while ($last_test_block =~ /^\s*\S+\.t\s+\.+\s*(ok|skipped|Failed\s+(\d+)\/(\d+))/mg) {
        if ($1 eq 'ok') {
            $test_files_pass++;
        } elsif ($1 =~ /^skipped/) {
            $test_files_skip++;
        } else {
            $test_files_fail++;
            $subtests_fail += $2 if $2;
        }
    }
    # Multi-line format: "Failed X/Y subtests" on its own line (after error output)
    if ($test_files_fail == 0) {
        while ($last_test_block =~ /^\s*Failed\s+(\d+)\/(\d+)\s+subtests/mg) {
            $test_files_fail++;
            $subtests_fail += $1;
        }
    }

    # Count individual "ok N" / "not ok N" TAP lines from last test block
    my $ok_count = 0;
    my $not_ok_count = 0;
    while ($last_test_block =~ /^\s*(not )?ok \d+/mg) {
        if ($1) { $not_ok_count++ } else { $ok_count++ }
    }

    # Use the last Result line
    if ($last_result =~ /PASS/ || $last_test_block =~ /All tests successful/) {
        $result->{status} = 'PASS';
        $result->{pass_count} = $total || $ok_count;
        $result->{total_count} = $total || ($ok_count + $not_ok_count);
        return;
    }

    if ($last_result =~ /FAIL/) {
        $result->{status} = 'FAIL';
        if ($total > 0) {
            # subtests_fail may exceed total when files crash and report inflated counts
            my $pass = $total > $subtests_fail ? $total - $subtests_fail : 0;
            $result->{pass_count}  = $pass;
            $result->{total_count} = $total;
        } elsif ($ok_count + $not_ok_count > 0) {
            $result->{pass_count}  = $ok_count;
            $result->{total_count} = $ok_count + $not_ok_count;
        }
        return;
    }

    # Detect common failure patterns (search full output for these)
    if ($output =~ /not imported|not exported/) {
        $result->{error} ||= 'Import/export error';
    }
    if ($output =~ /Can't locate (\S+\.pm)/m) {
        $result->{error} ||= "Missing: $1";
    }
    if ($output =~ /syntax error/m) {
        $result->{error} ||= 'Syntax error';
    }
    if ($output =~ /StackOverflowError/m) {
        $result->{error} ||= 'StackOverflowError';
    }

    # Determine overall status from whatever we found
    if ($test_files_pass > 0 || $ok_count > 0) {
        if ($test_files_fail == 0 && $not_ok_count == 0) {
            $result->{status} = 'PASS';
        } else {
            $result->{status} = 'FAIL';
        }
        if ($total > 0) {
            $result->{pass_count}  = $total - $subtests_fail;
            $result->{total_count} = $total;
        } else {
            $result->{pass_count}  = $ok_count;
            $result->{total_count} = $ok_count + $not_ok_count;
        }
    } elsif ($result->{configure} eq 'FAIL') {
        $result->{status} = 'CONFIG_FAIL';
    } elsif ($exit_code) {
        $result->{status} = 'FAIL';
    } else {
        # Installed but no tests ran (or we couldn't parse)
        if ($output =~ /Installation complete/) {
            $result->{status} = 'INSTALLED';
        } else {
            $result->{status} = 'UNKNOWN';
        }
    }
}

sub print_summary {
    my ($results, $elapsed) = @_;
    print format_summary($results, $elapsed);
}

sub format_summary {
    my ($results, $elapsed) = @_;
    my $out = '';

    $out .= "=" x 90 . "\n";
    $out .= "CPAN SMOKE TEST SUMMARY\n";
    $out .= "=" x 90 . "\n\n";

    # Header
    $out .= sprintf("%-28s %-12s %-12s %-22s %s\n",
        'Module', 'Status', 'Tests', 'XS', 'Notes');
    $out .= "-" x 90 . "\n";

    # Group by category
    my %by_cat;
    for my $r (@$results) {
        push @{$by_cat{$r->{category}}}, $r;
    }

    my @cat_order = ('known-good', 'partial', 'blocked', 'untested');
    for my $cat (@cat_order) {
        next unless $by_cat{$cat};
        $out .= "\n  -- $cat --\n";
        for my $r (sort { $a->{module} cmp $b->{module} } @{$by_cat{$cat}}) {
            my $status = $r->{status};
            if ($r->{regression}) {
                $status .= " [$r->{regression}]";
            }

            my $tests = '';
            if (defined $r->{total_count} && $r->{total_count} > 0) {
                $tests = "$r->{pass_count}/$r->{total_count}";
            }

            my $xs = $r->{xs_status} || '';
            if ($r->{xs_detected}) {
                $xs .= " (detected)" if $r->{xs_detected} eq 'yes';
                $xs .= " (LOAD FAIL)" if $r->{xs_detected} eq 'load-failure';
            }

            my $notes = $r->{error} || $r->{notes} || '';
            # Truncate long notes
            $notes = substr($notes, 0, 30) . '...' if length($notes) > 33;

            $out .= sprintf("  %-26s %-12s %-12s %-22s %s\n",
                $r->{module}, $status, $tests, $xs, $notes);
        }
    }

    $out .= "\n" . "-" x 90 . "\n";

    # Totals
    my $pass_count  = scalar grep { $_->{status} eq 'PASS' } @$results;
    my $fail_count  = scalar grep { $_->{status} =~ /FAIL/ } @$results;
    my $other_count = scalar(@$results) - $pass_count - $fail_count;

    $out .= sprintf("\nTotal: %d modules tested in %ss\n", scalar @$results, $elapsed);
    $out .= sprintf("  PASS: %d  |  FAIL: %d  |  Other: %d\n", $pass_count, $fail_count, $other_count);

    # Highlight regressions
    my @regressions = grep { $_->{regression} && $_->{regression} eq 'REGRESSED' } @$results;
    if (@regressions) {
        $out .= "\n  *** REGRESSIONS DETECTED ***\n";
        for my $r (@regressions) {
            $out .= "    $r->{module}: was PASS, now $r->{status}\n";
        }
    }

    # Highlight improvements
    my @improved = grep { $_->{regression} && ($_->{regression} eq 'FIXED' || $_->{regression} eq 'IMPROVED') } @$results;
    if (@improved) {
        $out .= "\n  Improvements:\n";
        for my $r (@improved) {
            $out .= "    $r->{module}: $r->{regression}\n";
        }
    }

    # XS summary
    my @xs_needed = grep { $_->{xs_detected} } @$results;
    if (@xs_needed) {
        $out .= "\n  XS Modules (detected during test):\n";
        for my $r (sort { $a->{module} cmp $b->{module} } @xs_needed) {
            my $java_impl = $r->{xs_status} =~ /java/ ? 'Java impl' : 'NO Java impl';
            $out .= sprintf("    %-30s %s\n", $r->{module}, $java_impl);
        }
    }

    $out .= "\n";
    return $out;
}

sub write_results_data {
    my ($results) = @_;
    my $dat_file = "cpan_smoke_${timestamp}.dat";
    open my $fh, '>', $dat_file or do {
        warn "Cannot write $dat_file: $!\n";
        return;
    };
    for my $r (@$results) {
        printf $fh "%s\t%s\t%s\t%s\t%s\n",
            $r->{module},
            $r->{status},
            defined $r->{pass_count} ? $r->{pass_count} : '',
            defined $r->{total_count} ? $r->{total_count} : '',
            $r->{xs_status} || '';
    }
    close $fh;
}

sub load_previous_results {
    my ($file) = @_;
    my %results;
    open my $fh, '<', $file or return %results;
    while (<$fh>) {
        chomp;
        my ($mod, $status, $pass, $total, $xs) = split /\t/;
        $results{$mod} = {
            status     => $status,
            pass_count => $pass ne '' ? $pass : undef,
            total_count => $total ne '' ? $total : undef,
            xs_status  => $xs,
        };
    }
    close $fh;
    return %results;
}

sub print_module_list {
    printf "%-28s %-12s %-22s %s\n", 'Module', 'Category', 'XS Status', 'Notes';
    print "-" x 90, "\n";
    for my $mod (@MODULE_REGISTRY) {
        my ($name, $cat, $xs, $env, $notes) = @$mod;
        my $env_str = $env ? ' [env]' : '';
        printf "%-28s %-12s %-22s %s%s\n", $name, $cat, $xs, $notes, $env_str;
    }
    printf "\nTotal: %d modules registered\n", scalar @MODULE_REGISTRY;
}

sub log_msg {
    my ($msg) = @_;
    print $log_fh "$msg\n" if $log_fh;
}

sub print_usage {
    print <<'USAGE';
cpan_smoke_test.pl - CPAN Module Smoke Tester for PerlOnJava

Usage:
  perl dev/tools/cpan_smoke_test.pl [options] [module ...]

Options:
  --quick       Test only known-good modules (regression check)
  --list        List all registered modules and exit
  --timeout N   Timeout per module in seconds (default: 300)
  --jobs N, -j N  Run N modules in parallel (default: 1, sequential)
  --output FILE Log file path (default: cpan_smoke_TIMESTAMP.log)
  --compare FILE  Compare with previous .dat file to detect regressions
  --help        Show this help

Examples:
  # Full smoke test (all registered modules)
  perl dev/tools/cpan_smoke_test.pl

  # Quick regression check (known-good only)
  perl dev/tools/cpan_smoke_test.pl --quick

  # Run 3 modules in parallel
  perl dev/tools/cpan_smoke_test.pl --jobs 3

  # Test specific modules
  perl dev/tools/cpan_smoke_test.pl Moo DateTime Try::Tiny

  # Compare with previous run
  perl dev/tools/cpan_smoke_test.pl --compare cpan_smoke_20250331.dat

  # Test an unregistered module
  perl dev/tools/cpan_smoke_test.pl Some::New::Module

Exit codes:
  0  All known-good modules passed, no regressions
  1  Regressions detected or known-good module failed
USAGE
}
