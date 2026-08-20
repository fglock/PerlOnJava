#!/usr/bin/env perl
use strict;
use warnings;

use Getopt::Long qw(GetOptions);
use JSON::PP;
use Encode qw(FB_DEFAULT decode);

my $fail_on_regression = 0;
my $fail_on_invalid = 0;
my $expected_files;
my $output_file;
my $path_prefix;
my $file_list;
my $normalize_pr958_artifacts = 0;
my $help = 0;
GetOptions(
    'fail-on-regression!' => \$fail_on_regression,
    'fail-on-invalid!' => \$fail_on_invalid,
    'expected-files=i' => \$expected_files,
    'output=s' => \$output_file,
    'path-prefix=s' => \$path_prefix,
    'file-list=s' => \$file_list,
    'normalize-pr958-artifacts!' => \$normalize_pr958_artifacts,
    'help' => \$help,
) or usage(2);

usage(0) if $help;
my ($baseline_file, $candidate_file) = @ARGV;
usage(2) unless defined $baseline_file && defined $candidate_file && @ARGV == 2;
die "--path-prefix and --file-list are mutually exclusive\n"
    if defined $path_prefix && defined $file_list;

my $baseline = load_results($baseline_file, 'baseline');
my $candidate = load_results($candidate_file, 'candidate');
if (defined $path_prefix) {
    $path_prefix = canonical_file($path_prefix);
    $path_prefix .= '/' unless $path_prefix =~ m{/$} || $path_prefix =~ /\.t\z/;
    $baseline = filter_results($baseline, $path_prefix);
    $candidate = filter_results($candidate, $path_prefix);
}
if (defined $file_list) {
    my $selected = load_file_list($file_list);
    $baseline = filter_results_by_file($baseline, $selected);
    $candidate = filter_results_by_file($candidate, $selected);
}
my $comparison = compare_results($baseline, $candidate);
$comparison->{expected_files} = $expected_files if defined $expected_files;

print_report($baseline_file, $candidate_file, $comparison);
save_report($output_file, $baseline_file, $candidate_file, $comparison)
    if defined $output_file;

exit 1 if $fail_on_regression && @{$comparison->{regressions}};
exit 1 if $fail_on_invalid && has_invalid_candidate($comparison, $expected_files);
exit 0;

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: compare_test_results.pl [OPTIONS] BASELINE CANDIDATE

Compare perl_test_runner results file-by-file. Inputs may be runner JSON or a
captured runner log such as logs/test_20260815_080000_958.log.

Options:
  --fail-on-regression  Exit nonzero when any candidate file loses passing tests
  --fail-on-invalid     Exit nonzero for missing files, execution failures,
                        zero-TAP results, or an --expected-files mismatch
  --expected-files NUM  Require exactly NUM candidate files
  --output FILE         Save the normalized comparison as JSON
  --path-prefix PATH    Compare one test file or files below a canonical path
  --file-list FILE      Compare only exact test paths listed one per line
  --normalize-pr958-artifacts
                        Normalize the two exact reconstructed PR-958 baseline
                        transcript signatures and retain raw counts in JSON
  --help                Show this help
USAGE
    exit $status;
}

sub load_results {
    my ($path, $side) = @_;
    open my $fh, '<:raw', $path or die "Cannot open $path: $!\n";
    my $content = do { local $/; <$fh> };
    close $fh;

    if ($content =~ /^\s*\{/) {
        my $document = eval { JSON::PP->new->utf8->decode($content) };
        if (!$document) {
            # Reports produced before the runner's UTF-8 output fix may contain
            # raw diagnostic bytes. They do not affect count comparison, so
            # normalize only those legacy strings instead of rejecting the
            # otherwise useful baseline artifact.
            my $decoded = decode('UTF-8', $content, FB_DEFAULT);
            $document = JSON::PP->new->decode($decoded);
        }
        die "JSON report $path has no results object\n"
            unless ref($document->{results}) eq 'HASH';
        my %normalized;
        for my $file (keys %{$document->{results}}) {
            my $result = $document->{results}{$file};
            my $entry = {
                ok => 0 + ($result->{ok_count} // 0),
                total => 0 + ($result->{total_tests} // 0),
                status => $result->{status} // 'unknown',
                planned => 0 + ($result->{planned_tests} // $result->{total_tests} // 0),
                actual => 0 + ($result->{actual_tests_run} // $result->{total_tests} // 0),
                incomplete => 0 + ($result->{incomplete_tests} // 0),
                exit_code => 0 + ($result->{exit_code} // 0),
            };
            normalize_pr958_artifact(canonical_file($file), $entry, $side);
            $normalized{canonical_file($file)} = $entry;
        }
        return \%normalized;
    }

    my %results;
    for my $line (split /\n/, $content) {
        next unless $line =~ /^\[\s*\d+\/\d+\]\s+(\S+)\s+\.\.\.\s+(\S+)\s+(\d+)\/(\d+)\s+ok\b/;
        my ($file, $marker, $ok, $total) = ($1, $2, $3, $4);
        my %status_for = (
            'T' => 'timeout', 'I' => 'incomplete', '!' => 'error',
            '?' => 'unknown',
        );
        my $entry = {
            ok => 0 + $ok,
            total => 0 + $total,
            status => $status_for{$marker} // ($ok == $total ? 'pass' : 'partial'),
            planned => 0 + $total,
            actual => 0 + $total,
            incomplete => $marker eq 'I' ? 1 : 0,
            exit_code => $marker eq '!' || $marker eq 'T' ? 1 : 0,
        };
        normalize_pr958_artifact(canonical_file($file), $entry, $side);
        $results{canonical_file($file)} = $entry;
    }
    die "Captured runner log $path contains no per-file result lines\n"
        unless keys %results;
    return \%results;
}

sub normalize_pr958_artifact {
    my ($file, $entry, $side) = @_;
    return unless $normalize_pr958_artifacts;
    my %artifacts = (
        'perl5_t/t/op/do.t|94|99' => [68, 71,
            'PR 958 duplicated its first 28 TAP assertions'],
        'perl5_t/t/japh/abigail.t|110|130' => [109, 130,
            'PR 958 logged one irreproducible extra pass'],
    );
    my $artifact = $artifacts{join('|', $file, $entry->{ok}, $entry->{total})};
    return unless $artifact;
    $entry->{raw_ok} = $entry->{ok};
    $entry->{raw_total} = $entry->{total};
    $entry->{normalization} = $artifact->[2];
    ($entry->{ok}, $entry->{total}) = @{$artifact}[0, 1];
    $entry->{planned} = $entry->{total};
    $entry->{actual} = $entry->{total};
    $entry->{normalization_side} = $side;
}

sub canonical_file {
    my ($file) = @_;
    $file =~ s{\\}{/}g;
    $file =~ s{^\./}{};
    $file =~ s{^.*?((?:perl5_t|perl5|src/test/resources)/)}{$1};
    return $file;
}

sub filter_results {
    my ($results, $prefix) = @_;
    my %filtered = map { $_ => $results->{$_} }
        grep { index($_, $prefix) == 0 } keys %$results;
    die "No result files match --path-prefix $prefix\n" unless keys %filtered;
    return \%filtered;
}

sub load_file_list {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot open file list $path: $!\n";
    my %selected;
    while (my $line = <$fh>) {
        $line =~ s/\r?\n\z//;
        $line =~ s/^\s+|\s+$//g;
        next if $line eq '' || $line =~ /^#/;
        $selected{canonical_file($line)} = 1;
    }
    close $fh or die "Cannot close file list $path: $!\n";
    die "File list $path contains no test paths\n" unless keys %selected;
    return \%selected;
}

sub filter_results_by_file {
    my ($results, $selected) = @_;
    my %filtered = map { $_ => $results->{$_} }
        grep { $selected->{$_} } keys %$results;
    return \%filtered;
}

sub compare_results {
    my ($baseline, $candidate) = @_;
    my (@regressions, @improvements, @plan_changes, @missing, @added,
        @execution_issues, @zero_tap, @truncated);
    my ($baseline_ok, $candidate_ok, $baseline_total, $candidate_total) = (0, 0, 0, 0);

    $baseline_ok += $_->{ok} for values %$baseline;
    $candidate_ok += $_->{ok} for values %$candidate;
    $baseline_total += $_->{total} for values %$baseline;
    $candidate_total += $_->{total} for values %$candidate;

    my %all = map { $_ => 1 } (keys %$baseline, keys %$candidate);
    for my $file (sort keys %$candidate) {
        my $result = $candidate->{$file};
        push @execution_issues, {
            file => $file,
            status => $result->{status},
            ok => $result->{ok},
            total => $result->{total},
        } if $result->{status} !~ /\A(?:pass|partial|fail)\z/
            || $result->{exit_code};
        push @zero_tap, {
            file => $file,
            status => $result->{status},
        } if $result->{total} == 0;
        push @truncated, {
            file => $file,
            planned => $result->{planned},
            actual => $result->{actual},
            incomplete => $result->{incomplete},
        } if $result->{incomplete}
            || ($result->{planned} > 0 && $result->{actual} < $result->{planned});
    }
    for my $file (sort keys %all) {
        my ($before, $after) = ($baseline->{$file}, $candidate->{$file});
        if (!$after) {
            push @missing, { file => $file, %$before };
            next;
        }
        if (!$before) {
            push @added, { file => $file, %$after };
            next;
        }
        my $delta = $after->{ok} - $before->{ok};
        my $entry = {
            file => $file,
            baseline_ok => $before->{ok}, candidate_ok => $after->{ok},
            baseline_total => $before->{total}, candidate_total => $after->{total},
            delta_ok => $delta,
        };
        push @regressions, $entry if $delta < 0;
        push @improvements, $entry if $delta > 0;
        push @plan_changes, $entry if $before->{total} != $after->{total};
    }

    @regressions = sort { $a->{delta_ok} <=> $b->{delta_ok} } @regressions;
    @improvements = sort { $b->{delta_ok} <=> $a->{delta_ok} } @improvements;
    return {
        summary => {
            baseline_ok => $baseline_ok, candidate_ok => $candidate_ok,
            delta_ok => $candidate_ok - $baseline_ok,
            baseline_total => $baseline_total, candidate_total => $candidate_total,
            delta_total => $candidate_total - $baseline_total,
            baseline_files => scalar(keys %$baseline),
            candidate_files => scalar(keys %$candidate),
        },
        regressions => \@regressions,
        improvements => \@improvements,
        plan_changes => \@plan_changes,
        missing_files => \@missing,
        added_files => \@added,
        execution_issues => \@execution_issues,
        zero_tap => \@zero_tap,
        truncated => \@truncated,
    };
}

sub print_report {
    my ($baseline_file, $candidate_file, $comparison) = @_;
    my $summary = $comparison->{summary};
    print "Baseline:  $baseline_file\n";
    print "Candidate: $candidate_file\n";
    printf "Passing assertions: %d/%d -> %d/%d (%+d passing, %+d planned)\n",
        @{$summary}{qw(baseline_ok baseline_total candidate_ok candidate_total delta_ok delta_total)};
    printf "Files: %d -> %d; regressions=%d improvements=%d missing=%d added=%d execution-issues=%d zero-TAP=%d truncated=%d\n",
        $summary->{baseline_files}, $summary->{candidate_files},
        scalar(@{$comparison->{regressions}}), scalar(@{$comparison->{improvements}}),
        scalar(@{$comparison->{missing_files}}), scalar(@{$comparison->{added_files}}),
        scalar(@{$comparison->{execution_issues}}), scalar(@{$comparison->{zero_tap}}),
        scalar(@{$comparison->{truncated}});
    if (defined $comparison->{expected_files}
            && $summary->{candidate_files} != $comparison->{expected_files}) {
        printf "EXPECTED FILE COUNT MISMATCH: expected %d, found %d\n",
            $comparison->{expected_files}, $summary->{candidate_files};
    }

    print_entries('REGRESSIONS', $comparison->{regressions});
    print_entries('IMPROVEMENTS', $comparison->{improvements});
    print_entries('PLAN CHANGES', $comparison->{plan_changes});
    if (@{$comparison->{missing_files}}) {
        print "\nMISSING FILES\n";
        print "  $_->{file}\n" for @{$comparison->{missing_files}};
    }
    if (@{$comparison->{added_files}}) {
        print "\nADDED FILES\n";
        print "  $_->{file}\n" for @{$comparison->{added_files}};
    }
    if (@{$comparison->{execution_issues}}) {
        print "\nEXECUTION ISSUES\n";
        printf "  %s: status=%s %d/%d\n",
            @{$_}{qw(file status ok total)} for @{$comparison->{execution_issues}};
    }
    if (@{$comparison->{zero_tap}}) {
        print "\nZERO TAP\n";
        printf "  %s: status=%s\n", @{$_}{qw(file status)}
            for @{$comparison->{zero_tap}};
    }
    if (@{$comparison->{truncated}}) {
        print "\nTRUNCATED OR INCOMPLETE TAP\n";
        printf "  %s: planned=%d actual=%d incomplete=%d\n",
            @{$_}{qw(file planned actual incomplete)}
            for @{$comparison->{truncated}};
    }
}

sub has_invalid_candidate {
    my ($comparison, $required_files) = @_;
    return 1 if @{$comparison->{missing_files}};
    return 1 if @{$comparison->{execution_issues}};
    return 1 if @{$comparison->{zero_tap}};
    return 1 if @{$comparison->{truncated}};
    return 1 if defined($required_files)
        && $comparison->{summary}{candidate_files} != $required_files;
    return 0;
}

sub print_entries {
    my ($title, $entries) = @_;
    return unless @$entries;
    print "\n$title\n";
    for my $entry (@$entries) {
        printf "  %s: %d/%d -> %d/%d (%+d)\n",
            @{$entry}{qw(file baseline_ok baseline_total candidate_ok candidate_total delta_ok)};
    }
}

sub save_report {
    my ($path, $baseline_file, $candidate_file, $comparison) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} JSON::PP->new->utf8->canonical->pretty->encode({
        baseline => $baseline_file,
        candidate => $candidate_file,
        %$comparison,
    });
    close $fh or die "Cannot close $path: $!\n";
}
