#!/usr/bin/env perl
use strict;
use warnings;

use Getopt::Long qw(GetOptions);
use JSON::PP;
use Encode qw(FB_DEFAULT decode);

my $fail_on_regression = 0;
my $output_file;
my $path_prefix;
my $help = 0;
GetOptions(
    'fail-on-regression!' => \$fail_on_regression,
    'output=s' => \$output_file,
    'path-prefix=s' => \$path_prefix,
    'help' => \$help,
) or usage(2);

usage(0) if $help;
my ($baseline_file, $candidate_file) = @ARGV;
usage(2) unless defined $baseline_file && defined $candidate_file && @ARGV == 2;

my $baseline = load_results($baseline_file);
my $candidate = load_results($candidate_file);
if (defined $path_prefix) {
    $path_prefix = canonical_file($path_prefix);
    $path_prefix .= '/' unless $path_prefix =~ m{/$};
    $baseline = filter_results($baseline, $path_prefix);
    $candidate = filter_results($candidate, $path_prefix);
}
my $comparison = compare_results($baseline, $candidate);

print_report($baseline_file, $candidate_file, $comparison);
save_report($output_file, $baseline_file, $candidate_file, $comparison)
    if defined $output_file;

exit 1 if $fail_on_regression && @{$comparison->{regressions}};
exit 0;

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: compare_test_results.pl [OPTIONS] BASELINE CANDIDATE

Compare perl_test_runner results file-by-file. Inputs may be runner JSON or a
captured runner log such as logs/test_20260815_080000_958.log.

Options:
  --fail-on-regression  Exit nonzero when any candidate file loses passing tests
  --output FILE         Save the normalized comparison as JSON
  --path-prefix PATH    Compare only files below this canonical path
  --help                Show this help
USAGE
    exit $status;
}

sub load_results {
    my ($path) = @_;
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
            $normalized{canonical_file($file)} = {
                ok => 0 + ($result->{ok_count} // 0),
                total => 0 + ($result->{total_tests} // 0),
                status => $result->{status} // 'unknown',
            };
        }
        return \%normalized;
    }

    my %results;
    for my $line (split /\n/, $content) {
        next unless $line =~ /^\[\s*\d+\/\d+\]\s+(\S+)\s+\.\.\.\s+\S+\s+(\d+)\/(\d+)\s+ok\b/;
        my ($file, $ok, $total) = ($1, $2, $3);
        $results{canonical_file($file)} = {
            ok => 0 + $ok,
            total => 0 + $total,
            status => $ok == $total ? 'pass' : 'partial',
        };
    }
    die "Captured runner log $path contains no per-file result lines\n"
        unless keys %results;
    return \%results;
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

sub compare_results {
    my ($baseline, $candidate) = @_;
    my (@regressions, @improvements, @plan_changes, @missing, @added);
    my ($baseline_ok, $candidate_ok, $baseline_total, $candidate_total) = (0, 0, 0, 0);

    $baseline_ok += $_->{ok} for values %$baseline;
    $candidate_ok += $_->{ok} for values %$candidate;
    $baseline_total += $_->{total} for values %$baseline;
    $candidate_total += $_->{total} for values %$candidate;

    my %all = map { $_ => 1 } (keys %$baseline, keys %$candidate);
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
    };
}

sub print_report {
    my ($baseline_file, $candidate_file, $comparison) = @_;
    my $summary = $comparison->{summary};
    print "Baseline:  $baseline_file\n";
    print "Candidate: $candidate_file\n";
    printf "Passing assertions: %d/%d -> %d/%d (%+d passing, %+d planned)\n",
        @{$summary}{qw(baseline_ok baseline_total candidate_ok candidate_total delta_ok delta_total)};
    printf "Files: %d -> %d; regressions=%d improvements=%d missing=%d added=%d\n",
        $summary->{baseline_files}, $summary->{candidate_files},
        scalar(@{$comparison->{regressions}}), scalar(@{$comparison->{improvements}}),
        scalar(@{$comparison->{missing_files}}), scalar(@{$comparison->{added_files}});

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
