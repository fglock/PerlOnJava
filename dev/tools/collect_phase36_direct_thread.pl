#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA;
use Fcntl qw(O_CREAT O_EXCL O_WRONLY);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my ($ledger_path, $acceptance_path, $output, @supplemental);
GetOptions(
    'ledger=s' => \$ledger_path,
    'acceptance-manifest=s' => \$acceptance_path,
    'output=s' => \$output,
    'supplemental-core=s@' => \@supplemental,
) or usage();
usage() unless $ledger_path && $acceptance_path && $output && !@ARGV;

my %protected = map { $_ => sha256_file($_) }
    ($ledger_path, $acceptance_path, @supplemental);
my $ledger = load_json($ledger_path);
my $acceptance = load_json($acceptance_path);
my $identity = validate_identity($acceptance->{identity});
my $artifacts = $acceptance->{artifacts};
die "acceptance manifest artifacts are missing\n" unless ref($artifacts) eq 'HASH';

my @supplemental_artifacts;
for my $descriptor_path (@supplemental) {
    my $entry = load_json($descriptor_path);
    die "supplemental core artifact is malformed: $descriptor_path\n"
        unless ref($entry) eq 'HASH' && defined($entry->{path})
            && ($entry->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    die "supplemental core artifact hash mismatch: $descriptor_path\n"
        unless -f $entry->{path}
            && sha256_file($entry->{path}) eq $entry->{sha256};
    push @supplemental_artifacts, {%$entry};
}

my %results;
for my $backend (qw(jvm interpreter)) {
    my $name = "$backend-results.json";
    my $entry = $artifacts->{$name};
    die "acceptance runner artifact is missing: $name\n"
        unless ref($entry) eq 'HASH' && defined($entry->{path})
            && ($entry->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    die "runner artifact hash mismatch: $name\n"
        unless -f $entry->{path}
            && sha256_file($entry->{path}) eq $entry->{sha256};
    $protected{$entry->{path}} = $entry->{sha256};
    my $runner = load_json($entry->{path});
    die "runner results are missing: $name\n"
        unless ref($runner->{results}) eq 'HASH';
    $results{$backend} = $runner->{results};
}

my $pairs = $ledger->{direct_thread_pairs};
my $thread_only = $ledger->{thread_only_tests};
die "ledger direct/thread pairs are missing\n"
    unless ref($pairs) eq 'ARRAY' && @$pairs;
die "ledger thread-only tests are missing\n"
    unless ref($thread_only) eq 'ARRAY' && @$thread_only;

my (@missing, @mismatches, @zero_tap, @timeouts, @truncated,
    @execution_issues, @rows);
my (%pair_complete, %modes, %thread_complete, %thread_modes);
for my $index (0 .. $#$pairs) {
    my $pair = $pairs->[$index];
    die "malformed direct/thread pair at index $index\n"
        unless ref($pair) eq 'HASH' && defined($pair->{direct})
            && length($pair->{direct}) && defined($pair->{thread})
            && length($pair->{thread});
    my $complete = 1;
    for my $backend (qw(jvm interpreter)) {
        my ($direct, $thread) = @{$results{$backend}}{
            $pair->{direct}, $pair->{thread}};
        for my $role_result (
            [direct => $pair->{direct}, $direct],
            [thread => $pair->{thread}, $thread],
        ) {
            my ($role, $test, $result) = @$role_result;
            my $label = "$backend:$test";
            if (!defined $result) {
                push @missing, $label;
                $complete = 0;
                next;
            }
            $modes{"$backend:$role"} = 1;
            classify($label, $result, \@zero_tap, \@timeouts,
                \@truncated, \@execution_issues);
            push @rows, row_record($backend, $role, $test, $result);
        }
        if (defined($direct) && defined($thread)
                && result_is_clean($direct) && result_is_clean($thread)
                && !same_or_better($direct, $thread)) {
            push @mismatches, "$backend:$pair->{direct}:$pair->{thread}";
        }
    }
    $pair_complete{$index} = 1 if $complete;
}

for my $index (0 .. $#$thread_only) {
    my $test = $thread_only->[$index];
    die "malformed thread-only test at index $index\n"
        unless defined($test) && !ref($test) && length($test);
    my $complete = 1;
    for my $backend (qw(jvm interpreter)) {
        my $result = $results{$backend}{$test};
        my $label = "$backend:$test";
        if (!defined $result) {
            push @missing, $label;
            $complete = 0;
            next;
        }
        $thread_modes{$backend} = 1;
        classify($label, $result, \@zero_tap, \@timeouts,
            \@truncated, \@execution_issues);
        push @rows, row_record($backend, 'thread-only', $test, $result);
    }
    $thread_complete{$index} = 1 if $complete;
}

my $details = {
    expected_pairs => 0 + @$pairs,
    actual_pairs => 0 + keys(%pair_complete),
    expected_modes => 4,
    actual_modes => 0 + keys(%modes),
    expected_thread_only => 0 + @$thread_only,
    actual_thread_only => 0 + keys(%thread_complete),
    expected_thread_only_modes => 2,
    actual_thread_only_modes => 0 + keys(%thread_modes),
    mismatches => 0 + @mismatches,
    missing => 0 + @missing,
    zero_tap => 0 + @zero_tap,
    timeouts => 0 + @timeouts,
    truncated => 0 + @truncated,
    execution_issues => 0 + @execution_issues,
    rows => \@rows,
    supplemental_core_artifacts => \@supplemental_artifacts,
};
my $document = {
    schema_version => 1,
    kind => 'direct-thread',
    verified => (@missing || @mismatches || @zero_tap || @timeouts
        || @truncated || @execution_issues) ? JSON::PP::false : JSON::PP::true,
    identity => {
        source_commit => $identity->{source_commit},
        runner_commit => $identity->{runner_commit},
        jperl_sha256 => $identity->{launcher}{sha256},
    },
    details => $details,
    failures => {
        missing => \@missing,
        mismatches => \@mismatches,
        zero_tap => \@zero_tap,
        timeouts => \@timeouts,
        truncated => \@truncated,
        execution_issues => \@execution_issues,
    },
};

for my $path (sort keys %protected) {
    die "direct/thread input mutated during collection: $path\n"
        unless -f $path && sha256_file($path) eq $protected{$path};
}
write_json_exclusive($output, $document);
die "direct/thread collection failed; evidence: $output\n"
    unless $document->{verified};
print "$output\n";

sub validate_identity {
    my ($value) = @_;
    die "acceptance manifest identity is missing\n" unless ref($value) eq 'HASH';
    for my $key (qw(source_commit runner_commit perl5_commit)) {
        die "acceptance manifest identity $key is invalid\n"
            unless ($value->{$key} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    for my $key (qw(launcher jar sbom baseline)) {
        die "acceptance manifest identity $key is missing\n"
            unless ref($value->{$key}) eq 'HASH'
                && ($value->{$key}{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    die "runner commit differs from source\n"
        unless $value->{runner_commit} eq $value->{source_commit};
    return $value;
}

sub classify {
    my ($label, $result, $zero, $timeout, $truncated, $execution) = @_;
    if (!result_shape_is_valid($result)) {
        push @$execution, $label;
        return;
    }
    push @$timeout, $label if $result->{status} eq 'timeout';
    push @$execution, $label if $result->{status} =~ /\A(?:error|fail)\z/;
    push @$zero, $label if $result->{actual_tests_run} == 0;
    push @$truncated, $label
        if $result->{status} eq 'incomplete'
            || $result->{incomplete_tests} > 0
            || $result->{actual_tests_run} < $result->{planned_tests};
}

sub result_shape_is_valid {
    my ($result) = @_;
    return 0 unless ref($result) eq 'HASH'
        && ($result->{status} // '') =~ /\A(?:pass|fail|error|timeout|incomplete)\z/;
    for my $field (qw(ok_count not_ok_count actual_tests_run planned_tests
            incomplete_tests)) {
        return 0 unless defined($result->{$field})
            && $result->{$field} =~ /\A\d+\z/;
    }
    return 1;
}

sub result_is_clean {
    my ($result) = @_;
    return result_shape_is_valid($result) && $result->{status} eq 'pass'
        && $result->{actual_tests_run} > 0 && $result->{not_ok_count} == 0
        && $result->{incomplete_tests} == 0
        && $result->{actual_tests_run} >= $result->{planned_tests};
}

sub same_or_better {
    my ($direct, $thread) = @_;
    return $thread->{ok_count} >= $direct->{ok_count}
        && $thread->{actual_tests_run} >= $direct->{actual_tests_run}
        && $thread->{not_ok_count} <= $direct->{not_ok_count}
        && $thread->{incomplete_tests} <= $direct->{incomplete_tests}
        && $thread->{planned_tests} == $direct->{planned_tests};
}

sub row_record {
    my ($backend, $role, $test, $result) = @_;
    return {
        backend => $backend,
        role => $role,
        test => $test,
        map { $_ => $result->{$_} } qw(status ok_count not_ok_count
            actual_tests_run planned_tests incomplete_tests),
    };
}

sub load_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $document = eval { JSON::PP->new->utf8->decode(<$fh>) };
    die "invalid JSON $path\n" unless ref($document) eq 'HASH';
    return $document;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!\n";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    return $sha->hexdigest;
}

sub write_json_exclusive {
    my ($path, $document) = @_;
    my $flags = O_WRONLY | O_CREAT | O_EXCL;
    sysopen my $fh, $path, $flags, 0600
        or die "Cannot create exclusive output $path: $!\n";
    print {$fh} JSON::PP->new->canonical->pretty->encode($document)
        or die "Cannot write $path: $!\n";
    close $fh or die "Cannot close $path: $!\n";
}

sub usage {
    die "usage: $0 --ledger FILE --acceptance-manifest FILE --output FILE "
        . "[--supplemental-core DESCRIPTOR ...]\n";
}
