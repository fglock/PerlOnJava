#!/usr/bin/env perl
use strict;
use warnings;

use Digest::SHA;
use File::Basename qw(dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $requirements = 'dev/tools/phase36_acceptance_requirements.json';
my $evidence;
my $mode = 'report';
my $expected_commit;
my $output;
my $help;
GetOptions(
    'requirements=s' => \$requirements,
    'evidence=s' => \$evidence,
    'mode=s' => \$mode,
    'expected-commit=s' => \$expected_commit,
    'output=s' => \$output,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV || $mode !~ /\A(?:report|strict)\z/;
die "--expected-commit must be a full Git SHA\n"
    if defined $expected_commit && $expected_commit !~ /\A[0-9a-f]{40}\z/;

my $rules = load_json($requirements, 'requirements');
die "Requirements schema_version must be 1\n"
    unless ($rules->{schema_version} // 0) == 1;
die "Requirements must target current Perl without a pinned revision\n"
    unless ($rules->{policy} // '') =~ /current/i
        && ($rules->{policy} // '') =~ /no pinned Perl revision/i;
my $required = $rules->{required_gates};
die "Requirements have no gates\n"
    unless ref($required) eq 'ARRAY' && @$required;

my $document = defined $evidence ? load_json($evidence, 'evidence') : undef;
my $evidence_root = defined $evidence ? dirname(File::Spec->rel2abs($evidence)) : '.';
my @global_issues;
my %gate_report;
my %identity;
my %gates;
if ($document) {
    push @global_issues, 'evidence schema_version must be 1'
        unless ($document->{schema_version} // 0) == 1;
    push @global_issues, 'evidence mode must be dry-run or acceptance'
        unless ($document->{mode} // '') =~ /\A(?:dry-run|acceptance)\z/;
    %identity = %{ ref($document->{identity}) eq 'HASH'
        ? $document->{identity} : {} };
    %gates = %{ ref($document->{gates}) eq 'HASH'
        ? $document->{gates} : {} };
    validate_identity(\@global_issues, \%identity);
    push @global_issues, "source commit differs from --expected-commit"
        if defined $expected_commit
            && ($identity{source_commit} // '') ne $expected_commit;
}

my $ledger_files;
for my $requirement (@$required) {
    my $id = $requirement->{id} // '';
    my $kind = $requirement->{kind} // '';
    die "Requirement has invalid gate id '$id'\n"
        unless $id =~ /\A[a-z0-9]+(?:-[a-z0-9]+)*\z/;
    my @issues;
    my $gate = $gates{$id};
    if (!$gate) {
        push @issues, 'required gate evidence is missing';
    } elsif (ref($gate) ne 'HASH') {
        push @issues, 'gate evidence must be an object';
    } else {
        push @issues, "gate state is " . ($gate->{state} // '<missing>')
            unless ($gate->{state} // '') eq 'passed';
        validate_artifact(\@issues, $gate->{artifact}, $evidence_root);
        validate_gate_identity(\@issues, $gate->{identity}, \%identity, $kind);
        my $details = ref($gate->{details}) eq 'HASH' ? $gate->{details} : {};
        push @issues, 'gate details must be an object'
            unless ref($gate->{details}) eq 'HASH';
        if ($kind eq 'ledger') {
            validate_ledger(\@issues, $details, $rules);
            $ledger_files = $details->{runner_files}
                if number($details->{runner_files});
        } elsif ($kind eq 'comparison') {
            validate_comparison(\@issues, $details, $ledger_files);
        } elsif ($kind eq 'direct-thread') {
            validate_direct_thread(\@issues, $details);
        } elsif ($kind eq 'cpan') {
            validate_cpan(\@issues, $details);
        } elsif ($kind eq 'performance') {
            validate_performance(\@issues, $details, $rules);
        } elsif ($kind eq 'packaging') {
            validate_packaging(\@issues, $details, \%identity);
        } elsif ($kind eq 'notice-license') {
            validate_zero_fields(\@issues, $details,
                qw(missing_notices changed_notices missing_licenses changed_licenses));
            push @issues, 'notice/license verification did not pass'
                unless true_value($details->{verified});
        } elsif ($kind eq 'make') {
            validate_zero_fields(\@issues, $details, qw(warnings failures));
            push @issues, 'full make did not pass'
                unless true_value($details->{passed});
        } elsif ($kind eq 'ci') {
            validate_ci(\@issues, $details, $rules, \%identity);
        } else {
            push @issues, "unknown gate kind '$kind'";
        }
    }
    $gate_report{$id} = {
        kind => $kind,
        status => @issues ? ($gate ? 'failed' : 'pending') : 'passed',
        issues => \@issues,
    };
}

my @unexpected = sort grep {
    my $candidate = $_;
    !grep { ($_->{id} // '') eq $candidate } @$required;
} keys %gates;
push @global_issues, 'unexpected gates: ' . join(', ', @unexpected) if @unexpected;

my $failed = scalar(grep { $gate_report{$_}{status} eq 'failed' } keys %gate_report)
    + scalar(@global_issues);
my $pending = scalar grep { $gate_report{$_}{status} eq 'pending' } keys %gate_report;
my $passed = scalar grep { $gate_report{$_}{status} eq 'passed' } keys %gate_report;
my $report = {
    schema_version => 1,
    check_mode => $mode,
    evidence_mode => $document ? $document->{mode} : undef,
    expected_commit => $expected_commit,
    summary => {
        required_gates => scalar(@$required),
        passed => $passed,
        pending => $pending,
        failed => $failed,
        authoritative => ($mode eq 'strict' && !$failed && !$pending
            && $document && ($document->{mode} // '') eq 'acceptance')
            ? JSON::PP::true : JSON::PP::false,
    },
    global_issues => \@global_issues,
    gates => \%gate_report,
};
my $rendered = JSON::PP->new->canonical->pretty->encode($report);
if (defined $output) {
    write_raw($output, $rendered);
} else {
    print $rendered;
}

exit 1 if $mode eq 'strict'
    && ($failed || $pending || !$document
        || ($document->{mode} // '') ne 'acceptance');
exit 0;

sub validate_identity {
    my ($issues, $identity) = @_;
    for my $field (qw(source_commit perl5_commit runner_commit)) {
        push @$issues, "$field is missing or not a full Git SHA"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    for my $field (qw(jperl_sha256 jar_sha256 sbom_sha256 baseline_sha256)) {
        push @$issues, "$field is missing or not SHA-256"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    push @$issues, 'runner commit does not match source commit'
        if ($identity->{runner_commit} // '') ne ($identity->{source_commit} // '');
}

sub validate_gate_identity {
    my ($issues, $gate_identity, $identity, $kind) = @_;
    if (ref($gate_identity) ne 'HASH') {
        push @$issues, 'gate identity is missing';
        return;
    }
    push @$issues, 'gate source commit is wrong'
        if ($gate_identity->{source_commit} // '') ne ($identity->{source_commit} // '');
    if ($kind =~ /\A(?:comparison|direct-thread|cpan)\z/) {
        push @$issues, 'gate runner commit is wrong'
            if ($gate_identity->{runner_commit} // '') ne ($identity->{runner_commit} // '');
        push @$issues, 'gate executable identity is wrong'
            if ($gate_identity->{jperl_sha256} // '') ne ($identity->{jperl_sha256} // '');
    }
    if ($kind eq 'comparison') {
        push @$issues, 'gate baseline identity is wrong'
            if ($gate_identity->{baseline_sha256} // '') ne ($identity->{baseline_sha256} // '');
    }
}

sub validate_artifact {
    my ($issues, $artifact, $base) = @_;
    if (ref($artifact) ne 'HASH') {
        push @$issues, 'artifact descriptor is missing';
        return;
    }
    my $path = $artifact->{path} // '';
    my $sha = $artifact->{sha256} // '';
    push @$issues, 'artifact SHA-256 is missing or malformed'
        unless $sha =~ /\A[0-9a-f]{64}\z/;
    if (!length $path) {
        push @$issues, 'artifact path is missing';
        return;
    }
    my $resolved = File::Spec->file_name_is_absolute($path)
        ? $path : File::Spec->catfile($base, File::Spec->splitdir($path));
    if (!-f $resolved || !-s $resolved) {
        push @$issues, "artifact is missing or empty: $path";
        return;
    }
    push @$issues, "artifact hash mismatch: $path"
        if $sha =~ /\A[0-9a-f]{64}\z/ && sha256_file($resolved) ne $sha;
}

sub validate_ledger {
    my ($issues, $details, $rules) = @_;
    push @$issues, 'ledger scope is not complete'
        unless ($details->{scope} // '') eq 'complete';
    my $files = $details->{runner_files};
    push @$issues, 'ledger runner file count is missing or below current minimum'
        unless number($files)
            && $files >= ($rules->{minimum_current_runner_files} // 1);
    validate_zero_fields($issues, $details, qw(unresolved_references missing_files));
}

sub validate_comparison {
    my ($issues, $details, $ledger_files) = @_;
    push @$issues, 'comparison ran before a valid ledger file count was established'
        unless number($ledger_files);
    for my $field (qw(expected_files candidate_files)) {
        push @$issues, "$field does not match the complete ledger"
            unless number($details->{$field}) && number($ledger_files)
                && $details->{$field} == $ledger_files;
    }
    validate_zero_fields($issues, $details, qw(
        regressions missing_files zero_tap timeouts truncated execution_issues
        wrong_executable wrong_commit
    ));
}

sub validate_direct_thread {
    my ($issues, $details) = @_;
    push @$issues, 'direct/thread expected pair count is missing'
        unless number($details->{expected_pairs}) && $details->{expected_pairs} > 0;
    push @$issues, 'direct/thread pair count is incomplete'
        unless number($details->{actual_pairs}) && number($details->{expected_pairs})
            && $details->{actual_pairs} == $details->{expected_pairs};
    push @$issues, 'direct/thread mode matrix is not four-way'
        unless number($details->{expected_modes}) && $details->{expected_modes} == 4
            && number($details->{actual_modes}) && $details->{actual_modes} == 4;
    validate_zero_fields($issues, $details,
        qw(mismatches missing zero_tap timeouts truncated execution_issues));
}

sub validate_cpan {
    my ($issues, $details) = @_;
    my $expected = $details->{expected_targets};
    my $results = $details->{results};
    if (ref($expected) ne 'ARRAY' || !@$expected) {
        push @$issues, 'affected CPAN target list is empty';
        return;
    }
    if (ref($results) ne 'HASH') {
        push @$issues, 'affected CPAN results are missing';
        return;
    }
    my @expected = sort @$expected;
    my @actual = sort keys %$results;
    push @$issues, 'affected CPAN result set is incomplete or has extras'
        unless JSON::PP->new->canonical->encode(\@expected) eq
            JSON::PP->new->canonical->encode(\@actual);
    for my $target (@expected) {
        my $result = $results->{$target};
        if (ref($result) ne 'HASH') {
            push @$issues, "CPAN target $target has no result";
            next;
        }
        push @$issues, "CPAN target $target did not pass"
            unless ($result->{status} // '') eq 'pass';
        push @$issues, "CPAN target $target has zero TAP"
            unless number($result->{total_tests}) && $result->{total_tests} > 0;
        push @$issues, "CPAN target $target is incomplete"
            if true_value($result->{timeout}) || true_value($result->{truncated})
                || true_value($result->{execution_error});
    }
}

sub validate_performance {
    my ($issues, $details, $rules) = @_;
    my $minimum = $rules->{minimum_performance_samples} // 5;
    my $baseline = $details->{baseline_seconds};
    my $candidate = $details->{candidate_seconds};
    for my $pair (['baseline', $baseline], ['candidate', $candidate]) {
        push @$issues, "$pair->[0] performance samples are incomplete"
            unless ref($pair->[1]) eq 'ARRAY' && @{$pair->[1]} >= $minimum
                && !grep { !number($_) || $_ <= 0 } @{$pair->[1]};
    }
    push @$issues, 'performance samples were not alternated'
        unless true_value($details->{alternating_order});
    if (ref($baseline) eq 'ARRAY' && ref($candidate) eq 'ARRAY'
            && @$baseline >= $minimum && @$candidate >= $minimum
            && !grep { !number($_) || $_ <= 0 } (@$baseline, @$candidate)) {
        push @$issues, 'candidate warmed median regressed'
            if median($candidate) > median($baseline);
    }
}

sub validate_packaging {
    my ($issues, $details, $identity) = @_;
    push @$issues, 'packaging verification did not pass'
        unless true_value($details->{verified});
    push @$issues, 'packaging JAR identity is wrong'
        if ($details->{jar_sha256} // '') ne ($identity->{jar_sha256} // '');
    push @$issues, 'packaging SBOM identity is wrong'
        if ($details->{sbom_sha256} // '') ne ($identity->{sbom_sha256} // '');
    validate_zero_fields($issues, $details, qw(missing_entries duplicate_entries));
}

sub validate_ci {
    my ($issues, $details, $rules, $identity) = @_;
    my $results = $details->{platforms};
    if (ref($results) ne 'HASH') {
        push @$issues, 'CI platform results are missing';
        return;
    }
    for my $platform (@{ $rules->{required_ci_platforms} // [] }) {
        my $result = $results->{$platform};
        if (ref($result) ne 'HASH') {
            push @$issues, "CI platform $platform is missing";
            next;
        }
        push @$issues, "CI platform $platform did not succeed"
            unless ($result->{status} // '') eq 'success';
        push @$issues, "CI platform $platform used the wrong commit"
            unless ($result->{source_commit} // '') eq ($identity->{source_commit} // '');
    }
}

sub validate_zero_fields {
    my ($issues, $details, @fields) = @_;
    for my $field (@fields) {
        push @$issues, "$field is missing or nonzero"
            unless number($details->{$field}) && $details->{$field} == 0;
    }
}

sub number {
    my ($value) = @_;
    return defined($value) && !ref($value) && $value =~ /\A-?(?:\d+(?:\.\d*)?|\.\d+)\z/;
}

sub true_value {
    my ($value) = @_;
    return defined($value) && ($value eq '1' || $value eq 'true');
}

sub median {
    my ($values) = @_;
    my @sorted = sort { $a <=> $b } @$values;
    my $middle = int(@sorted / 2);
    return @sorted % 2 ? $sorted[$middle]
        : ($sorted[$middle - 1] + $sorted[$middle]) / 2;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!\n";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $sha->hexdigest;
}

sub load_json {
    my ($path, $label) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $label $path: $!\n";
    my $document = eval { JSON::PP->new->utf8->decode(do { local $/; <$fh> }) };
    close $fh;
    die "Invalid $label JSON in $path\n" unless $document && ref($document) eq 'HASH';
    return $document;
}

sub write_raw {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: check_phase36_acceptance_manifest.pl [OPTIONS]

Check the final Phase 36 release-evidence envelope without rerunning its
expensive producers. Report mode inventories missing/invalid gates and exits
zero; strict mode rejects any pending or invalid evidence.

Options:
  --requirements FILE  machine-readable gate policy
  --evidence FILE      collected acceptance evidence JSON
  --mode report|strict report is non-authoritative (default)
  --expected-commit SHA require one exact integrated source commit
  --output FILE         write canonical JSON report instead of stdout
  --help                show this help
USAGE
    exit $status;
}
