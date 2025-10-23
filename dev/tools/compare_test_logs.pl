#!/usr/bin/env perl
use strict;
use warnings;
use Getopt::Long;

=head1 NAME

compare_test_logs.pl - Compare two test run logs to find regressions and progress

=head1 SYNOPSIS

    compare_test_logs.pl [options] <old_log> <new_log>
    
    Options:
      --min-diff N        Only show files with difference >= N tests (default: 1)
      --min-total N       Only show files with >= N total tests (default: 0)
      --show-progress     Show files with improvements (default: yes)
      --show-regressions  Show files with regressions (default: yes)
      --show-unchanged    Show files with no change (default: no)
      --summary-only      Only show summary statistics
      --sort-by FIELD     Sort by: name, diff, before, after (default: diff)
      --help              Show this help message

=head1 EXAMPLES

    # Compare two logs, show regressions and progress
    compare_test_logs.pl logs/test_20251022_101400 logs/test_20251022_154800
    
    # Only show significant changes (>= 10 tests difference)
    compare_test_logs.pl --min-diff 10 old.log new.log
    
    # Only show regressions in files with many tests
    compare_test_logs.pl --min-total 1000 --show-progress=0 old.log new.log
    
    # Quick summary only
    compare_test_logs.pl --summary-only old.log new.log

=cut

# Parse command line options
my $min_diff = 1;
my $min_total = 0;
my $show_progress = 1;
my $show_regressions = 1;
my $show_unchanged = 0;
my $summary_only = 0;
my $sort_by = 'diff';
my $help = 0;

GetOptions(
    'min-diff=i'        => \$min_diff,
    'min-total=i'       => \$min_total,
    'show-progress!'    => \$show_progress,
    'show-regressions!' => \$show_regressions,
    'show-unchanged!'   => \$show_unchanged,
    'summary-only'      => \$summary_only,
    'sort-by=s'         => \$sort_by,
    'help|h'            => \$help,
) or die "Error in command line arguments\n";

if ($help || @ARGV != 2) {
    print <<'END_HELP';
Usage: compare_test_logs.pl [options] <old_log> <new_log>

Compare two test run logs to identify regressions and improvements.

Options:
  --min-diff N        Only show files with difference >= N tests (default: 1)
  --min-total N       Only show files with >= N total tests (default: 0)
  --show-progress     Show files with improvements (default: yes)
  --show-regressions  Show files with regressions (default: yes)
  --show-unchanged    Show files with no change (default: no)
  --summary-only      Only show summary statistics
  --sort-by FIELD     Sort by: name, diff, before, after (default: diff)
  --help              Show this help message

Examples:
  compare_test_logs.pl logs/test_20251022_101400 logs/test_20251022_154800
  compare_test_logs.pl --min-diff 10 --min-total 1000 old.log new.log
  compare_test_logs.pl --summary-only old.log new.log
END_HELP
    exit($help ? 0 : 1);
}

my ($old_log, $new_log) = @ARGV;

# Parse a log file and extract test results
sub parse_log {
    my $file = shift;
    my %results;
    
    open my $fh, '<', $file or die "Cannot open $file: $!\n";
    while (<$fh>) {
        # Match lines like: [123/619] t/op/hash.t ... ✓ 26937/26942 ok (1.23s)
        if (/\]\s+(\S+\.t)\s+.*?(\d+)\/(\d+)\s+ok/) {
            my ($test, $passed, $total) = ($1, $2, $3);
            $results{$test} = {
                passed => $passed,
                total => $total,
            };
        }
    }
    close $fh;
    
    return %results;
}

# Parse both logs
print "Parsing logs...\n";
my %old_results = parse_log($old_log);
my %new_results = parse_log($new_log);

# Calculate statistics
my @changes;
my %stats = (
    total_old_tests => 0,
    total_new_tests => 0,
    total_old_passed => 0,
    total_new_passed => 0,
    files_with_regressions => 0,
    files_with_progress => 0,
    files_unchanged => 0,
    files_only_in_old => 0,
    files_only_in_new => 0,
    tests_lost => 0,
    tests_gained => 0,
);

# Find all test files
my %all_tests;
$all_tests{$_} = 1 for (keys %old_results, keys %new_results);

foreach my $test (keys %all_tests) {
    my $old = $old_results{$test};
    my $new = $new_results{$test};
    
    if (!$old) {
        # New test file
        $stats{files_only_in_new}++;
        $stats{total_new_tests} += $new->{total};
        $stats{total_new_passed} += $new->{passed};
        $stats{tests_gained} += $new->{passed};
        
        push @changes, {
            test => $test,
            old_passed => 0,
            old_total => 0,
            new_passed => $new->{passed},
            new_total => $new->{total},
            diff => $new->{passed},
            type => 'new',
        };
    } elsif (!$new) {
        # Test file removed
        $stats{files_only_in_old}++;
        $stats{total_old_tests} += $old->{total};
        $stats{total_old_passed} += $old->{passed};
        $stats{tests_lost} += $old->{passed};
        
        push @changes, {
            test => $test,
            old_passed => $old->{passed},
            old_total => $old->{total},
            new_passed => 0,
            new_total => 0,
            diff => -$old->{passed},
            type => 'removed',
        };
    } else {
        # Test file in both logs
        $stats{total_old_tests} += $old->{total};
        $stats{total_new_tests} += $new->{total};
        $stats{total_old_passed} += $old->{passed};
        $stats{total_new_passed} += $new->{passed};
        
        my $diff = $new->{passed} - $old->{passed};
        
        if ($diff > 0) {
            $stats{files_with_progress}++;
            $stats{tests_gained} += $diff;
        } elsif ($diff < 0) {
            $stats{files_with_regressions}++;
            $stats{tests_lost} += -$diff;
        } else {
            $stats{files_unchanged}++;
        }
        
        push @changes, {
            test => $test,
            old_passed => $old->{passed},
            old_total => $old->{total},
            new_passed => $new->{passed},
            new_total => $new->{total},
            diff => $diff,
            type => $diff > 0 ? 'progress' : $diff < 0 ? 'regression' : 'unchanged',
        };
    }
}

# Sort changes
my %sort_funcs = (
    name   => sub { $a->{test} cmp $b->{test} },
    diff   => sub { abs($b->{diff}) <=> abs($a->{diff}) || $a->{test} cmp $b->{test} },
    before => sub { $b->{old_passed} <=> $a->{old_passed} || $a->{test} cmp $b->{test} },
    after  => sub { $b->{new_passed} <=> $a->{new_passed} || $a->{test} cmp $b->{test} },
);

my $sort_func = $sort_funcs{$sort_by} || $sort_funcs{diff};
@changes = sort $sort_func @changes;

# Print summary
print "\n";
print "=" x 90 . "\n";
print "TEST LOG COMPARISON SUMMARY\n";
print "=" x 90 . "\n";
print "Old log: $old_log\n";
print "New log: $new_log\n";
print "\n";
printf "Total tests in old log:  %6d tests,  %6d passing  (%5.2f%%)\n",
    $stats{total_old_tests}, $stats{total_old_passed},
    $stats{total_old_tests} ? 100 * $stats{total_old_passed} / $stats{total_old_tests} : 0;
printf "Total tests in new log:  %6d tests,  %6d passing  (%5.2f%%)\n",
    $stats{total_new_tests}, $stats{total_new_passed},
    $stats{total_new_tests} ? 100 * $stats{total_new_passed} / $stats{total_new_tests} : 0;
print "\n";

my $net_change = $stats{total_new_passed} - $stats{total_old_passed};
my $change_symbol = $net_change >= 0 ? '+' : '';
printf "Net change:              %s%6d passing tests  (%s%5.2f%%)\n",
    $change_symbol, $net_change,
    $change_symbol, 
    $stats{total_old_passed} ? 100 * $net_change / $stats{total_old_passed} : 0;
print "\n";
printf "Files with regressions:  %4d files  (-%6d tests)\n",
    $stats{files_with_regressions}, $stats{tests_lost};
printf "Files with progress:     %4d files  (+%6d tests)\n",
    $stats{files_with_progress}, $stats{tests_gained};
printf "Files unchanged:         %4d files\n", $stats{files_unchanged};
printf "Files only in old log:   %4d files\n", $stats{files_only_in_old} if $stats{files_only_in_old};
printf "Files only in new log:   %4d files\n", $stats{files_only_in_new} if $stats{files_only_in_new};

if ($summary_only) {
    print "\n";
    exit 0;
}

# Print detailed changes
my @to_show = grep {
    my $c = $_;
    my $max_total = $c->{old_total} > $c->{new_total} ? $c->{old_total} : $c->{new_total};
    
    # Apply filters
    abs($c->{diff}) >= $min_diff &&
    $max_total >= $min_total &&
    !($c->{type} eq 'progress' && !$show_progress) &&
    !($c->{type} eq 'regression' && !$show_regressions) &&
    !($c->{type} eq 'unchanged' && !$show_unchanged);
} @changes;

if (@to_show) {
    print "\n";
    print "=" x 90 . "\n";
    print "DETAILED CHANGES\n";
    print "=" x 90 . "\n";
    printf "%-50s %15s %15s %10s\n", "Test File", "Before", "After", "Change";
    print "-" x 90 . "\n";
    
    foreach my $c (@to_show) {
        my $symbol = $c->{diff} > 0 ? '✓' : $c->{diff} < 0 ? '✗' : '=';
        my $change_str = $c->{diff} >= 0 ? sprintf("+%d", $c->{diff}) : sprintf("%d", $c->{diff});
        
        printf "%s %-47s %6d/%-6d %6d/%-6d %10s\n",
            $symbol,
            $c->{test},
            $c->{old_passed}, $c->{old_total},
            $c->{new_passed}, $c->{new_total},
            $change_str;
    }
    
    print "\n";
    printf "Showing %d of %d files with changes\n", scalar(@to_show), scalar(@changes);
    
    if (@to_show < @changes) {
        print "(Use --min-diff and --min-total to adjust filters)\n";
    }
}

print "\n";

