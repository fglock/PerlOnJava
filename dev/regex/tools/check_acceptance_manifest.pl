#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA;
use File::Basename qw(basename dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $requirements = 'dev/regex/tools/acceptance_requirements.json';
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
die "Requirements baseline_sha256 is missing or malformed\n"
    unless ($rules->{baseline_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
my $required = $rules->{required_gates};
die "Requirements have no gates\n"
    unless ref($required) eq 'ARRAY' && @$required;
my $excluded_classifications =
    $rules->{allowed_cpan_excluded_audit_classifications};
die "Requirements have no excluded CPAN audit classifications\n"
    unless ref($excluded_classifications) eq 'ARRAY'
        && @$excluded_classifications;
my $cpan_rules = $rules->{cpan_acceptance};
die "Requirements have no sealed CPAN acceptance policy\n"
    unless ref($cpan_rules) eq 'HASH'
        && ($cpan_rules->{policy_sha256} // '') =~ /\A[0-9a-f]{64}\z/
        && ref($cpan_rules->{expected_targets}) eq 'ARRAY'
        && @{$cpan_rules->{expected_targets}}
        && ref($cpan_rules->{required_modes}) eq 'ARRAY'
        && @{$cpan_rules->{required_modes}};
my %cpan_target_seen;
die "Requirements sealed CPAN target set is invalid\n"
    if grep { !defined($_) || ref($_) || !length($_) || $cpan_target_seen{$_}++ }
        @{$cpan_rules->{expected_targets}};
my %cpan_mode_seen;
die "Requirements sealed CPAN mode set must be JVM and interpreter\n"
    if grep { !defined($_) || ref($_) || !/\A(?:jvm|interpreter)\z/
            || $cpan_mode_seen{$_}++ } @{$cpan_rules->{required_modes}};
die "Requirements sealed CPAN mode set must be JVM and interpreter\n"
    unless canonical([sort keys %cpan_mode_seen])
        eq canonical([qw(interpreter jvm)]);
my %classification_seen;
for my $classification (@$excluded_classifications) {
    die "Requirements have invalid excluded CPAN audit classification\n"
        unless defined($classification) && !ref($classification)
            && $classification =~ /\A[a-z0-9]+(?:-[a-z0-9]+)*\z/
            && !$classification_seen{$classification}++;
}

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
    push @global_issues, 'evidence baseline does not match the required baseline'
        if ($identity{baseline_sha256} // '') ne $rules->{baseline_sha256};
    push @global_issues, "source commit differs from --expected-commit"
        if defined $expected_commit
            && ($identity{source_commit} // '') ne $expected_commit;
}

my $ledger_files;
my $ledger_pairs;
my $ledger_thread_only;
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
            $ledger_pairs = $details->{direct_thread_pairs}
                if number($details->{direct_thread_pairs});
            $ledger_thread_only = $details->{thread_only_tests}
                if number($details->{thread_only_tests});
        } elsif ($kind eq 'comparison') {
            validate_comparison(\@issues, $details, $ledger_files);
        } elsif ($kind eq 'direct-thread') {
            validate_direct_thread(\@issues, $details,
                $ledger_pairs, $ledger_thread_only);
        } elsif ($kind eq 'cpan') {
            validate_cpan(\@issues, $details, $rules, $evidence_root,
                $gate->{artifact}, $gate->{identity}, \%identity);
        } elsif ($kind eq 'performance') {
            validate_performance_delegation(\@issues, $details,
                $gate->{artifact}, $rules);
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
        ($kind eq 'performance' && !@issues
                && ref($gate->{details}) eq 'HASH'
                && ($gate->{details}{performance_authority} // '') eq
                    'final-release-wrapper' ? (
            performance_authority => 'final-release-wrapper') : ()),
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
    push @$issues, 'ledger runner file count is missing or zero'
        unless number($files) && $files > 0;
    push @$issues, 'ledger direct/thread pair count is missing or zero'
        unless number($details->{direct_thread_pairs})
            && $details->{direct_thread_pairs} > 0;
    push @$issues, 'ledger thread-only test count is missing'
        unless number($details->{thread_only_tests});
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
    my ($issues, $details, $ledger_pairs, $ledger_thread_only) = @_;
    push @$issues, 'direct/thread expected pair count is missing'
        unless number($details->{expected_pairs}) && $details->{expected_pairs} > 0;
    push @$issues, 'direct/thread pair count is incomplete'
        unless number($details->{actual_pairs}) && number($details->{expected_pairs})
            && $details->{actual_pairs} == $details->{expected_pairs};
    push @$issues, 'direct/thread expected pair count differs from the ledger'
        unless number($ledger_pairs) && number($details->{expected_pairs})
            && $details->{expected_pairs} == $ledger_pairs;
    push @$issues, 'direct/thread mode matrix is not four-way'
        unless number($details->{expected_modes}) && $details->{expected_modes} == 4
            && number($details->{actual_modes}) && $details->{actual_modes} == 4;
    push @$issues, 'thread-only expected count differs from the ledger'
        unless number($ledger_thread_only)
            && number($details->{expected_thread_only})
            && $details->{expected_thread_only} == $ledger_thread_only;
    push @$issues, 'thread-only test count is incomplete'
        unless number($details->{actual_thread_only})
            && number($details->{expected_thread_only})
            && $details->{actual_thread_only} == $details->{expected_thread_only};
    push @$issues, 'thread-only backend matrix is not two-way'
        unless number($details->{expected_thread_only_modes})
            && $details->{expected_thread_only_modes} == 2
            && number($details->{actual_thread_only_modes})
            && $details->{actual_thread_only_modes} == 2;
    validate_zero_fields($issues, $details,
        qw(mismatches missing zero_tap timeouts truncated execution_issues));
}

sub validate_cpan {
    my ($issues, $details, $rules, $evidence_root, $artifact,
        $gate_identity, $identity) = @_;
    my $cpan_rules = $rules->{cpan_acceptance};
    my @required_targets = sort @{$cpan_rules->{expected_targets}};
    my @required_modes = sort @{$cpan_rules->{required_modes}};
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
    my %expected_seen;
    for my $target (@$expected) {
        push @$issues, 'affected CPAN target names must be non-empty strings'
            unless defined($target) && !ref($target) && length($target);
        push @$issues, "affected CPAN target is duplicated: $target"
            if defined($target) && !ref($target) && $expected_seen{$target}++;
    }
    my @expected = sort @$expected;
    my @actual = sort keys %$results;
    push @$issues, 'affected CPAN target list differs from sealed policy'
        unless canonical(\@expected) eq canonical(\@required_targets);
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
        my @modes = sort keys %{ ref($result->{modes}) eq 'HASH'
            ? $result->{modes} : {} };
        push @$issues, "CPAN target $target mode summary is incomplete"
            unless canonical(\@modes) eq canonical(\@required_modes);
    }

    my $excluded = $details->{excluded_audits};
    if (ref($excluded) ne 'ARRAY') {
        push @$issues, 'excluded CPAN audits must be an array';
        return;
    }
    my %allowed = map { $_ => 1 }
        @{ $rules->{allowed_cpan_excluded_audit_classifications} // [] };
    my %excluded_seen;
    for my $audit (@$excluded) {
        if (ref($audit) ne 'HASH') {
            push @$issues, 'excluded CPAN audit must be an object';
            next;
        }
        my $target = $audit->{target} // '';
        push @$issues, 'excluded CPAN audit target is missing'
            unless length($target) && !ref($target);
        push @$issues, "excluded CPAN audit is duplicated: $target"
            if length($target) && $excluded_seen{$target}++;
        push @$issues, "excluded CPAN audit overlaps passing target: $target"
            if length($target) && $expected_seen{$target};
        my $classification = $audit->{classification} // '';
        push @$issues, "excluded CPAN audit $target has unsupported classification"
            unless length($classification) && $allowed{$classification};
        push @$issues, "excluded CPAN audit $target has no reason"
            unless defined($audit->{reason}) && !ref($audit->{reason})
                && length($audit->{reason});
        push @$issues, "excluded CPAN audit $target has invalid status"
            unless ($audit->{status} // '') =~ /\A(?:fail|incomplete|skipped)\z/;
        validate_artifact($issues, $audit->{artifact}, $evidence_root);
        validate_excluded_audit_identity($issues, $audit->{identity}, $target);

        if ($classification eq 'pre-existing-non-regex') {
            push @$issues, "excluded CPAN audit $target is not explicitly non-regex"
                unless false_value($audit->{regex_relevant});
            my $parent = $audit->{exact_parent};
            if (ref($parent) ne 'HASH') {
                push @$issues, "excluded CPAN audit $target has no exact-parent comparison";
                next;
            }
            push @$issues, "excluded CPAN audit $target parent commit is not a full Git SHA"
                unless ($parent->{source_commit} // '') =~ /\A[0-9a-f]{40}\z/;
            push @$issues, "excluded CPAN audit $target parent failure map is not identical"
                unless true_value($parent->{failure_map_identical});
            push @$issues, "excluded CPAN audit $target has no compared programs"
                unless number($parent->{compared_programs})
                    && $parent->{compared_programs} > 0;
            validate_artifact($issues, $parent->{artifact}, $evidence_root);
        }
    }
    validate_sealed_cpan_acceptance($issues, $artifact, $gate_identity,
        $identity, $cpan_rules, $evidence_root, $details);
}

sub validate_sealed_cpan_acceptance {
    my ($issues, $artifact, $gate_identity, $identity, $cpan_rules,
        $evidence_root, $details) = @_;
    return unless ref($artifact) eq 'HASH' && length($artifact->{path} // '');
    my $manifest_path = artifact_path($artifact->{path}, $evidence_root);
    return unless -f $manifest_path && -s $manifest_path;
    push @$issues, 'CPAN gate artifact is not cpan-acceptance.json'
        unless basename($manifest_path) eq 'cpan-acceptance.json';

    my $seal_path = "$manifest_path.sha256";
    if (!-f $seal_path || !-s $seal_path) {
        push @$issues, 'CPAN acceptance seal is missing';
        return;
    }
    my $seal_text = read_raw($seal_path);
    my ($sealed_sha, $sealed_name) =
        $seal_text =~ /\A([0-9a-f]{64})\s+([^\s]+)\s*\z/;
    push @$issues, 'CPAN acceptance seal is malformed'
        unless $sealed_sha && ($sealed_name // '') eq 'cpan-acceptance.json';
    push @$issues, 'CPAN acceptance seal does not match its manifest'
        unless $sealed_sha && $sealed_sha eq sha256_file($manifest_path);

    my $document = eval { load_json($manifest_path, 'sealed CPAN acceptance') };
    if (!$document) {
        push @$issues, 'sealed CPAN acceptance JSON is invalid';
        return;
    }
    my $cpan_schema = $document->{schema_version} // 0;
    push @$issues, 'CPAN acceptance schema_version must be 1 or 2'
        unless $cpan_schema == 1 || $cpan_schema == 2;
    push @$issues, 'CPAN acceptance mode is not acceptance'
        unless ($document->{mode} // '') eq 'acceptance';
    push @$issues, 'CPAN acceptance aggregate did not pass'
        unless ($document->{status} // '') eq 'pass';
    push @$issues, 'CPAN acceptance has excluded audits'
        unless ref($document->{excluded_audits}) eq 'ARRAY'
            && !@{$document->{excluded_audits}};

    my $cpan_identity = ref($document->{identity}) eq 'HASH'
        ? $document->{identity} : {};
    for my $pair (
        [source_commit => 'source_commit', 'source'],
        [runner_commit => 'runner_commit', 'runner'],
        [perl5_commit => 'perl5_commit', 'perl5'],
        [jperl_sha256 => 'jperl_sha256', 'executable'],
        [jar_sha256 => 'jar_sha256', 'JAR'],
        [sbom_sha256 => 'sbom_sha256', 'SBOM'],
    ) {
        push @$issues, "CPAN acceptance $pair->[2] identity is wrong"
            if ($cpan_identity->{$pair->[0]} // '')
                ne ($identity->{$pair->[1]} // '');
    }
    push @$issues, 'CPAN acceptance policy identity is wrong'
        if ($cpan_identity->{policy_sha256} // '')
            ne ($cpan_rules->{policy_sha256} // '');
    for my $field (qw(manifest_sha256 jcpan_sha256)) {
        push @$issues, "CPAN acceptance $field is missing or malformed"
            unless ($cpan_identity->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    my $inputs = ref($cpan_identity->{inputs}) eq 'HASH'
        ? $cpan_identity->{inputs} : {};
    my @input_checks = (
        [source => commit => $identity->{source_commit}],
        [perl5 => commit => $identity->{perl5_commit}],
        [jperl => sha256 => $identity->{jperl_sha256}],
        [jar => sha256 => $identity->{jar_sha256}],
        [sbom => sha256 => $identity->{sbom_sha256}],
        [jcpan => sha256 => $cpan_identity->{jcpan_sha256}],
    );
    for my $check (@input_checks) {
        my ($name, $field, $expected) = @$check;
        push @$issues, "CPAN acceptance input identity is wrong: $name"
            unless ref($inputs->{$name}) eq 'HASH'
                && length($inputs->{$name}{path} // '')
                && ($inputs->{$name}{$field} // '') eq ($expected // '');
    }
    if (ref($gate_identity) ne 'HASH') {
        push @$issues, 'CPAN gate identity is missing';
    } else {
        for my $field (qw(source_commit runner_commit perl5_commit jperl_sha256
                jar_sha256 sbom_sha256 baseline_sha256)) {
            push @$issues, "CPAN gate $field identity is wrong"
                if ($gate_identity->{$field} // '') ne ($identity->{$field} // '');
        }
        push @$issues, 'CPAN gate policy identity is wrong'
            if ($gate_identity->{policy_sha256} // '')
                ne ($cpan_rules->{policy_sha256} // '');
    }

    my @manifest_targets = sort @{ ref($document->{expected_targets}) eq 'ARRAY'
        ? $document->{expected_targets} : [] };
    my @required_targets = sort @{$cpan_rules->{expected_targets}};
    push @$issues, 'CPAN acceptance target set is wrong'
        unless canonical(\@manifest_targets) eq canonical(\@required_targets);
    my $cpan_results = ref($document->{results}) eq 'HASH'
        ? $document->{results} : {};
    push @$issues, 'CPAN acceptance result set is wrong'
        unless canonical([sort keys %$cpan_results]) eq canonical(\@required_targets);
    my $summary_results = ref($details->{results}) eq 'HASH'
        ? $details->{results} : {};

    my $root = dirname($manifest_path);
    my %expected_artifacts = ('jperl-version.log' => 'jperl-version');
    for my $target (@required_targets) {
        for my $mode (@{$cpan_rules->{required_modes}}) {
            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            $expected_artifacts{File::Spec->catfile($base, 'raw.log')} = 'raw-log';
            $expected_artifacts{File::Spec->catfile($base, 'result.json')} = 'mode-result';
        }
    }
    my %retained;
    for my $descriptor (@{ ref($document->{artifacts}) eq 'ARRAY'
            ? $document->{artifacts} : [] }) {
        if (ref($descriptor) ne 'HASH') {
            push @$issues, 'CPAN acceptance artifact descriptor is malformed';
            next;
        }
        my $relative = $descriptor->{path} // '';
        if (!length($relative) || File::Spec->file_name_is_absolute($relative)
                || grep { $_ eq '..' } File::Spec->splitdir($relative)) {
            push @$issues, "CPAN acceptance artifact path is unsafe: $relative";
            next;
        }
        if (!defined($expected_artifacts{$relative})
                || ($descriptor->{kind} // '') ne $expected_artifacts{$relative}
                || $retained{$relative}) {
            push @$issues, "CPAN acceptance artifact set is wrong: $relative";
            next;
        }
        $retained{$relative} = $descriptor;
        my $path = File::Spec->catfile($root, File::Spec->splitdir($relative));
        if (!-f $path || !-s $path) {
            push @$issues, "CPAN acceptance artifact is missing: $relative";
            next;
        }
        my $resolved = abs_path($path);
        my $resolved_root = abs_path($root);
        if (!defined($resolved) || !defined($resolved_root)
                || index($resolved, "$resolved_root/") != 0) {
            push @$issues, "CPAN acceptance artifact resolves outside evidence root: $relative";
            next;
        }
        push @$issues, "CPAN acceptance artifact hash mismatch: $relative"
            unless ($descriptor->{sha256} // '') =~ /\A[0-9a-f]{64}\z/
                && sha256_file($path) eq $descriptor->{sha256};
    }
    my @missing_artifacts = sort grep { !$retained{$_} } keys %expected_artifacts;
    push @$issues, 'CPAN acceptance artifact set is incomplete: '
        . join(', ', @missing_artifacts) if @missing_artifacts;

    my ($aggregate_total, $aggregate_pass) = (0, 1);
    for my $target (@required_targets) {
        my $target_result = $cpan_results->{$target};
        if (ref($target_result) ne 'HASH') {
            $aggregate_pass = 0;
            next;
        }
        my $modes = ref($target_result->{modes}) eq 'HASH'
            ? $target_result->{modes} : {};
        my @modes = sort keys %$modes;
        my @required_modes = sort @{$cpan_rules->{required_modes}};
        push @$issues, "CPAN acceptance mode set is wrong: $target"
            unless canonical(\@modes) eq canonical(\@required_modes);
        my ($target_total, $target_pass) = (0, 1);
        for my $mode (@required_modes) {
            my $mode_result = $modes->{$mode};
            if (ref($mode_result) ne 'HASH') {
                $target_pass = 0;
                next;
            }
            push @$issues, "CPAN acceptance mode identity is wrong: $target $mode"
                unless ($mode_result->{target} // '') eq $target
                    && ($mode_result->{mode} // '') eq $mode;
            my $mode_identity = ref($mode_result->{identity}) eq 'HASH'
                ? $mode_result->{identity} : {};
            for my $field (qw(source_commit runner_commit perl5_commit
                    jperl_sha256 jar_sha256 sbom_sha256)) {
                push @$issues, "CPAN acceptance mode identity is wrong: $target $mode $field"
                    if ($mode_identity->{$field} // '')
                        ne ($identity->{$field} // '');
            }
            my $argv = $mode_result->{argv};
            push @$issues, "CPAN acceptance command is wrong: $target $mode"
                unless ref($argv) eq 'ARRAY' && @$argv == 3
                    && length($argv->[0] // '')
                    && $argv->[1] eq '-t' && $argv->[2] eq $target;
            my $environment = ref($mode_result->{environment}) eq 'HASH'
                ? $mode_result->{environment} : {};
            push @$issues, "CPAN acceptance backend selector is wrong: $target $mode"
                unless ($environment->{REGEX_IMPLEMENTATION_CPAN_TARGET} // '') eq $target
                    && ($environment->{REGEX_IMPLEMENTATION_CPAN_MODE} // '') eq $mode
                    && ($cpan_schema < 2
                        || (exists($environment->{JPERL_UNIMPLEMENTED})
                            && !defined($environment->{JPERL_UNIMPLEMENTED})))
                    && ($mode eq 'interpreter'
                        ? (($environment->{JPERL_INTERPRETER} // '') eq '1')
                        : (exists($environment->{JPERL_INTERPRETER})
                            && !defined($environment->{JPERL_INTERPRETER})));
            my @environment_keys = qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME TMPDIR
                PERL_MM_USE_DEFAULT JPERL_INTERPRETER REGEX_IMPLEMENTATION_CPAN_TARGET
                REGEX_IMPLEMENTATION_CPAN_MODE);
            push @environment_keys, 'JPERL_UNIMPLEMENTED' if $cpan_schema >= 2;
            my $missing_environment_keys = grep {
                !exists($environment->{$_}) } @environment_keys;
            push @$issues, "CPAN acceptance environment is wrong: $target $mode"
                unless !$missing_environment_keys
                    && ($environment->{PERLONJAVA_JAR} // '')
                        eq ($inputs->{jar}{path} // '')
                    && ($environment->{PERLONJAVA_HOME} // '')
                        eq ($environment->{HOME} // '')
                    && ($environment->{PERL_MM_USE_DEFAULT} // '') eq '1';
            push @$issues, "CPAN acceptance environment hash is wrong: $target $mode"
                unless ($mode_result->{environment_sha256} // '') eq
                    Digest::SHA::sha256_hex(canonical({ map {
                        $_ => $environment->{$_} } @environment_keys }));
            my $mode_pass = ($mode_result->{status} // '') eq 'pass'
                && number($mode_result->{total_tests}) && $mode_result->{total_tests} > 0
                && number($mode_result->{exit_code}) && $mode_result->{exit_code} == 0
                && number($mode_result->{signal}) && $mode_result->{signal} == 0
                && false_value($mode_result->{timeout})
                && false_value($mode_result->{execution_error})
                && false_value($mode_result->{zero_tap})
                && false_value($mode_result->{malformed})
                && false_value($mode_result->{truncated})
                && number($mode_result->{failures}) && $mode_result->{failures} == 0;
            push @$issues, "CPAN acceptance mode did not pass: $target $mode"
                unless $mode_pass;
            push @$issues, "CPAN acceptance has unapproved warnings: $target $mode"
                unless ref($mode_result->{unapproved_warnings}) eq 'ARRAY'
                    && !@{$mode_result->{unapproved_warnings}};
            push @$issues, "CPAN acceptance has warning diagnostics: $target $mode"
                unless ref($mode_result->{warning_diagnostics}) eq 'ARRAY'
                    && !@{$mode_result->{warning_diagnostics}};

            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            my $raw_relative = File::Spec->catfile($base, 'raw.log');
            my $meta_relative = File::Spec->catfile($base, 'result.json');
            my $raw_path = File::Spec->catfile($root,
                File::Spec->splitdir($raw_relative));
            my $meta_path = File::Spec->catfile($root,
                File::Spec->splitdir($meta_relative));
            push @$issues, "CPAN acceptance raw log hash mismatch: $target $mode"
                unless ref($mode_result->{raw_log}) eq 'HASH'
                    && ($mode_result->{raw_log}{path} // '') eq $raw_relative
                    && ($mode_result->{raw_log}{sha256} // '') =~ /\A[0-9a-f]{64}\z/
                    && -f $raw_path
                    && sha256_file($raw_path) eq $mode_result->{raw_log}{sha256};
            if (-f $raw_path) {
                my $raw = read_raw($raw_path);
                push @$issues, "CPAN acceptance raw log has an unapproved warning: $target $mode"
                    if unapproved_warning_lines($raw);
                my @summaries = $raw =~ /^Files=\d+,\s+Tests=(\d+)\b/mg;
                push @$issues, "CPAN acceptance raw TAP total is wrong: $target $mode"
                    unless @summaries && $summaries[-1] == $mode_result->{total_tests};
            }
            if (-f $meta_path) {
                my $retained_mode = eval {
                    load_json($meta_path, 'retained CPAN mode result') };
                push @$issues, "CPAN acceptance mode result differs from aggregate: $target $mode"
                    unless $retained_mode
                        && canonical($retained_mode) eq canonical($mode_result);
            }
            $target_total += number($mode_result->{total_tests})
                ? $mode_result->{total_tests} : 0;
            $target_pass = 0 unless $mode_pass;
        }
        push @$issues, "CPAN acceptance target aggregate is wrong: $target"
            unless ($target_result->{status} // '') eq ($target_pass ? 'pass' : 'fail')
                && number($target_result->{total_tests})
                && $target_result->{total_tests} == $target_total
                && false_value($target_result->{timeout})
                && false_value($target_result->{truncated})
                && false_value($target_result->{execution_error});
        my $summary = $summary_results->{$target};
        my @summary_modes = sort keys %{ ref($summary) eq 'HASH'
                && ref($summary->{modes}) eq 'HASH' ? $summary->{modes} : {} };
        my @sealed_modes = sort keys %$modes;
        push @$issues, "CPAN gate summary differs from sealed acceptance: $target"
            unless ref($summary) eq 'HASH'
                && ($summary->{status} // '') eq ($target_result->{status} // '')
                && number($summary->{total_tests})
                && $summary->{total_tests} == $target_result->{total_tests}
                && canonical(\@summary_modes) eq canonical(\@sealed_modes)
                && !(grep {
                    ($summary->{modes}{$_}{status} // '')
                        ne ($modes->{$_}{status} // '')
                } @sealed_modes);
        $aggregate_total += $target_total;
        $aggregate_pass = 0 unless $target_pass;
    }
    push @$issues, 'CPAN acceptance aggregate did not pass'
        unless $aggregate_pass && ($document->{status} // '') eq 'pass';
    push @$issues, 'CPAN acceptance aggregate TAP total is wrong'
        unless number($document->{total_tests})
            && $document->{total_tests} == $aggregate_total;
}

sub artifact_path {
    my ($path, $base) = @_;
    return File::Spec->file_name_is_absolute($path)
        ? $path : File::Spec->catfile($base, File::Spec->splitdir($path));
}

sub unapproved_warning_lines {
    my ($text) = @_;
    return grep {
        my $line = $_;
        $line !~ /^\s*(?:ok|not ok|#)/i
            && $line =~ /(?:Use of uninitialized|uninitialized value|Argument .* isn't numeric|Possible unintended interpolation|Wide character in|Subroutine .* redefined|WARNING:|warning:|\bat\s+\S.*\s+line\s+\d+\.?\s*$)/i
    } split /\n/, $text;
}

sub canonical {
    return JSON::PP->new->canonical->encode($_[0]);
}

sub slug {
    my $slug = lc $_[0];
    $slug =~ s/[^a-z0-9]+/-/g;
    $slug =~ s/^-|-$//g;
    return $slug;
}

sub validate_excluded_audit_identity {
    my ($issues, $identity, $target) = @_;
    if (ref($identity) ne 'HASH') {
        push @$issues, "excluded CPAN audit $target identity is missing";
        return;
    }
    for my $field (qw(source_commit runner_commit perl5_commit)) {
        push @$issues, "excluded CPAN audit $target $field is not a full Git SHA"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    push @$issues, "excluded CPAN audit $target runner commit differs from its source"
        if ($identity->{runner_commit} // '') ne ($identity->{source_commit} // '');
    push @$issues, "excluded CPAN audit $target jperl_sha256 is not SHA-256"
        unless ($identity->{jperl_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
}

sub validate_performance_delegation {
    my ($issues, $details, $artifact, $rules) = @_;
    my @legacy = qw(baseline_seconds candidate_seconds alternating_order);
    if (keys(%$details) == @legacy
            && !(grep { !exists $details->{$_} } @legacy)
            && established_legacy_performance_contract($rules)) {
        validate_legacy_performance_compatibility($issues, $details, $rules);
        return;
    }
    my @required = qw(final_performance_contract final_performance_sha256
        performance_authority);
    my %required = map { $_ => 1 } @required;
    my @unexpected = sort grep { !$required{$_} } keys %$details;
    push @$issues, 'performance gate contains legacy or mixed authority fields: '
        . join(', ', @unexpected) if @unexpected;
    push @$issues, 'performance gate delegation fields are incomplete'
        unless keys(%$details) == @required
            && !grep { !exists $details->{$_} } @required;
    push @$issues, 'performance final-artifact contract is unsupported'
        unless ($details->{final_performance_contract} // '') eq
            'regex_implementation-final-performance/v1';
    push @$issues, 'performance authority is not delegated to the final wrapper'
        unless ($details->{performance_authority} // '') eq
            'final-release-wrapper';
    push @$issues, 'performance final-artifact SHA-256 is malformed'
        unless ($details->{final_performance_sha256} // '')
            =~ /\A[0-9a-f]{64}\z/;
    push @$issues, 'performance final-artifact hash differs from gate artifact'
        unless ref($artifact) eq 'HASH'
            && ($details->{final_performance_sha256} // '') eq
                ($artifact->{sha256} // '');
}

sub established_legacy_performance_contract {
    my ($rules) = @_;
    my @expected = (
        'ledger:ledger', 'jvm:comparison', 'interpreter:comparison',
        'direct-thread:direct-thread', 'cpan:cpan',
        'performance:performance', 'packaging:packaging',
        'notice-license:notice-license', 'make:make', 'ci:ci',
    );
    my $required = $rules->{required_gates};
    return 0 unless ref($required) eq 'ARRAY';
    my @actual = map { ($_->{id} // '') . ':' . ($_->{kind} // '') } @$required;
    return canonical(\@actual) eq canonical(\@expected);
}

sub validate_legacy_performance_compatibility {
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

sub false_value {
    my ($value) = @_;
    return defined($value) && ($value eq '0' || $value eq 'false');
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

sub read_raw {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $contents = <$fh>;
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
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
Usage: check_acceptance_manifest.pl [OPTIONS]

Check the final Regex implementation release-evidence envelope without rerunning its
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
