#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA;
use Fcntl qw(O_CREAT O_EXCL O_WRONLY);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my ($ledger_path, $acceptance_path, $allowlist_path, $output, @supplemental);
GetOptions(
    'ledger=s' => \$ledger_path,
    'acceptance-manifest=s' => \$acceptance_path,
    'allowlist=s' => \$allowlist_path,
    'output=s' => \$output,
    'supplemental-core=s@' => \@supplemental,
) or usage();
usage() unless $ledger_path && $acceptance_path && $output && !@ARGV;

my %protected = map { $_ => sha256_file($_) }
    ($ledger_path, $acceptance_path, @supplemental,
        defined($allowlist_path) ? ($allowlist_path) : ());
my $ledger = load_json($ledger_path);
my $acceptance = load_json($acceptance_path);
my ($allowlist, $allowlist_entries) = load_allowlist($allowlist_path);
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
    $protected{$entry->{path}} = $entry->{sha256};
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
    @execution_issues, @rows, @description_differences,
    @classified_shared_failures, @unclassified_shared_failures,
    @standalone_failures);
my (%pair_complete, %modes, %thread_complete, %thread_modes);
my (%assertions, %status_counts, %used_allowlist);
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
            $status_counts{$backend}{$role}{$result->{status}}++
                if result_shape_is_valid($result);
            my $tap = load_result_tap($label, $result, \%protected,
                \@execution_issues);
            $assertions{$backend}{$test} = $tap if $tap;
            push @rows, row_record($backend, $role, $test, $result);
        }
        compare_assertions($backend, $pair, \%assertions, $allowlist,
            \%used_allowlist, \@mismatches, \@description_differences,
            \@classified_shared_failures, \@unclassified_shared_failures);
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
        $status_counts{$backend}{'thread-only'}{$result->{status}}++
            if result_shape_is_valid($result);
        my $tap = load_result_tap($label, $result, \%protected,
            \@execution_issues);
        $assertions{$backend}{$test} = $tap if $tap;
        if ($tap) {
            push @standalone_failures, map {
                +{backend => $backend, test => $test, assertion => 0 + $_,
                    description => $tap->{$_}{description}}
            } grep { !$tap->{$_}{ok} } sort {$a <=> $b} keys %$tap;
        }
        push @rows, row_record($backend, 'thread-only', $test, $result);
    }
    $thread_complete{$index} = 1 if $complete;
}

my @unused_allowlist = map { $allowlist_entries->{$_} }
    sort grep { !$used_allowlist{$_} } keys %$allowlist_entries;

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
    assertion_status_mismatches => 0 + @mismatches,
    description_differences => 0 + @description_differences,
    classified_shared_failures => 0 + @classified_shared_failures,
    unclassified_shared_failures => 0 + @unclassified_shared_failures,
    standalone_failures => 0 + @standalone_failures,
    unused_allowlist => 0 + @unused_allowlist,
    status_counts => \%status_counts,
    rows => \@rows,
    supplemental_core_artifacts => \@supplemental_artifacts,
};
my $document = {
    schema_version => 1,
    kind => 'direct-thread',
    verified => (@missing || @mismatches || @zero_tap || @timeouts
        || @truncated || @execution_issues || @unclassified_shared_failures
        || @standalone_failures || @unused_allowlist)
        ? JSON::PP::false : JSON::PP::true,
    identity => {
        source_commit => $identity->{source_commit},
        runner_commit => $identity->{runner_commit},
        jperl_sha256 => $identity->{launcher}{sha256},
    },
    observations => {
        description_differences => \@description_differences,
    },
    details => $details,
    failures => {
        missing => \@missing,
        mismatches => \@mismatches,
        zero_tap => \@zero_tap,
        timeouts => \@timeouts,
        truncated => \@truncated,
        execution_issues => \@execution_issues,
        classified_shared_failures => \@classified_shared_failures,
        unclassified_shared_failures => \@unclassified_shared_failures,
        standalone_failures => \@standalone_failures,
        unused_allowlist => \@unused_allowlist,
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
    push @$execution, $label if $result->{status} eq 'error';
    push @$zero, $label if $result->{actual_tests_run} == 0;
    push @$truncated, $label
        if $result->{status} eq 'incomplete'
            || $result->{incomplete_tests} > 0
            || $result->{actual_tests_run} < $result->{planned_tests};
}

sub load_result_tap {
    my ($label, $result, $protected, $execution) = @_;
    return unless result_shape_is_valid($result);
    my $path = $result->{raw_output_path};
    if (!defined($path) || ref($path) || !length($path) || !-f $path) {
        push @$execution, "$label:raw-tap-missing";
        return;
    }
    $protected->{$path} = sha256_file($path);
    open my $fh, '<:raw', $path or do {
        push @$execution, "$label:raw-tap-unreadable";
        return;
    };
    my %assertions;
    my $malformed = 0;
    while (defined(my $line = <$fh>)) {
        $line =~ s/\r?\n\z//;
        next unless $line =~ /\A(not ok|ok)\s+(\d+)(?:\s*-\s*)?(.*)\z/;
        if (exists $assertions{$2}) {
            $malformed = 1;
            last;
        }
        $assertions{$2} = {
            ok => $1 eq 'ok' ? JSON::PP::true : JSON::PP::false,
            description => $3 // '',
        };
    }
    close $fh;
    if ($malformed) {
        push @$execution, "$label:duplicate-tap-assertion";
        return;
    }
    my $ok = grep { $_->{ok} } values %assertions;
    my $not_ok = keys(%assertions) - $ok;
    if (keys(%assertions) != $result->{actual_tests_run}
            || $ok != $result->{ok_count}
            || $not_ok != $result->{not_ok_count}) {
        push @$execution, "$label:tap-count-mismatch";
        return;
    }
    return \%assertions;
}

sub compare_assertions {
    my ($backend, $pair, $all, $allowlist, $used, $mismatches,
        $descriptions, $classified, $unclassified) = @_;
    my ($direct_path, $thread_path) = @{$pair}{qw(direct thread)};
    my $direct = $all->{$backend}{$direct_path};
    my $thread = $all->{$backend}{$thread_path};
    return unless $direct && $thread;
    my %numbers = map { $_ => 1 } (keys %$direct, keys %$thread);
    for my $number (sort {$a <=> $b} keys %numbers) {
        my $label = "$backend:$direct_path:$thread_path:$number";
        if (!exists($direct->{$number}) || !exists($thread->{$number})) {
            push @$mismatches, "$label:missing";
            next;
        }
        my $direct_ok = $direct->{$number}{ok} ? 1 : 0;
        my $thread_ok = $thread->{$number}{ok} ? 1 : 0;
        if ($direct_ok != $thread_ok) {
            push @$mismatches, "$label:status";
            next;
        }
        if ($direct->{$number}{description} ne $thread->{$number}{description}) {
            push @$descriptions, {
                backend => $backend,
                direct => $direct_path,
                thread => $thread_path,
                assertion => 0 + $number,
                direct_description => $direct->{$number}{description},
                thread_description => $thread->{$number}{description},
            };
        }
        next if $direct_ok;
        my $key = allowlist_key($backend, $direct_path, $thread_path, $number);
        my $failure = {
            backend => $backend,
            direct => $direct_path,
            thread => $thread_path,
            assertion => 0 + $number,
            description => $direct->{$number}{description},
        };
        if (exists $allowlist->{$key}) {
            $failure->{classification} = $allowlist->{$key}{classification};
            $used->{$key} = 1;
            push @$classified, $failure;
        } else {
            push @$unclassified, $failure;
        }
    }
}

sub load_allowlist {
    my ($path) = @_;
    return ({}, {}) unless defined $path;
    my $document = load_json($path);
    die "direct/thread allowlist schema_version must be 1\n"
        unless ($document->{schema_version} // '') eq '1';
    die "direct/thread allowlist entries are missing\n"
        unless ref($document->{entries}) eq 'ARRAY';
    my (%by_key, %entries);
    for my $index (0 .. $#{$document->{entries}}) {
        my $entry = $document->{entries}[$index];
        die "malformed direct/thread allowlist entry at index $index\n"
            unless ref($entry) eq 'HASH'
                && ($entry->{backend} // '') =~ /\A(?:jvm|interpreter)\z/
                && defined($entry->{direct}) && !ref($entry->{direct})
                && length($entry->{direct})
                && defined($entry->{thread}) && !ref($entry->{thread})
                && length($entry->{thread})
                && defined($entry->{assertion})
                && $entry->{assertion} =~ /\A[1-9]\d*\z/
                && defined($entry->{classification})
                && !ref($entry->{classification})
                && length($entry->{classification});
        my $key = allowlist_key(@{$entry}{qw(backend direct thread assertion)});
        die "duplicate direct/thread allowlist entry at index $index\n"
            if exists $by_key{$key};
        $by_key{$key} = {%$entry, assertion => 0 + $entry->{assertion}};
        $entries{$key} = $by_key{$key};
    }
    return (\%by_key, \%entries);
}

sub allowlist_key {
    return join("\0", @_);
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
    return 0 unless $result->{ok_count} + $result->{not_ok_count}
        == $result->{actual_tests_run};
    return 0 if $result->{status} eq 'pass' && $result->{not_ok_count} != 0;
    return 0 if $result->{status} eq 'fail' && $result->{not_ok_count} == 0;
    return 1;
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
        . "[--allowlist FILE] [--supplemental-core DESCRIPTOR ...]\n";
}
