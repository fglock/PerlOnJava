#!/usr/bin/perl
use strict;
use warnings;
use JSON;
use Data::Dumper;

sub main {
    my ($file1, $file2) = @ARGV;
    
    unless ($file1 && $file2) {
        die "Usage: $0 <json_file1> <json_file2>\n";
    }
    
    # Load JSON files
    my $data1 = load_json($file1);
    my $data2 = load_json($file2);
    
    my $results1 = $data1->{results};
    my $results2 = $data2->{results};
    
    # Determine which is better
    my $stats1 = count_passing_tests($results1);
    my $stats2 = count_passing_tests($results2);
    
    my ($worse_results, $better_results, $worse_file, $better_file);
    if ($stats1 < $stats2) {
        ($worse_results, $better_results) = ($results1, $results2);
        ($worse_file, $better_file) = ($file1, $file2);
    } else {
        ($worse_results, $better_results) = ($results2, $results1);
        ($worse_file, $better_file) = ($file2, $file1);
    }
    
    print "Comparing test results:\n";
    print "Better version: $better_file ($stats2 passing individual tests)\n";
    print "Worse version:  $worse_file ($stats1 passing individual tests)\n";
    print "Gap: " . abs($stats2 - $stats1) . " tests\n\n";
    
    # Find specific differences
    analyze_specific_failures($worse_results, $better_results, $worse_file, $better_file);
}

sub load_json {
    my $filename = shift;
    open my $fh, '<', $filename or die "Cannot open $filename: $!";
    my $content = do { local $/; <$fh> };
    close $fh;
    return decode_json($content);
}

sub count_passing_tests {
    my $results = shift;
    my $total = 0;
    for my $test_name (keys %$results) {
        my $test = $results->{$test_name};
        $total += $test->{ok_count} // 0;
    }
    return $total;
}

sub analyze_specific_failures {
    my ($worse_results, $better_results, $worse_file, $better_file) = @_;
    
    my @critical_failures;
    
    for my $test_file (sort keys %$better_results) {
        my $better_test = $better_results->{$test_file};
        my $worse_test = $worse_results->{$test_file} // {};
        
        my $better_ok = $better_test->{ok_count} // 0;
        my $worse_ok = $worse_test->{ok_count} // 0;
        
        # Skip if no difference
        next if $better_ok == $worse_ok;
        
        # This test passes more in better version
        if ($better_ok > $worse_ok) {
            my $failure_info = {
                test_file => $test_file,
                better_ok => $better_ok,
                worse_ok => $worse_ok,
                gap => $better_ok - $worse_ok,
                better_test => $better_test,
                worse_test => $worse_test,
                failure_reason => determine_failure_reason($worse_test, $better_test)
            };
            push @critical_failures, $failure_info;
        }
    }
    
    # Sort by gap (impact)
    @critical_failures = sort { $b->{gap} <=> $a->{gap} } @critical_failures;
    
    print "="x80 . "\n";
    print "SPECIFIC FAILURES TO FIX (sorted by test impact)\n";
    print "="x80 . "\n\n";
    
    my $total_recoverable = 0;
    for my $failure (@critical_failures) {
        $total_recoverable += $failure->{gap};
    }
    
    print "Total recoverable tests: $total_recoverable\n\n";
    
    for my $i (0..$#critical_failures) {
        my $failure = $critical_failures[$i];
        
        printf "%d. %s\n", $i+1, $failure->{test_file};
        printf "   Impact: +%d tests (from %d to %d)\n", 
               $failure->{gap}, $failure->{worse_ok}, $failure->{better_ok};
        printf "   Better version status: %s\n", $failure->{better_test}{status} // 'unknown';
        printf "   Worse version status:  %s\n", $failure->{worse_test}{status} // 'unknown';
        
        print "   WHY IT FAILED:\n";
        print_failure_details($failure);
        
        print "\n" . "-"x60 . "\n\n";
    }
}

sub determine_failure_reason {
    my ($worse_test, $better_test) = @_;
    
    my $worse_status = $worse_test->{status} // 'missing';
    my $better_status = $better_test->{status} // 'unknown';
    
    # If test didn't run at all
    if (!%$worse_test || $worse_status eq 'missing') {
        return 'test_not_executed';
    }
    
    # If it's a compilation/runtime error
    if ($worse_status eq 'error') {
        return 'execution_error';
    }
    
    # If tests failed
    if ($worse_status eq 'fail') {
        if ($worse_test->{missing_features} && @{$worse_test->{missing_features}}) {
            return 'missing_features';
        }
        return 'test_failures';
    }
    
    return 'unknown';
}

sub print_failure_details {
    my $failure = shift;
    my $worse_test = $failure->{worse_test};
    my $better_test = $failure->{better_test};
    
    # Show missing features
    if ($worse_test->{missing_features} && @{$worse_test->{missing_features}}) {
        print "     MISSING FEATURES: " . join(", ", @{$worse_test->{missing_features}}) . "\n";
    }
    
    # Show errors
    if ($worse_test->{errors} && @{$worse_test->{errors}}) {
        print "     ERRORS:\n";
        for my $error (@{$worse_test->{errors}}) {
            print "       - $error\n";
        }
    }
    
    # Show execution details
    my $worse_total = ($worse_test->{ok_count} // 0) + ($worse_test->{not_ok_count} // 0);
    my $better_total = ($better_test->{ok_count} // 0) + ($better_test->{not_ok_count} // 0);
    
    if ($worse_total == 0 && $better_total > 0) {
        print "     ISSUE: Test didn't execute at all in worse version\n";
        print "     EXPECTED: Should run $better_total tests\n";
    } elsif ($worse_total < $better_total) {
        print "     ISSUE: Test execution stopped early\n";
        print "     EXECUTED: $worse_total tests (expected $better_total)\n";
    } else {
        print "     ISSUE: Individual test failures within the file\n";
        my $worse_fails = $worse_test->{not_ok_count} // 0;
        my $better_fails = $better_test->{not_ok_count} // 0;
        print "     FAILURES: $worse_fails (better version had $better_fails)\n";
    }
    
    # Show exit code if different
    my $worse_exit = $worse_test->{exit_code} // 'unknown';
    my $better_exit = $better_test->{exit_code} // 'unknown';
    if ($worse_exit ne $better_exit) {
        print "     EXIT CODES: worse=$worse_exit, better=$better_exit\n";
    }
    
    # Show duration if significantly different
    my $worse_duration = $worse_test->{duration} // 0;
    my $better_duration = $better_test->{duration} // 0;
    if ($worse_duration =~ /^\d+\.?\d*$/ && $better_duration =~ /^\d+\.?\d*$/) {
        if (abs($worse_duration - $better_duration) > 1) {
            print "     DURATION: worse=${worse_duration}s, better=${better_duration}s\n";
        }
    }
    
    # Show specific test file for investigation
    print "     ACTION: Investigate " . $failure->{test_file} . "\n";
    if ($worse_test->{missing_features} && @{$worse_test->{missing_features}}) {
        print "     IMPLEMENT: " . join(", ", @{$worse_test->{missing_features}}) . "\n";
    }
}

main() unless caller;

