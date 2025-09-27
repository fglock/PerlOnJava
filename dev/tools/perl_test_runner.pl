#!/usr/bin/env perl
use strict;
use warnings;
use File::Find;
use File::Spec;
use Time::HiRes qw(time);
use Getopt::Long;
use JSON::PP;
use Data::Dumper;
use POSIX qw(WNOHANG);

# PerlOnJava Test Runner
# Runs standard Perl tests against PerlOnJava and analyzes results

my $jperl_path = './jperl';
my $timeout = 30; # Default to 30 seconds
my $jobs = 5;     # Default to 5 parallel jobs
my $output_file;
my $help;

GetOptions(
    'jperl=s'   => \$jperl_path,
    'timeout=f' => \$timeout,
    'jobs|j=i'  => \$jobs,
    'output=s'  => \$output_file,
    'help'      => \$help,
) or die "Error in command line arguments\n";

if ($help || @ARGV != 1) {
    print_usage();
    exit($help ? 0 : 1);
}

my $test_dir = $ARGV[0];

unless (-d $test_dir) {
    die "Error: Test directory '$test_dir' does not exist\n";
}

unless (-x $jperl_path) {
    die "Error: jperl not found or not executable at '$jperl_path'\n";
}

# Global state
my %results;
my %summary = (
    pass => 0, fail => 0, error => 0, timeout => 0,
    total_ok => 0, total_not_ok => 0, total_tests => 0,
    total_skipped => 0, total_todo => 0
);
my %feature_impact;

# Feature patterns for analysis
my %feature_patterns = (
    'regex' => [qw(regex regular.expression pattern.match qr m\/ s\/)],
    'references' => [qw(reference ref. arrayref hashref scalarref \\@ \\% \\$)],
    'objects' => [qw(bless ->new package.*:: \@ISA object method class)],
    'modules' => [qw(use.+ require.+ import module package)],
    'file_io' => [qw(open. close. print.+\w+ <\w+> file.handle filehandle)],
    'eval' => [qw(eval\{ eval\" eval. string.eval)],
    'closures' => [qw(sub\{ closure anonymous.sub lexical.closure)],
    'tie' => [qw(tie\s+ TIEHASH TIEARRAY TIESCALAR tied)],
    'formats' => [qw(format\s+\w+ write. formline)],
    'special_vars' => [qw(\$\$ \$! \$\? \$@ %ENV special.variable)],
    'subroutines' => [qw(sub\s+\w+ subroutine function.call \&\w+)],
    'arrays' => [qw(\@\w+ array push pop shift unshift splice)],
    'hashes' => [qw(%\w+ hash keys. values. each.)],
    'control_flow' => [qw(goto next last redo continue)],
    'prototypes' => [qw(prototype sub.*. function.prototype)],
);

# Find all .t files
print "Finding test files in $test_dir...\n";
my @test_files = find_test_files($test_dir);
my $total_files = @test_files;

print "Found $total_files test files\n";
print "Running tests with $jperl_path (${jobs} parallel jobs, ${timeout}s timeout)\n";
print "-" x 60, "\n";

# Run tests in parallel
run_tests_parallel(\@test_files, $test_dir);

print "-" x 60, "\n";
print_summary();
print_feature_impact();

if ($output_file) {
    save_results($output_file);
}

# Subroutines

sub find_test_files {
    my ($dir) = @_;
    my @files;

    find(sub {
        push @files, $File::Find::name if /\.t$/;
    }, $dir);

    return sort @files;
}

sub run_tests_parallel {
    my ($test_files, $test_dir) = @_;
    my $total_files = @$test_files;
    my $completed = 0;
    my %children;
    my @test_queue = @$test_files;

    # Don't use SIGCHLD handler - we'll poll instead
    local $SIG{CHLD} = 'DEFAULT';

    # Start initial batch of jobs
    while (@test_queue && keys(%children) < $jobs) {
        start_test_job(\@test_queue, \%children, $total_files, $completed);
    }

    # Wait for jobs to complete and start new ones
    while (%children || @test_queue) {
        # Check for completed children
        my @pids = keys %children;
        for my $pid (@pids) {
            my $res = waitpid($pid, WNOHANG);
            if ($res > 0) {
                # Child has exited
                my $test_info = delete $children{$pid};
                process_test_result($test_info, $test_dir);
                $completed++;

                # Start a new job if queue has items
                if (@test_queue && keys(%children) < $jobs) {
                    start_test_job(\@test_queue, \%children, $total_files, $completed);
                }
            } elsif ($res < 0) {
                # Error - child doesn't exist
                warn "Warning: Lost track of child $pid\n";
                delete $children{$pid};
            }
        }

        # Only sleep if we still have children running
        if (%children) {
            select(undef, undef, undef, 0.05);  # Short sleep
        }
    }
}

sub process_test_result {
    my ($test_info, $test_dir) = @_;

    # Look for result file from child
    my $temp_pattern = "/tmp/perl_test_*";
    my @temp_files = glob($temp_pattern);

    my $result_data;
    for my $temp_file (@temp_files) {
        if (-f $temp_file && open my $fh, '<', $temp_file) {
            local $/;
            my $json_data = <$fh>;
            close $fh;

            eval {
                $result_data = JSON::PP->new->decode($json_data);
            };

            if ($result_data && $result_data->{test_file} eq $test_info->{test_file}) {
                unlink $temp_file;
                last;
            }
        }
    }

    # Fallback if we couldn't read the result
    unless ($result_data) {
        $result_data = {
            test_file => $test_info->{test_file},
            test_index => $test_info->{test_index},
            result => {
                status => 'error',
                ok_count => 0, not_ok_count => 0, total_tests => 0,
                skip_count => 0, todo_count => 0,
                errors => ['Failed to get test result'], missing_features => []
            }
        };
    }

    my $test_file = $result_data->{test_file};
    my $test_index = $result_data->{test_index};
    my $result = $result_data->{result};

    my $rel_path = File::Spec->abs2rel($test_file, $test_dir);
    my $duration = time() - $test_info->{start_time};

    $result->{duration} = sprintf("%.2f", $duration);
    $result->{file} = $rel_path;
    $results{$rel_path} = $result;

    # Update summary
    $summary{$result->{status}}++;
    $summary{total_ok} += $result->{ok_count};
    $summary{total_not_ok} += $result->{not_ok_count};
    $summary{total_tests} += $result->{total_tests};
    $summary{total_skipped} += $result->{skip_count} || 0;
    $summary{total_todo} += $result->{todo_count} || 0;

    # Track feature impact
    for my $feature (@{$result->{missing_features}}) {
        push @{$feature_impact{$feature}}, $rel_path;
    }

    # Print result
    my %status_chars = (
        pass => '✓', fail => '✗', error => '!', timeout => 'T'
    );
    my $char = $status_chars{$result->{status}} || '?';

    printf "[%3d/%d] %s", $test_index, $total_files, $rel_path;
    print " " x (50 - length($rel_path)) if length($rel_path) < 50;
    printf " ... %s %d/%d ok (%.2fs)\n",
        $char, $result->{ok_count}, $result->{total_tests}, $duration;
}

# Single, clean run_single_test function
sub run_single_test {
    my ($test_file) = @_;

    # Temporarily disable fatal unimplemented errors
    # so we can run tests that mix implemented and unimplemented features
    local $ENV{JPERL_UNIMPLEMENTED} = $test_file =~ m{
        re/pat_rt_report.t
        | re/pat.t
        | op/pack.t
        | op/index.t
        | re/reg_pmod.t
        | op/sprintf.t }x
        ? "warn" : "";
    local $ENV{JPERL_OPTS} = $test_file =~ m{ re/pat.t }x
        ? "-Xss256m" : "";
    local $ENV{JPERL_LARGECODE} = $test_file =~ m{ re/pat.t | re/pat_advanced.t | op/signatures.t | t/op/pack.t }x
        ? "refactor" : "";

    # Save current directory
    my $old_dir = File::Spec->rel2abs('.');

    ## # Change to test directory for relative paths
    ## my $test_dir = $test_file;
    ## $test_dir =~ s{/[^/]+$}{};

    chdir($test_dir) if $test_dir && -d $test_dir;

    # Use absolute path for jperl
    my $abs_jperl = File::Spec->rel2abs($jperl_path, $old_dir);
    my $test_name = File::Spec->abs2rel($test_file, $test_dir || '.');

    # Try to use system timeout command if available
    my $timeout_cmd = '';
    if (system('which timeout >/dev/null 2>&1') == 0) {
        $timeout_cmd = "timeout ${timeout}s ";
    } elsif (system('which gtimeout >/dev/null 2>&1') == 0) {
        # macOS with coreutils
        $timeout_cmd = "gtimeout ${timeout}s ";
    }

    my $cmd = "${timeout_cmd}$abs_jperl $test_name 2>&1";

    # Capture output with timeout
    my $output = '';
    my $exit_code = 0;

    if ($timeout_cmd) {
        # Use external timeout
        $output = `$cmd`;
        $exit_code = $? >> 8;
    } else {
        # Fallback to alarm-based timeout
        eval {
            local $SIG{ALRM} = sub { die "timeout\n" };
            alarm($timeout);
            $output = `$abs_jperl $test_name 2>&1`;
            $exit_code = $? >> 8;
            alarm(0);
        };
        if ($@ && $@ =~ /timeout/) {
            $exit_code = 124;  # Same as timeout command
        }
    }

    # Restore directory
    chdir($old_dir);

    # Check if it was a timeout
    if ($exit_code == 124) {
        return {
            status => 'timeout',
            ok_count => 0, not_ok_count => 0, total_tests => 0,
            skip_count => 0, todo_count => 0,
            errors => ['Test timed out'], missing_features => []
        };
    }

    return parse_tap_output($output, $exit_code);
}

sub start_test_job {
    my ($test_queue, $children, $total_files, $completed) = @_;

    return unless @$test_queue;

    my $test_file = shift @$test_queue;
    my $test_index = $total_files - @$test_queue;

    my $pid = fork();
    if (!defined $pid) {
        die "Cannot fork: $!";
    } elsif ($pid == 0) {
        # Child process
        my $result = run_single_test($test_file);

        # Write result to temporary file for parent to read
        my $temp_file = "/tmp/perl_test_$$" . "_" . time() . "_" . rand(1000);
        if (open my $fh, '>', $temp_file) {
            print $fh JSON::PP->new->encode({
                test_file => $test_file,
                test_index => $test_index,
                result => $result
            });
            close $fh;
        }

        exit(0);
    } else {
        # Parent process
        $children->{$pid} = {
            test_file => $test_file,
            test_index => $test_index,
            start_time => time(),
        };
    }
}

sub parse_tap_output {
    my ($output, $exit_code) = @_;

    # Handle undefined output
    $output = '' unless defined $output;

    my @lines = split /\n/, $output;
    my ($ok_count, $not_ok_count, $total_tests) = (0, 0, 0);
    my ($skip_count, $todo_count) = (0, 0);
    my (@errors, @missing_features);

    # Parse TAP output
    for my $line (@lines) {
        $line =~ s/^\s+|\s+$//g;  # trim
        next unless $line;

        # Test plan
        if ($line =~ /^1\.\.(\d+)/) {
            $total_tests = $1;
            next;
        }

        # Test results
        if ($line =~ /^ok\s+\d+/) {
            $ok_count++;
            $skip_count++ if $line =~ /#\s*skip/i;
            $todo_count++ if $line =~ /#\s*todo/i;
            next;
        }

        if ($line =~ /^not ok\s+\d+/) {
            $not_ok_count++;
            next;
        }

        # Look for errors and missing features
        my $line_lower = lc($line);
        if ($line_lower =~ /error|fatal|died|exception|abort|not implemented|unimplemented|unsupported|syntax error|compilation failed|can't locate|undefined subroutine|bareword not allowed/) {
            push @errors, $line;

            # Identify missing features
            for my $feature (keys %feature_patterns) {
                for my $pattern (@{$feature_patterns{$feature}}) {
                    if ($line_lower =~ /$pattern/) {
                        push @missing_features, $feature;
                        last;
                    }
                }
            }
        }
    }

    # If no test plan found, use count
    $total_tests = $ok_count + $not_ok_count if $total_tests == 0;

    # Determine status
    my $status;
    if ($ok_count == 0 && $not_ok_count == 0) {
        $status = $exit_code == 0 ? 'pass' : 'error';
    } elsif ($not_ok_count == 0 && $ok_count > 0) {
        $status = 'pass';
    } elsif ($ok_count > 0) {
        $status = 'fail';
    } else {
        $status = 'error';
    }

    # Remove duplicates
    my %seen;
    @missing_features = grep !$seen{$_}++, @missing_features;

    return {
        status => $status,
        ok_count => $ok_count,
        not_ok_count => $not_ok_count,
        total_tests => $total_tests,
        skip_count => $skip_count,
        todo_count => $todo_count,
        errors => \@errors,
        missing_features => \@missing_features,
        exit_code => $exit_code,
    };
}

sub print_summary {
    print "\nTEST SUMMARY:\n";
    printf "  Total files: %d\n", $summary{pass} + $summary{fail} + $summary{error} + $summary{timeout};
    printf "  Passed:      %d\n", $summary{pass};
    printf "  Failed:      %d\n", $summary{fail};
    printf "  Errors:      %d\n", $summary{error};
    printf "  Timeouts:    %d\n", $summary{timeout};
    print "\n";
    printf "  Total tests: %d\n", $summary{total_tests};
    printf "  OK:          %d\n", $summary{total_ok};
    printf "  Not OK:      %d\n", $summary{total_not_ok};
    printf "  Skipped:     %d\n", $summary{total_skipped} if $summary{total_skipped};
    printf "  TODO:        %d\n", $summary{total_todo} if $summary{total_todo};

    if ($summary{total_tests} > 0) {
        my $pass_rate = ($summary{total_ok} / $summary{total_tests}) * 100;
        printf "  Pass rate:   %.1f%%\n", $pass_rate;
    }
}

sub print_feature_impact {
    return unless %feature_impact;

    print "\nFEATURE IMPACT ANALYSIS:\n";
    print "(Features that, if implemented, would likely improve the most tests)\n\n";

    # Sort features by impact
    my @sorted_features = sort { @{$feature_impact{$b}} <=> @{$feature_impact{$a}} }
                          keys %feature_impact;

    for my $i (0 .. 9) {  # Top 10
        last if $i >= @sorted_features;
        my $feature = $sorted_features[$i];
        my $count = @{$feature_impact{$feature}};
        printf "  %-15s - affects %3d test files\n", $feature, $count;
    }

    print "\nTop 3 features to prioritize:\n";
    for my $i (0 .. 2) {
        last if $i >= @sorted_features;
        my $feature = $sorted_features[$i];
        my $count = @{$feature_impact{$feature}};
        printf "  %d. %s: %d test files would benefit\n", $i + 1, $feature, $count;
    }
}

sub save_results {
    my ($filename) = @_;

    my $report = {
        timestamp => scalar(localtime),
        jperl_path => $jperl_path,
        summary => \%summary,
        feature_impact => \%feature_impact,
        results => \%results,
    };

    open my $fh, '>', $filename or die "Cannot write to $filename: $!\n";
    print $fh JSON::PP->new->pretty->encode($report);
    close $fh;

    print "\nDetailed results saved to: $filename\n";
}

sub print_usage {
    print <<"EOF";
Usage: $0 [OPTIONS] TEST_DIRECTORY

Run Perl tests against PerlOnJava

Options:
  --jperl PATH     Path to jperl executable (default: ./jperl)
  --timeout SEC    Timeout per test in seconds (default: 3)
  --jobs|-j NUM    Number of parallel jobs (default: 4)
  --output FILE    Save detailed results to JSON file
  --help           Show this help message

Examples:
  $0 ../perl5/t
  $0 --jperl ./jperl --timeout 5 --jobs 8 ../perl5/t
  $0 -j 2 --output results.json ../perl5/t
EOF
}
