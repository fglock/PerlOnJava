#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA;
use Fcntl qw(O_CREAT O_EXCL O_RDONLY O_WRONLY);
use File::Basename qw(basename dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;
use IO::Handle;
use MIME::Base64 qw(decode_base64 encode_base64);

my $MAX_JSON_BYTES = 64 * 1024 * 1024;
my $MAX_BLOB_BYTES = 4 * 1024 * 1024 * 1024;
my $MAX_DECIMAL_DIGITS = 18;
my $MAX_COUNT = 10_000_000;
my $MAX_PERFORMANCE_SAMPLES = 10_000;
my %immutable_snapshot;
our $REVALIDATING_INPUTS;
my ($authority_path, $requirements_path, $expected_candidate,
    $expected_baseline, $expected_perl5, $expected_runner,
    $expected_jperl, $expected_jar, $expected_sbom, $expected_authority,
    $expected_requirements, $output, $help);
$requirements_path = 'dev/tools/phase36_acceptance_requirements.json';
GetOptions(
    'authority=s' => \$authority_path,
    'requirements=s' => \$requirements_path,
    'expected-candidate=s' => \$expected_candidate,
    'expected-baseline=s' => \$expected_baseline,
    'expected-perl5=s' => \$expected_perl5,
    'expected-runner=s' => \$expected_runner,
    'expected-jperl-sha256=s' => \$expected_jperl,
    'expected-jar-sha256=s' => \$expected_jar,
    'expected-sbom-sha256=s' => \$expected_sbom,
    'expected-authority-sha256=s' => \$expected_authority,
    'expected-requirements-sha256=s' => \$expected_requirements,
    'output=s' => \$output,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
die "--authority is required\n" unless defined $authority_path;
die "--expected-candidate must be a full Git SHA\n"
    unless ($expected_candidate // '') =~ /\A[0-9a-f]{40}\z/;
die "--expected-baseline must be SHA-256\n"
    unless ($expected_baseline // '') =~ /\A[0-9a-f]{64}\z/;
for my $entry ([perl5 => $expected_perl5], [runner => $expected_runner]) {
    die "--expected-$entry->[0] must be a full Git SHA\n"
        unless ($entry->[1] // '') =~ /\A[0-9a-f]{40}\z/;
}
for my $entry ([jperl => $expected_jperl], [jar => $expected_jar],
        [sbom => $expected_sbom], [authority => $expected_authority],
        [requirements => $expected_requirements]) {
    die "--expected-$entry->[0]-sha256 must be SHA-256\n"
        unless ($entry->[1] // '') =~ /\A[0-9a-f]{64}\z/;
}
die "--output is required\n" unless defined $output && length $output;

my $authority = load_json($authority_path, 'authority', $expected_authority);
my $requirements = load_json($requirements_path, 'requirements',
    $expected_requirements);
my $selected_requirements = abs_path($requirements_path);
my $requirements_sha256 = $expected_requirements;
reject_extra_keys($authority, 'authority', qw(schema_version kind mode identity
    prerequisites lanes));
die "Authority schema_version must be 1\n"
    unless ($authority->{schema_version} // 0) == 1;
die "Authority kind must be phase36-envelope-authority\n"
    unless ($authority->{kind} // '') eq 'phase36-envelope-authority';
die "Authority mode must be acceptance\n"
    unless ($authority->{mode} // '') eq 'acceptance';
die "Requirements schema_version must be 1\n"
    unless ($requirements->{schema_version} // 0) == 1;
validate_requirements_policy($requirements);
die "Requirements baseline differs from --expected-baseline\n"
    unless ($requirements->{baseline_sha256} // '') eq $expected_baseline;

my $identity = validate_global_identity($authority->{identity});
die "Authority source differs from --expected-candidate\n"
    unless $identity->{source_commit} eq $expected_candidate;
die "Authority baseline differs from --expected-baseline\n"
    unless $identity->{baseline_sha256} eq $expected_baseline;
my %trusted_identity = (
    perl5_commit => $expected_perl5, runner_commit => $expected_runner,
    jperl_sha256 => $expected_jperl, jar_sha256 => $expected_jar,
    sbom_sha256 => $expected_sbom,
);
for my $field (sort keys %trusted_identity) {
    die "Authority $field differs from trusted CLI identity\n"
        unless $identity->{$field} eq $trusted_identity{$field};
}
my $prerequisite = validate_prerequisite($authority->{prerequisites},
    $identity, dirname(File::Spec->rel2abs($authority_path)));

my @required = @{$requirements->{required_gates} // []};
die "Requirements must contain exactly ten gates\n" unless @required == 10;
my %required = map { ($_->{id} // '') => ($_->{kind} // '') } @required;
my @expected_ids = qw(ledger jvm interpreter direct-thread cpan performance
    packaging notice-license make ci);
die "Requirements gate set is not the Phase 36 ten-gate set\n"
    unless canonical([sort keys %required]) eq canonical([sort @expected_ids]);
my %expected_kind = (
    ledger => 'ledger', jvm => 'comparison', interpreter => 'comparison',
    'direct-thread' => 'direct-thread', cpan => 'cpan',
    performance => 'performance', packaging => 'packaging',
    'notice-license' => 'notice-license', make => 'make', ci => 'ci',
);
die "Requirements gate kind map is not the exact Phase 36 policy\n"
    unless canonical(\%required) eq canonical(\%expected_kind);

my $authority_root = abs_path(dirname(File::Spec->rel2abs($authority_path)))
    or die "Cannot resolve authority directory\n";
my $output_absolute = File::Spec->rel2abs($output);
my $output_root = abs_path(dirname($output_absolute))
    or die "Output directory does not exist\n";
die "Authority and output must share one evidence directory\n"
    unless $authority_root eq $output_root;
die "Refusing to overwrite output $output_absolute\n" if -e $output_absolute;

my $lanes = $authority->{lanes};
die "Authority lanes must be an array\n" unless ref($lanes) eq 'ARRAY';
my (%selection, %producer_document);
for my $lane (@$lanes) {
    die "Authority lane must be an object\n" unless ref($lane) eq 'HASH';
    reject_extra_keys($lane, 'authority lane', qw(gate producer artifact));
    my $gate = $lane->{gate} // '';
    die "Unknown authority gate '$gate'\n" unless exists $required{$gate};
    die "Duplicate authority gate '$gate'\n" if $selection{$gate};
    my $producer = $lane->{producer} // '';
    my $expected_producer = producer_for($gate);
    die "Gate $gate has wrong producer '$producer'\n"
        unless $producer eq $expected_producer;
    my $artifact = validate_descriptor($lane->{artifact}, $authority_root,
        "gate $gate artifact", 0, $MAX_JSON_BYTES);
    my $cache_key = join("\0", $producer, $artifact->{absolute},
        $artifact->{sha256});
    my $document = $producer_document{$cache_key} //=
        decode_json_bytes($artifact->{bytes}, "gate $gate producer artifact",
            $artifact->{absolute});
    $selection{$gate} = {
        producer => $producer,
        artifact => $artifact,
        document => $document,
    };
}
my @missing = grep { !$selection{$_} } @expected_ids;
die "Missing authority gates: " . join(', ', @missing) . "\n" if @missing;

my %gates;
my $regex = validate_regex_producer($selection{ledger}, $identity,
    $authority_root, \%selection);
for my $gate (qw(jvm interpreter)) {
    die "Regex producer selection differs across $gate\n"
        unless same_artifact($selection{ledger}{artifact},
            $selection{$gate}{artifact});
}
$gates{ledger} = gate_record($selection{ledger},
    { source_commit => $identity->{source_commit} }, $regex->{ledger});
for my $backend (qw(jvm interpreter)) {
    $gates{$backend} = gate_record($selection{$backend}, {
        source_commit => $identity->{source_commit},
        runner_commit => $identity->{runner_commit},
        jperl_sha256 => $identity->{jperl_sha256},
        baseline_sha256 => $identity->{baseline_sha256},
    }, $regex->{$backend});
}
my $package = validate_package($selection{packaging}, $identity);
$gates{packaging} = gate_record($selection{packaging},
    { source_commit => $identity->{source_commit} }, {
        verified => JSON::PP::true, jar_sha256 => $identity->{jar_sha256},
        sbom_sha256 => $identity->{sbom_sha256}, missing_entries => 0,
        duplicate_entries => 0,
    });

my $direct = validate_direct_thread($selection{'direct-thread'}{document},
    $identity);
$gates{'direct-thread'} = gate_record($selection{'direct-thread'}, {
    source_commit => $identity->{source_commit},
    runner_commit => $identity->{runner_commit},
    jperl_sha256 => $identity->{jperl_sha256},
}, $direct);

my $cpan = validate_cpan($selection{cpan}{document}, $identity,
    $requirements, $selection{cpan}{artifact}{absolute},
    $selection{cpan}{artifact}{sha256});
$gates{cpan} = gate_record($selection{cpan}, {
    source_commit => $identity->{source_commit},
    runner_commit => $identity->{runner_commit},
    perl5_commit => $identity->{perl5_commit},
    jperl_sha256 => $identity->{jperl_sha256},
    jar_sha256 => $identity->{jar_sha256},
    sbom_sha256 => $identity->{sbom_sha256},
    baseline_sha256 => $identity->{baseline_sha256},
    policy_sha256 => $requirements->{cpan_acceptance}{policy_sha256},
}, $cpan);

my $performance = validate_performance($selection{performance}, $identity,
    $requirements);
$gates{performance} = gate_record($selection{performance},
    { source_commit => $identity->{source_commit} }, $performance);

my $notice = validate_notice($selection{'notice-license'}{document}, $identity);
my $package_notice = $package->{artifacts}{notice_license};
die "Notice/license gate is not the strict package-retained artifact\n"
    unless ref($package_notice) eq 'HASH'
        && ($package_notice->{sha256} // '') eq
            $selection{'notice-license'}{artifact}{sha256};
$gates{'notice-license'} = gate_record($selection{'notice-license'},
    { source_commit => $identity->{source_commit} }, $notice);

my $make = validate_make($selection{make}, $identity);
validate_regex_release_authority($regex->{release_authority}, $identity,
    \%selection, $authority_root);
$gates{make} = gate_record($selection{make},
    { source_commit => $identity->{source_commit} }, {
        passed => JSON::PP::true,
        warnings => 0, failures => 0,
    });

my $ci = validate_ci($selection{ci}{document}, $identity, $requirements,
    $requirements_sha256);
my $platforms = $ci->{platforms};
$gates{ci} = gate_record($selection{ci},
    { source_commit => $identity->{source_commit} }, { platforms => $platforms });

my $envelope = {
    schema_version => 1,
    mode => 'acceptance',
    identity => $identity,
    prerequisites => { perl5_sync => $prerequisite },
    gates => \%gates,
};
wait_at_prepublication_test_boundary();
revalidate_immutable_inputs();
publish_exclusive_atomic($output_absolute,
    JSON::PP->new->utf8->canonical->pretty->encode($envelope));
print "$output_absolute\n";

sub producer_for {
    my ($gate) = @_;
    return 'run_phase36_regex_acceptance.pl'
        if $gate =~ /\A(?:ledger|jvm|interpreter)\z/;
    return 'collect_phase36_direct_thread.pl' if $gate eq 'direct-thread';
    return 'run_phase36_cpan_acceptance.pl' if $gate eq 'cpan';
    return 'run_phase36_final_performance.pl' if $gate eq 'performance';
    return 'run_phase36_package_evidence.pl' if $gate eq 'packaging';
    return 'verify_phase36_notice_license.pl' if $gate eq 'notice-license';
    return 'run_phase36_make_evidence.pl' if $gate eq 'make';
    return 'run_phase36_ci_evidence.pl' if $gate eq 'ci';
    die "No producer contract for $gate\n";
}

sub validate_requirements_policy {
    my ($requirements) = @_;
    reject_extra_keys($requirements, 'requirements', qw(schema_version policy
        baseline_sha256 performance_acceptance cpan_acceptance
        allowed_cpan_excluded_audit_classifications required_ci_platforms
        required_gates));
    die "Requirements policy statement is missing\n"
        unless defined($requirements->{policy}) && !ref($requirements->{policy})
            && length($requirements->{policy});
    my $performance = $requirements->{performance_acceptance};
    die "Requirements performance acceptance policy is missing\n"
        unless ref($performance) eq 'HASH';
    reject_extra_keys($performance, 'requirements performance acceptance', qw(
        schema_version authoritative_producer authoritative_checker
        authority_model process_tree_contract windows_process_tree_policy
        authority_key_unix_mode authority_key_windows_policy jfr_evaluation
        maximum_jfr_recording_bytes maximum_jfr_metrics_bytes
        maximum_expensive_owners legacy_timing_summary_authoritative
        envelope_contract release_authority minimum_ordinary_samples
        ordinary_semantic_checksum ordinary_operations ordered_execution_order
        psycho_speed_rows thresholds));
    die "Requirements performance authority contract is wrong\n"
        unless ($performance->{schema_version} // 0) == 1
            && ($performance->{authoritative_producer} // '') eq
                'dev/tools/run_phase36_final_performance.pl'
            && ($performance->{authoritative_checker} // '') eq
                'dev/tools/check_phase36_final_performance.pl'
            && ($performance->{process_tree_contract} // '') eq
                'unix-process-groups-v1'
            && ($performance->{envelope_contract} // '') eq
                'phase36-final-performance/v1'
            && ($performance->{release_authority} // '') eq
                'final-release-wrapper'
            && false_value($performance->{legacy_timing_summary_authoritative})
            && count_number($performance->{minimum_ordinary_samples})
            && $performance->{minimum_ordinary_samples} > 0
            && $performance->{minimum_ordinary_samples}
                <= $MAX_PERFORMANCE_SAMPLES;
    die "Requirements ordered performance policy is wrong\n"
        unless ref($performance->{ordered_execution_order}) eq 'ARRAY'
            && canonical($performance->{ordered_execution_order}) eq
                canonical([qw(baseline candidate candidate baseline)]);
    die "Requirements psycho/speed policy is malformed\n"
        unless ref($performance->{psycho_speed_rows}) eq 'ARRAY'
            && @{$performance->{psycho_speed_rows}} == 4;
    my %psycho_seen;
    for my $row (@{$performance->{psycho_speed_rows}}) {
        die "Requirements psycho/speed policy row is malformed\n"
            unless ref($row) eq 'HASH';
        reject_extra_keys($row, 'requirements psycho/speed policy row',
            qw(test plan passed skipped));
        die "Requirements psycho/speed policy row is invalid\n"
            unless defined($row->{test}) && !ref($row->{test})
                && length($row->{test}) && !$psycho_seen{$row->{test}}++
                && count_number($row->{plan}) && count_number($row->{passed})
                && count_number($row->{skipped})
                && $row->{passed} + $row->{skipped} == $row->{plan};
    }
    die "Requirements performance thresholds are missing\n"
        unless ref($performance->{thresholds}) eq 'HASH';
    reject_extra_keys($performance->{thresholds},
        'requirements performance thresholds', qw(
            root_reflective_allocation_reduction live_heap_relative_allowance
            live_heap_absolute_allowance_bytes committed_heap_rss_review_ratio));
    my $cpan = $requirements->{cpan_acceptance};
    die "Requirements CPAN acceptance policy is missing\n"
        unless ref($cpan) eq 'HASH';
    reject_extra_keys($cpan, 'requirements CPAN acceptance', qw(policy_sha256
        expected_targets required_modes));
    die "Requirements CPAN policy hash is malformed\n"
        unless ($cpan->{policy_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    die "Requirements gate policy is missing\n"
        unless ref($requirements->{required_gates}) eq 'ARRAY';
    for my $gate (@{$requirements->{required_gates}}) {
        die "Requirements gate entry is malformed\n" unless ref($gate) eq 'HASH';
        reject_extra_keys($gate, 'requirements gate entry',
            qw(id kind description));
    }
}

sub validate_global_identity {
    my ($identity) = @_;
    die "Authority identity must be an object\n" unless ref($identity) eq 'HASH';
    reject_extra_keys($identity, 'authority identity', qw(source_commit
        perl5_commit runner_commit jperl_sha256 jar_sha256 sbom_sha256
        baseline_sha256));
    for my $field (qw(source_commit perl5_commit runner_commit)) {
        die "Authority $field is not a full Git SHA\n"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    for my $field (qw(jperl_sha256 jar_sha256 sbom_sha256 baseline_sha256)) {
        die "Authority $field is not SHA-256\n"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    die "Authority runner commit differs from source commit\n"
        unless $identity->{runner_commit} eq $identity->{source_commit};
    return { map { $_ => $identity->{$_} } qw(source_commit perl5_commit
        runner_commit jperl_sha256 jar_sha256 sbom_sha256 baseline_sha256) };
}

sub validate_regex_producer {
    my ($selection, $identity, $root, $all_selection) = @_;
    my $document = $selection->{document};
    reject_extra_keys($document, 'regex producer', qw(schema_version mode source
        identity baseline artifact_directory expected_files
        strict_regex_expected_files verified_runner_sha ledger_summary
        strict_regex_ledger_summary commands exit_statuses artifacts status
        timeout incomplete review_stop release_authority));
    die "Regex producer schema_version must be 1\n"
        unless ($document->{schema_version} // 0) == 1;
    die "Regex producer mode is not acceptance\n"
        unless ($document->{mode} // '') eq 'acceptance';
    my $source = $document->{source};
    die "Regex producer source identity is incomplete\n"
        unless ref($source) eq 'HASH'
            && ($source->{starting_sha} // '') eq $identity->{source_commit}
            && ($source->{final_sha} // '') eq $identity->{source_commit}
            && ($source->{perl5_sha_as_provenance} // '') eq $identity->{perl5_commit};
    reject_extra_keys($source, 'regex producer source', qw(starting_sha final_sha
        perl5_sha_as_provenance tracked_state_signature));
    die "Regex producer tracked source signature is missing\n"
        unless ($source->{tracked_state_signature} // '') =~ /\A[0-9a-f]{64}\z/;
    my $producer_identity = $document->{identity};
    die "Regex producer identity is missing\n"
        unless ref($producer_identity) eq 'HASH';
    reject_extra_keys($producer_identity, 'regex producer identity', qw(
        source_commit runner_commit perl5_commit launcher jar sbom baseline
        runner_policy));
    for my $field (qw(source_commit runner_commit perl5_commit)) {
        die "Regex producer $field is stale\n"
            unless ($producer_identity->{$field} // '') eq $identity->{$field};
    }
    for my $pair ([launcher => 'jperl_sha256'], [jar => 'jar_sha256'],
            [sbom => 'sbom_sha256'], [baseline => 'baseline_sha256']) {
        die "Regex producer $pair->[0] identity is stale\n"
            unless ref($producer_identity->{$pair->[0]}) eq 'HASH'
                && ($producer_identity->{$pair->[0]}{sha256} // '')
                    eq $identity->{$pair->[1]};
        reject_extra_keys($producer_identity->{$pair->[0]},
            "regex producer $pair->[0] identity", qw(path sha256));
        die "Regex producer $pair->[0] path is missing\n"
            unless defined($producer_identity->{$pair->[0]}{path})
                && !ref($producer_identity->{$pair->[0]}{path})
                && length($producer_identity->{$pair->[0]}{path});
    }
    my $runner_policy = $producer_identity->{runner_policy};
    die "Regex producer runner policy is missing\n"
        unless ref($runner_policy) eq 'HASH';
    reject_extra_keys($runner_policy, 'regex producer runner policy', qw(timeout
        jobs cpu_heavy_jobs));
    die "Regex producer runner policy is malformed\n"
        unless positive_count($runner_policy->{timeout})
            && positive_count($runner_policy->{jobs})
            && positive_count($runner_policy->{cpu_heavy_jobs})
            && $runner_policy->{cpu_heavy_jobs} <= 3;
    my $statuses = $document->{exit_statuses};
    die "Regex producer exit statuses are missing\n" unless ref($statuses) eq 'HASH';
    die "Regex producer has incomplete or non-pass commands\n"
        if !keys(%$statuses) || grep { !count_number($statuses->{$_}) || $statuses->{$_} != 0 }
            keys %$statuses;
    reject_completion_flags($document, 'regex producer');
    die "Regex producer baseline or artifact-directory binding is stale\n"
        unless ($document->{baseline} // '')
                eq $producer_identity->{baseline}{path}
            && defined($document->{artifact_directory})
            && !ref($document->{artifact_directory})
            && abs_path($document->{artifact_directory})
                eq abs_path(dirname($selection->{artifact}{absolute}));
    die "Regex producer verified runner identity is stale\n"
        unless ($document->{verified_runner_sha} // '')
            eq $identity->{runner_commit};
    my $artifacts = validate_artifact_map($document->{artifacts}, $root,
        'regex producer');
    for my $name (qw(regex-ledger.json jvm-results.json interpreter-results.json
            jvm-comparison.json interpreter-comparison.json jperl-version.log
            strict-regex-ledger.json jvm-strict-regex-comparison.json
            interpreter-strict-regex-comparison.json strict-regex-files.txt
            jvm-strict-regex-comparison.log
            interpreter-strict-regex-comparison.log raw-tap-index.json)) {
        die "Regex producer artifact is missing: $name\n" unless $artifacts->{$name};
    }
    my $version_log = $artifacts->{'jperl-version.log'}{bytes};
    my @reported_sha = $version_log =~ /\b([0-9a-f]{7,40})\b/ig;
    die "Regex producer jperl-version log does not identify the trusted runner\n"
        unless grep { index($identity->{runner_commit}, lc($_)) == 0 }
            @reported_sha;
    my $ledger = decode_json_bytes($artifacts->{'regex-ledger.json'}{bytes},
        'regex ledger', $artifacts->{'regex-ledger.json'}{absolute});
    reject_extra_keys($ledger, 'regex ledger', qw(schema_version policy scope
        summary core_re_files auxiliary_regex_files runner_files
        documented_unit_gates direct_thread_pairs thread_only_tests
        unresolved_references));
    my $strict_ledger = decode_json_bytes(
        $artifacts->{'strict-regex-ledger.json'}{bytes}, 'strict regex ledger',
        $artifacts->{'strict-regex-ledger.json'}{absolute});
    die "Regex producer strict ledger is missing or unresolved\n"
        unless ref($strict_ledger->{summary}) eq 'HASH'
            && count_number($strict_ledger->{summary}{unresolved_references})
            && $strict_ledger->{summary}{unresolved_references} == 0
            && ref($document->{strict_regex_ledger_summary}) eq 'HASH'
            && canonical($document->{strict_regex_ledger_summary})
                eq canonical($strict_ledger->{summary})
            && count_number($document->{strict_regex_expected_files})
            && $document->{strict_regex_expected_files} > 0;
    for my $field (qw(core_re_files documented_unit_gates direct_thread_pairs
            thread_only_tests)) {
        die "Strict regex ledger inventory is missing: $field\n"
            unless ref($strict_ledger->{$field}) eq 'ARRAY';
    }
    my %strict_file = map { $_ => 1 } (
        @{$strict_ledger->{core_re_files}},
        @{$strict_ledger->{documented_unit_gates}},
        @{$strict_ledger->{thread_only_tests}},
    );
    for my $pair (@{$strict_ledger->{direct_thread_pairs}}) {
        die "Strict regex ledger contains a malformed direct/thread pair\n"
            unless ref($pair) eq 'HASH'
                && defined($pair->{direct}) && !ref($pair->{direct})
                && defined($pair->{thread}) && !ref($pair->{thread});
        $strict_file{$pair->{direct}} = 1;
        $strict_file{$pair->{thread}} = 1;
    }
    my @strict_files = sort keys %strict_file;
    die "Strict regex semantic inventory count is stale\n"
        unless @strict_files == $document->{strict_regex_expected_files};
    my $retained_strict_files = parse_canonical_file_list(
        $artifacts->{'strict-regex-files.txt'}{bytes}, 'strict regex file list');
    die "Strict regex retained list differs from ledger inventory\n"
        unless canonical($retained_strict_files) eq canonical(\@strict_files);
    my $strict_files_sha256 = Digest::SHA::sha256_hex(
        join('', map { "$_\n" } @strict_files));
    my $runner_files = $document->{expected_files};
    die "Regex producer discovered file count is missing or zero\n"
        unless count_number($runner_files) && $runner_files > 0;
    die "Regex ledger summary is incomplete\n"
        unless ref($ledger->{summary}) eq 'HASH';
    reject_extra_keys($ledger->{summary}, 'regex ledger summary', qw(core_re_files
        auxiliary_regex_files runner_files documented_unit_gates
        direct_thread_pairs thread_only_tests unresolved_references));
    for my $field (qw(core_re_files auxiliary_regex_files runner_files
            documented_unit_gates direct_thread_pairs thread_only_tests
            unresolved_references)) {
        die "Regex ledger summary is incomplete: $field\n"
            unless count_number($ledger->{summary}{$field});
    }
    die "Regex producer ledger summary differs from retained ledger\n"
        unless ref($document->{ledger_summary}) eq 'HASH'
            && canonical($document->{ledger_summary}) eq canonical($ledger->{summary});
    for my $field (qw(core_re_files auxiliary_regex_files runner_files
            documented_unit_gates direct_thread_pairs thread_only_tests
            unresolved_references)) {
        die "Regex ledger $field inventory is missing or inconsistent\n"
            unless ref($ledger->{$field}) eq 'ARRAY'
                && @{$ledger->{$field}} == $ledger->{summary}{$field};
    }
    die "Regex ledger has unresolved references\n"
        if @{$ledger->{unresolved_references}};
    die "Regex ledger discovered count differs from producer\n"
        unless count_number($ledger->{summary}{runner_files})
            && $ledger->{summary}{runner_files} == $runner_files;
    my $pairs = $ledger->{direct_thread_pairs};
    my $thread = $ledger->{thread_only_tests};
    die "Regex ledger pair inventory is missing\n"
        unless ref($pairs) eq 'ARRAY' && @$pairs;
    die "Regex ledger thread-only inventory is missing\n"
        unless ref($thread) eq 'ARRAY';
    my %result = (ledger => {
        scope => 'complete', runner_files => 0 + $runner_files,
        direct_thread_pairs => 0 + @$pairs, thread_only_tests => 0 + @$thread,
        unresolved_references => 0 + $ledger->{summary}{unresolved_references},
        missing_files => 0,
    });
    my %backend_results;
    my %ledger_runner_file = map { $_ => 1 } @{$ledger->{runner_files}};
    die "Regex ledger runner inventory has duplicates or unsafe identities\n"
        unless keys(%ledger_runner_file) == @{$ledger->{runner_files}}
            && !grep { !safe_relative_test_path($_) } keys %ledger_runner_file;
    my $raw_tap = validate_raw_tap_index(
        $artifacts->{'raw-tap-index.json'}{bytes}, $root, \%ledger_runner_file);
    for my $backend (qw(jvm interpreter)) {
        my $runner = decode_json_bytes(
            $artifacts->{"$backend-results.json"}{bytes},
            "$backend runner results",
            $artifacts->{"$backend-results.json"}{absolute});
        reject_extra_keys($runner, "$backend runner results", qw(timestamp
            jperl_path summary feature_impact results));
        die "$backend runner used the wrong executable\n"
            unless ($runner->{jperl_path} // '')
                eq $producer_identity->{launcher}{path};
        die "$backend runner result set is missing\n"
            unless ref($runner->{results}) eq 'HASH';
        $backend_results{$backend} = $runner->{results};
        die "$backend runner discovered count is stale\n"
            unless keys(%{$runner->{results}}) == $runner_files;
        for my $file (keys %{$runner->{results}}) {
            my $row = $runner->{results}{$file};
            die "$backend runner row is malformed: $file\n"
                unless ref($row) eq 'HASH';
            reject_extra_keys($row, "$backend runner row $file", qw(status
                ok_count not_ok_count total_tests planned_tests actual_tests_run
                incomplete_tests skip_count todo_count errors missing_features
                exit_code raw_output_path failure_output timeout truncated
                execution_error duration file));
            die "$backend runner row file identity differs: $file\n"
                unless safe_relative_test_path($file)
                    && defined($row->{file}) && !ref($row->{file})
                    && $row->{file} eq $file;
            die "$backend runner row duration is malformed: $file\n"
                unless defined($row->{duration}) && !ref($row->{duration})
                    && "$row->{duration}" =~ /\A(?:0|[1-9]\d*)(?:\.\d+)?\z/
                    && decimal_digit_count("$row->{duration}") <= $MAX_DECIMAL_DIGITS
                    && 0 + $row->{duration} <= $runner_policy->{timeout};
            die "$backend runner row arrays are malformed: $file\n"
                unless ref($row->{errors}) eq 'ARRAY'
                    && ref($row->{missing_features}) eq 'ARRAY';
        }
        my $comparison = decode_json_bytes(
            $artifacts->{"$backend-comparison.json"}{bytes},
            "$backend comparison",
            $artifacts->{"$backend-comparison.json"}{absolute});
        reject_extra_keys($comparison, "$backend comparison", qw(expected_files
            summary regressions improvements plan_changes missing_files
            added_files execution_issues zero_tap truncated new_invalid
            inherited_invalid));
        my $summary = $comparison->{summary};
        die "$backend comparison summary is missing\n" unless ref($summary) eq 'HASH';
        reject_extra_keys($summary, "$backend comparison summary", qw(baseline_ok
            candidate_ok delta_ok baseline_total candidate_total delta_total
            baseline_files candidate_files));
        my $candidate_files = $summary->{candidate_files};
        die "$backend comparison uses a stale discovered count\n"
            unless count_number($candidate_files) && $candidate_files == $runner_files
                && count_number($comparison->{expected_files})
                && $comparison->{expected_files} == $runner_files;
        for my $field (qw(regressions improvements plan_changes missing_files
                added_files zero_tap truncated execution_issues new_invalid
                inherited_invalid)) {
            die "$backend comparison $field is missing\n"
                unless ref($comparison->{$field}) eq 'ARRAY';
        }
        die "$backend comparison did not pass\n"
            if grep { @{$comparison->{$_}} }
                qw(regressions missing_files zero_tap truncated execution_issues new_invalid);
        my %inherited;
        for my $entry (@{$comparison->{inherited_invalid}}) {
            die "$backend inherited-invalid entry is malformed\n"
                unless ref($entry) eq 'HASH' && safe_relative_test_path($entry->{file});
            my $classified = $entry->{file};
            die "$backend inherited-invalid entry is duplicated: $classified\n"
                if $inherited{$classified}++;
        }
        my %expected_inherited;
        for my $file (keys %{$runner->{results}}) {
            my $green = regex_runner_row_is_green($runner->{results}{$file});
            if ($strict_file{$file}) {
            } elsif (!$green) {
                $expected_inherited{$file} = 1;
                die "$backend broad invalid row is not classified as inherited: $file\n"
                    unless $inherited{$file};
            }
        }
        die "$backend inherited-invalid set has stale, green, or strict entries\n"
            unless canonical([sort keys %inherited]) eq
                canonical([sort keys %expected_inherited]);
        my $strict_comparison = decode_json_bytes(
            $artifacts->{"$backend-strict-regex-comparison.json"}{bytes},
            "$backend strict regex comparison",
            $artifacts->{"$backend-strict-regex-comparison.json"}{absolute});
        validate_strict_regex_comparison($strict_comparison, $backend,
            \@strict_files, $strict_files_sha256,
            $artifacts->{"$backend-strict-regex-comparison.log"}{bytes});
        $result{$backend} = {
            expected_files => 0 + $runner_files,
            candidate_files => 0 + $candidate_files,
            regressions => 0, missing_files => 0, zero_tap => 0,
            timeouts => 0, truncated => 0, execution_issues => 0,
            wrong_executable => 0, wrong_commit => 0,
        };
    }
    for my $backend (qw(jvm interpreter)) {
        for my $file (@strict_files) {
            next unless ref($backend_results{$backend}{$file}) eq 'HASH';
            die "$backend strict runner row is incomplete, timed out, or zero-TAP: $file\n"
                unless regex_runner_row_is_complete(
                    $backend_results{$backend}{$file});
        }
    }
    validate_strict_regex_backend_parity(\%backend_results, \@strict_files);
    for my $backend (qw(jvm interpreter)) {
        die "$backend runner set differs from retained ledger inventory\n"
            unless canonical([sort keys %{$backend_results{$backend}}]) eq
                canonical([sort keys %ledger_runner_file]);
        for my $file (sort keys %{$backend_results{$backend}}) {
            die "$backend runner raw TAP identity is stale: $file\n"
                unless ref($raw_tap->{$backend}{$file}) eq 'HASH'
                    && ($backend_results{$backend}{$file}{raw_output_path} // '') eq
                        $raw_tap->{$backend}{$file}{path};
        }
        for my $file (@strict_files) {
            my $row = $backend_results{$backend}{$file};
            die "$backend strict runner row is incomplete, timed out, or zero-TAP: $file\n"
                unless regex_runner_row_is_green($row);
            validate_runner_tap($raw_tap->{$backend}{$file}{bytes}, $row,
                "$backend raw TAP $file");
        }
    }
    $result{release_authority} = $document->{release_authority};
    return \%result;
}

sub validate_strict_regex_backend_parity {
    my ($results, $files) = @_;
    my @semantic_fields = qw(status ok_count not_ok_count total_tests
        planned_tests actual_tests_run incomplete_tests skip_count todo_count
        errors missing_features exit_code failure_output timeout truncated
        execution_error);
    for my $file (@$files) {
        my $jvm = $results->{jvm}{$file};
        my $interpreter = $results->{interpreter}{$file};
        die "Strict regex backend parity is missing JVM row: $file\n"
            unless ref($jvm) eq 'HASH';
        die "Strict regex backend parity is missing interpreter row: $file\n"
            unless ref($interpreter) eq 'HASH';
        my %jvm_semantics = map { $_ => $jvm->{$_} } @semantic_fields;
        my %interpreter_semantics = map { $_ => $interpreter->{$_} }
            @semantic_fields;
        die "Strict regex backend parity differs: $file\n"
            unless canonical(\%jvm_semantics) eq
                canonical(\%interpreter_semantics);
    }
}

sub regex_runner_row_is_complete {
    my ($row) = @_;
    return ref($row) eq 'HASH'
        && !scalar(grep { !count_number($row->{$_}) }
            qw(total_tests exit_code planned_tests actual_tests_run ok_count
                not_ok_count incomplete_tests skip_count todo_count))
        && ref($row->{errors}) eq 'ARRAY'
        && ref($row->{missing_features}) eq 'ARRAY'
        && (true_value($row->{timeout}) || false_value($row->{timeout}))
        && (true_value($row->{truncated}) || false_value($row->{truncated}))
        && (true_value($row->{execution_error})
            || false_value($row->{execution_error}));
}

sub regex_runner_row_is_green {
    my ($row) = @_;
    return ($row->{status} // '') eq 'pass'
        && count_number($row->{total_tests}) && $row->{total_tests} > 0
        && count_number($row->{exit_code}) && $row->{exit_code} == 0
        && count_number($row->{planned_tests}) && $row->{planned_tests} > 0
        && count_number($row->{actual_tests_run})
        && $row->{actual_tests_run} == $row->{planned_tests}
        && !scalar(grep { !count_number($row->{$_}) }
            qw(ok_count not_ok_count skip_count todo_count))
        && $row->{not_ok_count} == 0
        && $row->{ok_count} + $row->{not_ok_count} == $row->{total_tests}
        && $row->{total_tests} == $row->{planned_tests}
        && count_number($row->{incomplete_tests})
        && $row->{incomplete_tests} == 0
        && ref($row->{errors}) eq 'ARRAY'
        && !@{$row->{errors}}
        && ref($row->{missing_features}) eq 'ARRAY'
        && !@{$row->{missing_features}}
        && (!exists($row->{timeout}) || false_value($row->{timeout}))
        && (!exists($row->{truncated}) || false_value($row->{truncated}))
        && (!exists($row->{execution_error})
            || false_value($row->{execution_error}));
}

sub validate_strict_regex_comparison {
    my ($comparison, $backend, $expected_files, $expected_sha, $log) = @_;
    reject_extra_keys($comparison, "$backend strict regex comparison", qw(
        expected_files summary regressions improvements plan_changes missing_files
        added_files execution_issues zero_tap truncated new_invalid
        inherited_invalid compared_files compared_files_sha256 baseline candidate));
    my $summary = $comparison->{summary};
    die "$backend strict regex comparison summary is missing\n"
        unless ref($summary) eq 'HASH';
    reject_extra_keys($summary, "$backend strict regex comparison summary", qw(
        baseline_ok candidate_ok delta_ok baseline_total candidate_total
        delta_total baseline_files candidate_files));
    die "$backend strict regex comparison file count is stale\n"
        unless count_number($comparison->{expected_files})
            && $comparison->{expected_files} == @$expected_files
            && count_number($summary->{candidate_files})
            && $summary->{candidate_files} == @$expected_files;
    die "$backend strict regex comparison exact file identity is stale\n"
        unless ref($comparison->{compared_files}) eq 'ARRAY'
            && canonical($comparison->{compared_files}) eq canonical($expected_files)
            && ($comparison->{compared_files_sha256} // '') eq $expected_sha;
    die "$backend strict regex comparison log identity is stale\n"
        unless defined($log)
            && $log =~ /^Compared file identity: files=\Q@{[scalar @$expected_files]}\E sha256=\Q$expected_sha\E$/m;
    for my $field (qw(regressions improvements plan_changes missing_files
            added_files execution_issues zero_tap truncated new_invalid
            inherited_invalid)) {
        die "$backend strict regex comparison $field is missing\n"
            unless ref($comparison->{$field}) eq 'ARRAY';
    }
    die "$backend strict regex comparison did not pass\n"
        if grep { @{$comparison->{$_}} } qw(regressions missing_files
            execution_issues zero_tap truncated new_invalid inherited_invalid);
}

sub parse_canonical_file_list {
    my ($bytes, $label) = @_;
    my @file;
    for my $line (split /\n/, $bytes, -1) {
        $line =~ s/\r\z//;
        next if $line eq '';
        die "$label contains comments or whitespace\n"
            if $line =~ /^\s*#/ || $line =~ /^\s|\s\z/;
        die "$label contains unsafe path: $line\n"
            unless safe_relative_test_path($line);
        push @file, $line;
    }
    die "$label is empty\n" unless @file;
    my %seen;
    die "$label contains duplicate paths\n" if grep { $seen{$_}++ } @file;
    die "$label is not sorted\n"
        unless canonical(\@file) eq canonical([sort @file]);
    return \@file;
}

sub safe_relative_test_path {
    my ($path) = @_;
    return 0 unless defined($path) && !ref($path) && length($path);
    return 0 if $path =~ /[\\\0]/ || File::Spec->file_name_is_absolute($path)
        || $path =~ /\A[A-Za-z]:/ || $path =~ m{//};
    my @part = split m{/}, $path, -1;
    return 0 if grep { $_ eq '' || $_ eq '.' || $_ eq '..' } @part;
    return join('/', @part) eq $path;
}

sub decimal_digit_count {
    my ($value) = @_;
    (my $digits = "$value") =~ s/[^0-9]//g;
    return length($digits);
}

sub validate_raw_tap_index {
    my ($bytes, $root, $expected_files) = @_;
    my $index = decode_json_bytes($bytes, 'regex raw TAP index');
    reject_extra_keys($index, 'regex raw TAP index', qw(schema_version kind
        mapping aggregate_bytes entries));
    die "Regex raw TAP index contract is wrong\n"
        unless ($index->{schema_version} // 0) == 1
            && ($index->{kind} // '') eq 'phase36-regex-raw-tap-index'
            && ($index->{mapping} // '') eq
                'sha256-backend-nul-normalized-relative-file/v1'
            && whole_number($index->{aggregate_bytes})
            && ref($index->{entries}) eq 'ARRAY';
    my (%result, %retained_path);
    my $aggregate = 0;
    for my $entry (@{$index->{entries}}) {
        die "Regex raw TAP index entry is malformed\n" unless ref($entry) eq 'HASH';
        reject_extra_keys($entry, 'regex raw TAP index entry', qw(backend file
            path size sha256));
        my $backend = $entry->{backend} // '';
        my $file = $entry->{file};
        die "Regex raw TAP index identity is unsafe\n"
            unless $backend =~ /\A(?:jvm|interpreter)\z/
                && safe_relative_test_path($file) && $expected_files->{$file};
        my $expected_path = join('/', 'raw-tap', $backend,
            Digest::SHA::sha256_hex("$backend\0$file") . '.tap');
        die "Regex raw TAP retained mapping is stale\n"
            unless ($entry->{path} // '') eq $expected_path;
        die "Regex raw TAP index has a duplicate row or path\n"
            if $result{$backend}{$file} || $retained_path{$expected_path}++;
        my $validated = validate_descriptor({path => $expected_path,
                sha256 => $entry->{sha256}}, $root,
            "regex raw TAP $backend $file", 0, $MAX_JSON_BYTES);
        die "Regex raw TAP size is stale\n"
            unless size_number($entry->{size})
                && $entry->{size} == -s $validated->{absolute};
        $aggregate += $entry->{size};
        $result{$backend}{$file} = { %$entry, bytes => $validated->{bytes} };
    }
    die "Regex raw TAP aggregate size is stale\n"
        unless $aggregate == $index->{aggregate_bytes};
    for my $backend (qw(jvm interpreter)) {
        die "Regex raw TAP index membership differs for $backend\n"
            unless canonical([sort keys %{$result{$backend} // {}}]) eq
                canonical([sort keys %$expected_files]);
    }
    return \%result;
}

sub validate_runner_tap {
    my ($tap, $row, $label) = @_;
    my @plan = $tap =~ /^\s*1\.\.(\d+)\b/mg;
    my @ok = $tap =~ /^\s*ok\b/mg;
    my @not_ok = $tap =~ /^\s*not ok\b/mg;
    die "$label has missing or duplicate TAP plan\n" unless @plan == 1;
    die "$label contains a bailout\n" if $tap =~ /^\s*Bail out!/mi;
    die "$label differs from runner row counts\n"
        unless $plan[0] == $row->{planned_tests}
            && @ok == $row->{ok_count}
            && @not_ok == $row->{not_ok_count}
            && @ok + @not_ok == $row->{actual_tests_run};
}

sub validate_regex_release_authority {
    my ($authority, $identity, $selection, $root) = @_;
    die "Regex release authority is missing\n" unless ref($authority) eq 'HASH';
    reject_extra_keys($authority, 'regex release authority', qw(authoritative
        kind make_evidence mode package_evidence schema_version selected));
    die "Regex release authority is not authoritative acceptance evidence\n"
        unless ($authority->{schema_version} // 0) == 1
            && ($authority->{kind} // '') eq 'phase36-release-authority'
            && ($authority->{mode} // '') eq 'acceptance'
            && true_value($authority->{authoritative});
    my $package = $authority->{package_evidence};
    die "Regex package release authority is missing\n"
        unless ref($package) eq 'HASH';
    reject_extra_keys($package, 'regex package release authority', qw(path
        sha256 identity));
    my $package_path = abs_path($package->{path} // '');
    die "Regex package release authority selected different evidence\n"
        unless $package_path
            && $package_path eq $selection->{packaging}{artifact}{absolute}
            && ($package->{sha256} // '') eq
                $selection->{packaging}{artifact}{sha256};
    my $package_identity = $package->{identity};
    die "Regex package release identity is missing\n"
        unless ref($package_identity) eq 'HASH';
    reject_extra_keys($package_identity, 'regex package release identity', qw(
        source_commit jar_sha256 sbom_sha256));
    validate_lane_identity($package_identity, $identity,
        qw(source_commit jar_sha256 sbom_sha256));

    my $make = $authority->{make_evidence};
    die "Regex make release authority is missing\n" unless ref($make) eq 'HASH';
    reject_extra_keys($make, 'regex make release authority', qw(path sha256
        seal identity));
    my $make_path = abs_path($make->{path} // '');
    die "Regex make release authority selected different evidence\n"
        unless $make_path && $make_path eq $selection->{make}{artifact}{absolute}
            && ($make->{sha256} // '') eq $selection->{make}{artifact}{sha256};
    my $make_identity = $make->{identity};
    die "Regex make release identity is missing\n"
        unless ref($make_identity) eq 'HASH';
    reject_extra_keys($make_identity, 'regex make release identity', qw(
        source_commit runner_commit jar_sha256 jar_reported_commit
        jar_embedded_commit));
    validate_lane_identity($make_identity, $identity,
        qw(source_commit runner_commit jar_sha256));
    for my $field (qw(jar_reported_commit jar_embedded_commit)) {
        die "Regex make release $field differs from selected source\n"
            unless ($make_identity->{$field} // '') eq $identity->{source_commit};
    }
    my $seal_descriptor = $make->{seal};
    die "Regex make release seal is missing\n"
        unless ref($seal_descriptor) eq 'HASH';
    reject_extra_keys($seal_descriptor, 'regex make release seal', qw(path sha256));
    my $expected_seal_path = "$make_path.seal";
    my $seal_path = abs_path($seal_descriptor->{path} // '');
    die "Regex make release seal path differs from selected make evidence\n"
        unless $seal_path && $seal_path eq $expected_seal_path;
    my $validated_seal = validate_descriptor($seal_descriptor,
        dirname($make_path), 'regex make release seal', 1, 512, 0);
    my $make_document = $selection->{make}{document};
    my $expected_seal = "SHA-256 "
        . ($make_document->{seal}{payload_sha256} // '') . " "
        . $selection->{make}{artifact}{sha256} . "\n";
    die "Regex make release seal content is invalid\n"
        unless ($validated_seal->{bytes} // '') eq $expected_seal;

    my $selected = $authority->{selected};
    die "Regex selected release tuple is missing\n"
        unless ref($selected) eq 'HASH';
    reject_extra_keys($selected, 'regex selected release tuple', qw(source_root
        source_commit runner_commit jar sbom baseline));
    my $selected_source_root = abs_path($selected->{source_root} // '');
    my $make_source_root = abs_path(
        $selection->{make}{document}{source}{root} // '');
    die "Regex selected source root differs from make authority\n"
        unless $selected_source_root && -d $selected_source_root
            && !-l $selected->{source_root}
            && $selected->{source_root} eq $selected_source_root
            && $make_source_root && $selected_source_root eq $make_source_root;
    my $make_cwd = abs_path($selection->{make}{document}{command}{cwd} // '');
    die "Regex selected source root differs from make command cwd\n"
        unless $make_cwd
            && !-l $selection->{make}{document}{command}{cwd}
            && $selection->{make}{document}{command}{cwd} eq $make_cwd
            && $make_cwd eq $selected_source_root;
    for my $field (qw(source_commit runner_commit)) {
        die "Regex selected release $field is stale\n"
            unless ($selected->{$field} // '') eq $identity->{$field};
    }
    for my $pair ([jar => 'jar_sha256'], [sbom => 'sbom_sha256'],
            [baseline => 'baseline_sha256']) {
        my ($name, $field) = @$pair;
        my $descriptor = $selected->{$name};
        die "Regex selected release $name is missing or stale\n"
            unless ref($descriptor) eq 'HASH'
                && ($descriptor->{sha256} // '') eq $identity->{$field};
        reject_extra_keys($descriptor, "regex selected release $name",
            qw(path sha256));
        die "Regex selected release $name path is missing\n"
            unless defined($descriptor->{path}) && !ref($descriptor->{path})
                && length($descriptor->{path});
    }
    my $regex_document = $selection->{ledger}{document};
    my %expected_path = (
        jar => $regex_document->{identity}{jar}{path},
        sbom => $regex_document->{identity}{sbom}{path},
        baseline => $regex_document->{identity}{baseline}{path},
    );
    for my $name (sort keys %expected_path) {
        my $selected_path = abs_path($selected->{$name}{path} // '');
        my $identity_path = abs_path($expected_path{$name} // '');
        die "Regex selected release $name path differs from producer identity\n"
            unless $selected_path && -f $selected_path && !-l $selected->{$name}{path}
                && $identity_path && $selected_path eq $identity_path;
        validate_descriptor($selected->{$name}, $root,
            "regex selected release $name bytes", 1,
            $name eq 'jar' ? $MAX_BLOB_BYTES : $MAX_JSON_BYTES, 1);
    }
    validate_descriptor($regex_document->{identity}{launcher}, $root,
        'regex selected launcher bytes', 1, $MAX_JSON_BYTES, 1);
    my $producer_baseline = abs_path($regex_document->{baseline} // '');
    my $selected_baseline = abs_path($selected->{baseline}{path} // '');
    die "Regex selected baseline path differs from producer baseline\n"
        unless $producer_baseline && $selected_baseline
            && $producer_baseline eq $selected_baseline;
    my $make_jar = abs_path(
        $selection->{make}{document}{artifacts}{jar}{path} // '');
    my $selected_jar = abs_path($selected->{jar}{path} // '');
    die "Regex selected JAR path differs from make authority\n"
        unless $make_jar && $selected_jar && $make_jar eq $selected_jar;
    my $deliverables = $selection->{packaging}{document}{artifacts}{deliverables};
    die "Regex release package deliverables are incomplete\n"
        unless ref($deliverables) eq 'HASH'
            && ref($deliverables->{jar}) eq 'HASH'
            && ($deliverables->{jar}{sha256} // '') eq $identity->{jar_sha256}
            && ref($deliverables->{sbom}) eq 'HASH'
            && ($deliverables->{sbom}{sha256} // '') eq $identity->{sbom_sha256};
    for my $name (qw(jar sbom)) {
        my $package_declared = $deliverables->{$name}{path} // '';
        my $package_candidate = File::Spec->file_name_is_absolute($package_declared)
            ? $package_declared
            : File::Spec->catfile(dirname(
                $selection->{packaging}{artifact}{absolute}),
                File::Spec->splitdir($package_declared));
        my $package_path = abs_path($package_candidate);
        my $selected_path = abs_path($selected->{$name}{path} // '');
        die "Regex selected $name path differs from package deliverable\n"
            unless $package_path && $selected_path
                && !-l $package_candidate
                && $package_path eq $selected_path;
    }
}

sub validate_direct_thread {
    my ($document, $identity) = @_;
    reject_extra_keys($document, 'direct/thread producer', qw(schema_version kind
        verified identity observations details failures status timeout incomplete
        review_stop));
    die "Direct/thread schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'direct-thread';
    die "Direct/thread producer did not verify\n" unless true_value($document->{verified});
    validate_lane_identity($document->{identity}, $identity,
        qw(source_commit runner_commit jperl_sha256));
    reject_completion_flags($document, 'direct/thread producer');
    my $details = $document->{details};
    die "Direct/thread details are missing\n" unless ref($details) eq 'HASH';
    reject_extra_keys($details, 'direct/thread details', qw(expected_pairs
        actual_pairs expected_modes actual_modes expected_thread_only
        actual_thread_only expected_thread_only_modes actual_thread_only_modes
        mismatches missing zero_tap timeouts truncated execution_issues
        assertion_status_mismatches description_differences
        classified_shared_failures unclassified_shared_failures
        standalone_failures unused_allowlist status_counts rows
        supplemental_core_artifacts));
    for my $field (qw(expected_pairs actual_pairs expected_modes actual_modes
            expected_thread_only actual_thread_only expected_thread_only_modes
            actual_thread_only_modes mismatches missing zero_tap timeouts
            truncated execution_issues assertion_status_mismatches
            description_differences classified_shared_failures
            unclassified_shared_failures standalone_failures unused_allowlist)) {
        die "Direct/thread $field is missing\n" unless count_number($details->{$field});
    }
    die "Direct/thread retained rows/status metadata is missing\n"
        unless ref($details->{status_counts}) eq 'HASH'
            && ref($details->{rows}) eq 'ARRAY'
            && ref($details->{supplemental_core_artifacts}) eq 'ARRAY';
    my $failures = $document->{failures};
    die "Direct/thread failure evidence is missing\n" unless ref($failures) eq 'HASH';
    my @failure_fields = qw(missing mismatches zero_tap timeouts truncated
        execution_issues classified_shared_failures unclassified_shared_failures
        standalone_failures unused_allowlist);
    reject_extra_keys($failures, 'direct/thread failure evidence', @failure_fields);
    for my $field (@failure_fields) {
        die "Direct/thread failure evidence $field is missing\n"
            unless ref($failures->{$field}) eq 'ARRAY';
    }
    die "Direct/thread failure arrays contradict passing counts\n"
        if grep { @{$failures->{$_}} }
            qw(missing mismatches zero_tap timeouts truncated execution_issues
                unclassified_shared_failures standalone_failures unused_allowlist);
    my $observations = $document->{observations};
    die "Direct/thread observations are missing\n" unless ref($observations) eq 'HASH';
    reject_extra_keys($observations, 'direct/thread observations',
        'description_differences');
    die "Direct/thread description-difference evidence is inconsistent\n"
        unless ref($observations->{description_differences}) eq 'ARRAY'
            && @{$observations->{description_differences}}
                == $details->{description_differences};
    die "Direct/thread producer reports incomplete or failing evidence\n"
        if $details->{expected_pairs} <= 0
            || $details->{actual_pairs} != $details->{expected_pairs}
            || $details->{expected_modes} != 4 || $details->{actual_modes} != 4
            || $details->{actual_thread_only} != $details->{expected_thread_only}
            || $details->{expected_thread_only_modes} != 2
            || $details->{actual_thread_only_modes} != 2
            || grep { $details->{$_} != 0 }
                qw(mismatches missing zero_tap timeouts truncated execution_issues
                    unclassified_shared_failures standalone_failures unused_allowlist);
    return { map { $_ => 0 + $details->{$_} } qw(expected_pairs actual_pairs
        expected_modes actual_modes expected_thread_only actual_thread_only
        expected_thread_only_modes actual_thread_only_modes mismatches missing
        zero_tap timeouts truncated execution_issues) };
}

sub validate_cpan {
    my ($document, $identity, $requirements, $path, $artifact_sha) = @_;
    reject_extra_keys($document, 'CPAN producer', qw(schema_version mode status
        expected_targets results total_tests excluded_audits identity artifacts
        authority timeout incomplete review_stop));
    die "CPAN producer artifact must be cpan-acceptance.json\n"
        unless basename($path) eq 'cpan-acceptance.json';
    die "CPAN producer schema_version must be current version 2\n"
        unless ($document->{schema_version} // 0) == 2;
    die "CPAN producer is not a passing acceptance run\n"
        unless ($document->{mode} // '') eq 'acceptance'
            && ($document->{status} // '') eq 'pass';
    validate_lane_identity($document->{identity}, $identity,
        qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256
            sbom_sha256));
    my $cpan_identity = $document->{identity};
    reject_extra_keys($cpan_identity, 'CPAN producer identity', qw(source_commit
        runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256
        policy_sha256 manifest_sha256 jcpan_sha256 inputs
        execution_authorized
        authority_tuple_sha256 authority_marker_sha256 authority_bridge_sha256
        authority_launch_sha256 authority_seal_sha256));
    die "CPAN policy identity is wrong\n"
        unless ($document->{identity}{policy_sha256} // '')
            eq ($requirements->{cpan_acceptance}{policy_sha256} // '');
    for my $field (qw(manifest_sha256 jcpan_sha256)) {
        die "CPAN $field is missing or malformed\n"
            unless ($cpan_identity->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    validate_cpan_inputs($cpan_identity->{inputs}, $identity,
        $cpan_identity->{jcpan_sha256});
    validate_cpan_execution_authority($document->{authority}, $cpan_identity);
    reject_completion_flags($document, 'CPAN producer');
    my @expected = @{$requirements->{cpan_acceptance}{expected_targets} // []};
    die "CPAN target set differs from policy\n"
        unless canonical([sort @{$document->{expected_targets} // []}])
            eq canonical([sort @expected]);
    my $results = $document->{results};
    die "CPAN results are missing\n" unless ref($results) eq 'HASH';
    my $artifact_root = dirname($path);
    my $retained = validate_cpan_artifacts($document->{artifacts},
        $artifact_root, \@expected,
        $requirements->{cpan_acceptance}{required_modes});
    my (%summary, $aggregate_total);
    for my $target (@expected) {
        my $result = $results->{$target};
        die "CPAN target $target is missing or did not pass\n"
            unless ref($result) eq 'HASH' && ($result->{status} // '') eq 'pass'
                && count_number($result->{total_tests}) && $result->{total_tests} > 0;
        reject_extra_keys($result, "CPAN target $target", qw(status total_tests
            timeout truncated execution_error rationale
            focused_selector_permitted modes));
        die "CPAN target $target reports incomplete execution\n"
            unless false_value($result->{timeout})
                && false_value($result->{truncated})
                && false_value($result->{execution_error});
        die "CPAN target $target policy metadata is incomplete\n"
            unless defined($result->{rationale}) && !ref($result->{rationale})
                && length($result->{rationale})
                && boolean_value($result->{focused_selector_permitted});
        my $modes = $result->{modes};
        die "CPAN target $target modes are missing\n" unless ref($modes) eq 'HASH';
        my $target_total = 0;
        for my $mode (@{$requirements->{cpan_acceptance}{required_modes}}) {
            my $entry = $modes->{$mode};
            die "CPAN target $target $mode did not pass\n"
                unless ref($entry) eq 'HASH' && ($entry->{status} // '') eq 'pass'
                    && count_number($entry->{total_tests}) && $entry->{total_tests} > 0
                    && count_number($entry->{exit_code}) && $entry->{exit_code} == 0
                    && count_number($entry->{signal}) && $entry->{signal} == 0
                    && false_value($entry->{timeout})
                    && false_value($entry->{execution_error})
                    && false_value($entry->{zero_tap})
                    && false_value($entry->{malformed})
                    && false_value($entry->{truncated});
            validate_lane_identity($entry->{identity}, $identity,
                qw(source_commit runner_commit perl5_commit jperl_sha256
                    jar_sha256 sbom_sha256));
            die "CPAN target $target $mode has failures or warnings\n"
                unless count_number($entry->{failures}) && $entry->{failures} == 0
                    && ref($entry->{unapproved_warnings}) eq 'ARRAY'
                    && !@{$entry->{unapproved_warnings}}
                    && ref($entry->{warning_diagnostics}) eq 'ARRAY'
                    && !@{$entry->{warning_diagnostics}};
            validate_cpan_mode($entry, $target, $mode, $identity,
                $cpan_identity->{inputs}, $artifact_root, $retained);
            $target_total += $entry->{total_tests};
        }
        die "CPAN target $target aggregate TAP total is wrong\n"
            unless $result->{total_tests} == $target_total;
        $aggregate_total += $target_total;
        $summary{$target} = {
            status => 'pass', total_tests => 0 + $result->{total_tests},
            modes => { map { $_ => { status => 'pass' } }
                @{$requirements->{cpan_acceptance}{required_modes}} },
        };
    }
    die "CPAN producer has excluded audits or extra targets\n"
        unless ref($document->{excluded_audits}) eq 'ARRAY'
            && !@{$document->{excluded_audits}}
            && canonical([sort keys %$results]) eq canonical([sort @expected]);
    die "CPAN aggregate TAP total is missing or wrong\n"
        unless count_number($document->{total_tests})
            && $document->{total_tests} == $aggregate_total;
    my $seal = "$path.sha256";
    die "CPAN producer seal is missing\n" unless -f $seal && -s $seal;
    my $seal_text = read_bounded($seal, 1024, 'CPAN seal');
    die "CPAN producer seal is malformed or stale\n"
        unless $seal_text =~ /\A([0-9a-f]{64})\s+cpan-acceptance\.json\s*\z/
            && $1 eq $artifact_sha;
    return { expected_targets => [@expected], results => \%summary,
        excluded_audits => [] };
}

sub validate_cpan_execution_authority {
    my ($authority, $identity) = @_;
    die "CPAN execution authority is missing\n" unless ref($authority) eq 'HASH';
    reject_extra_keys($authority, 'CPAN execution authority', qw(schema
        execution_authorized tuple_sha256 marker_sha256 bridge_sha256
        launch_sha256 seal_sha256));
    die "CPAN execution authority schema is wrong\n"
        unless ($authority->{schema} // '') eq
            'perlonjava.phase36.cpan-launch-authority/v1';
    die "CPAN execution authority is not an exact JSON boolean\n"
        unless JSON::PP::is_bool($authority->{execution_authorized});
    die "CPAN execution identity is not an exact JSON boolean\n"
        unless JSON::PP::is_bool($identity->{execution_authorized});
    die "CPAN execution authorization differs between authority and identity\n"
        unless ($authority->{execution_authorized} ? 1 : 0)
            == ($identity->{execution_authorized} ? 1 : 0);
    die "CPAN execution is not authorized\n"
        unless $authority->{execution_authorized};
    my %binding = (
        tuple_sha256 => 'authority_tuple_sha256',
        marker_sha256 => 'authority_marker_sha256',
        bridge_sha256 => 'authority_bridge_sha256',
        launch_sha256 => 'authority_launch_sha256',
        seal_sha256 => 'authority_seal_sha256',
    );
    for my $field (sort keys %binding) {
        die "CPAN execution authority $field is missing or malformed\n"
            unless ($authority->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
        die "CPAN marker/bridge authority tuple binding differs: $field\n"
            unless ($identity->{$binding{$field}} // '') eq $authority->{$field};
    }
}

sub validate_package {
    my ($selection, $identity) = @_;
    my $document = $selection->{document};
    reject_extra_keys($document, 'packaging producer', qw(schema_version kind
        producer verified identity completion artifacts missing_entries
        duplicate_entries));
    die "Packaging schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'packaging';
    die "Packaging producer label is wrong\n"
        unless ($document->{producer} // '') eq
            'run_phase36_package_evidence.pl';
    die "Packaging producer did not verify\n"
        unless true_value($document->{verified});
    my $package_identity = $document->{identity};
    die "Packaging identity is missing\n"
        unless ref($package_identity) eq 'HASH';
    reject_extra_keys($package_identity, 'packaging identity', qw(source_commit
        jar_sha256 sbom_sha256));
    validate_lane_identity($package_identity, $identity,
        qw(source_commit jar_sha256 sbom_sha256));
    validate_completion_record($document->{completion}, 'packaging');
    die "Packaging missing or duplicate entry counts are missing or nonzero\n"
        unless count_number($document->{missing_entries})
            && $document->{missing_entries} == 0
            && count_number($document->{duplicate_entries})
            && $document->{duplicate_entries} == 0;
    my $root = dirname($selection->{artifact}{absolute});
    die "Packaging producer has no structured hashed artifacts\n"
        unless ref($document->{artifacts}) eq 'HASH'
            && keys %{$document->{artifacts}};
    reject_extra_keys($document->{artifacts}, 'packaging artifacts', qw(
        deliverables logs notice_license report sbom_inputs));
    for my $group ([deliverables => [qw(deb jar sbom)]],
            [sbom_inputs => [qw(java_bom perl_bom)]]) {
        my ($name, $keys) = @$group;
        die "Packaging $name is missing\n"
            unless ref($document->{artifacts}{$name}) eq 'HASH';
        reject_extra_keys($document->{artifacts}{$name},
            "packaging $name", @$keys);
    }
    die "Packaging retained JAR or SBOM identity is stale\n"
        unless ($document->{artifacts}{deliverables}{jar}{sha256} // '') eq
                $identity->{jar_sha256}
            && ($document->{artifacts}{deliverables}{sbom}{sha256} // '') eq
                $identity->{sbom_sha256};
    die "Packaging producer has no retained artifact descriptor\n"
        unless validate_nested_artifacts($document->{artifacts}, $root,
            'packaging producer artifacts');
    return $document;
}

sub validate_performance {
    my ($selection, $identity, $requirements) = @_;
    my $document = $selection->{document};
    reject_extra_keys($document, 'final performance producer', qw(schema_version
        kind identity ordinary psycho_speed ordered review_explanations authority
        policy_sha256 evaluation decision verified));
    die "Final performance schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'phase36-final-performance';
    die "Final performance did not pass or stopped for review\n"
        unless true_value($document->{verified})
            && ($document->{decision} // '') eq 'passed'
            && ref($document->{review_explanations}) eq 'ARRAY'
            && !@{$document->{review_explanations}};
    my $final_identity = $document->{identity};
    die "Final performance identity is missing\n"
        unless ref($final_identity) eq 'HASH';
    die "Final performance source or Perl5 identity is stale\n"
        unless ($final_identity->{candidate_source_commit} // '')
                eq $identity->{source_commit}
            && ($final_identity->{perl5_commit} // '') eq $identity->{perl5_commit};
    die "Final performance candidate JAR or launcher identity is stale\n"
        unless ref($final_identity->{candidate_jar}) eq 'HASH'
            && ($final_identity->{candidate_jar}{sha256} // '')
                eq $identity->{jar_sha256}
            && ref($final_identity->{candidate_launcher}) eq 'HASH'
            && ($final_identity->{candidate_launcher}{sha256} // '')
                eq $identity->{jperl_sha256};
    my $root = dirname($selection->{artifact}{absolute});
    die "Final performance ordinary evidence is missing\n"
        unless ref($document->{ordinary}) eq 'HASH'
            && ref($document->{ordinary}{artifact}) eq 'HASH';
    my $ordinary_artifact = validate_descriptor(
        $document->{ordinary}{artifact}, $root,
        'final performance ordinary artifact', 0, $MAX_JSON_BYTES);
    my $ordinary = decode_json_bytes($ordinary_artifact->{bytes},
        'final performance ordinary evidence', $ordinary_artifact->{absolute});
    validate_ordinary_performance($ordinary, $identity, $requirements, $root);
    my $performance_policy = $requirements->{performance_acceptance};
    die "Final performance acceptance policy is missing\n"
        unless ref($performance_policy) eq 'HASH';
    my $expected_policy_sha = Digest::SHA::sha256_hex(canonical($performance_policy));
    die "Final performance policy identity is stale\n"
        unless ($document->{policy_sha256} // '') eq $expected_policy_sha;
    die "Final performance evaluation is inconsistent\n"
        unless ref($document->{evaluation}) eq 'HASH'
            && ($document->{evaluation}{decision} // '') eq 'passed'
            && true_value($document->{evaluation}{verified})
            && ($document->{evaluation}{policy_sha256} // '') eq
                $document->{policy_sha256}
            && ref($document->{evaluation}{issues}) eq 'ARRAY'
            && !@{$document->{evaluation}{issues}}
            && ref($document->{evaluation}{review_stops}) eq 'ARRAY'
            && !@{$document->{evaluation}{review_stops}}
            && ref($document->{evaluation}{metrics}) eq 'HASH';
    validate_final_performance_authority($document, $requirements);
    die "Final performance psycho-speed or ordered evidence is missing\n"
        unless ref($document->{psycho_speed}) eq 'HASH'
            && ref($document->{psycho_speed}{rows}) eq 'ARRAY'
            && @{$document->{psycho_speed}{rows}} == 8
            && ref($document->{ordered}) eq 'HASH'
            && ref($document->{ordered}{runs}) eq 'ARRAY'
            && @{$document->{ordered}{runs}} == 4;
    validate_final_performance_identity($final_identity, $identity, $root);
    validate_psycho_performance_rows($document->{psycho_speed}{rows},
        $final_identity, $performance_policy, $root);
    validate_ordered_performance_runs($document->{ordered}{runs},
        $final_identity, $performance_policy, $root);
    return {
        final_performance_contract => 'phase36-final-performance/v1',
        final_performance_sha256 => $selection->{artifact}{sha256},
        performance_authority => 'final-release-wrapper',
    };
}

sub validate_final_performance_identity {
    my ($value, $identity, $root) = @_;
    my @commit = qw(baseline_source_commit candidate_source_commit
        candidate_parent_commit perl5_commit);
    my @artifact = qw(benchmark jfc jdk_executable jdk_version_log
        ordinary_performance_producer performance_evaluator perl_interpreter
        execution_environment baseline_jar candidate_jar baseline_launcher
        candidate_launcher interpreter_launcher jfr_tool jfr_metrics_producer
        time_executable git_executable ps_executable uptime_executable
        ordered_test_source ordered_fixture_manifest
        ordered_fixture_tree_manifest dbix_archive);
    reject_extra_keys($value, 'final performance identity', @commit, @artifact);
    die "Final performance complete source identity is stale\n"
        unless ($value->{candidate_source_commit} // '') eq $identity->{source_commit}
            && ($value->{candidate_parent_commit} // '') eq
                ($value->{baseline_source_commit} // '')
            && ($value->{perl5_commit} // '') eq $identity->{perl5_commit};
    for my $field (@artifact) {
        die "Final performance identity artifact is missing: $field\n"
            unless ref($value->{$field}) eq 'HASH';
        validate_descriptor($value->{$field}, $root,
            "final performance identity $field", 0,
            $field =~ /(?:jar|recording|archive)/ ? $MAX_BLOB_BYTES : $MAX_JSON_BYTES);
    }
    die "Final performance candidate JAR or launcher identity is stale\n"
        unless $value->{candidate_jar}{sha256} eq $identity->{jar_sha256}
            && $value->{candidate_launcher}{sha256} eq $identity->{jperl_sha256};
}

sub validate_final_performance_authority {
    my ($document, $requirements) = @_;
    my $authority = $document->{authority};
    die "Final performance authority is missing\n" unless ref($authority) eq 'HASH';
    reject_extra_keys($authority, 'final performance authority', qw(
        schema_version kind complete execution_attested nonce source
        authority_key_sha256 orchestrator_sha256
        ordinary_performance_producer_sha256 performance_evaluator_sha256
        benchmark_sha256 perl_interpreter_sha256 jfr_metrics_producer_sha256
        requirements_sha256 git_executable_sha256 ps_executable_sha256
        uptime_executable_sha256 process_tree_contract
        evidence_contract_sha256 hmac_sha256));
    die "Final performance authority contract is incomplete\n"
        unless ($authority->{schema_version} // 0) == 1
            && ($authority->{kind} // '') eq 'phase36-performance-authority'
            && true_value($authority->{complete})
            && true_value($authority->{execution_attested})
            && ($authority->{process_tree_contract} // '') eq 'unix-process-groups-v1'
            && ($authority->{nonce} // '') =~ /\A[0-9a-f]{64}\z/
            && ($authority->{hmac_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    my $contract = Digest::SHA::sha256_hex(canonical({map {
        $_ => $document->{$_}
    } qw(schema_version kind identity ordinary psycho_speed ordered
        review_explanations)}));
    die "Final performance authority evidence contract is stale\n"
        unless ($authority->{evidence_contract_sha256} // '') eq $contract;
    for my $field (qw(authority_key_sha256 orchestrator_sha256
            ordinary_performance_producer_sha256 performance_evaluator_sha256
            benchmark_sha256 perl_interpreter_sha256 jfr_metrics_producer_sha256
            requirements_sha256 git_executable_sha256 ps_executable_sha256
            uptime_executable_sha256)) {
        die "Final performance authority hash is malformed: $field\n"
            unless ($authority->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
}

sub validate_psycho_performance_rows {
    my ($rows, $identity, $policy, $root) = @_;
    my %expected;
    for my $spec (@{$policy->{psycho_speed_rows} // []}) {
        $expected{"$_|$spec->{test}"} = 1 for qw(jvm interpreter);
    }
    my %seen;
    for my $row (@$rows) {
        die "Final performance psycho/speed row is malformed\n"
            unless ref($row) eq 'HASH';
        reject_extra_keys($row, 'final performance psycho/speed row', qw(
            backend test source_commit jar_sha256 launcher_sha256 exit_code
            timeout truncated test_source tap command));
        my $key = ($row->{backend} // '') . '|' . ($row->{test} // '');
        die "Final performance psycho/speed row is unexpected or duplicated: $key\n"
            unless $expected{$key} && !$seen{$key}++;
        die "Final performance psycho/speed row did not complete: $key\n"
            unless count_number($row->{exit_code}) && $row->{exit_code} == 0
                && false_value($row->{timeout}) && false_value($row->{truncated})
                && ($row->{source_commit} // '') eq $identity->{candidate_source_commit}
                && ($row->{jar_sha256} // '') eq $identity->{candidate_jar}{sha256};
        validate_descriptor($row->{$_}, $root,
            "final performance psycho/speed $key $_", 0, $MAX_JSON_BYTES)
            for qw(test_source tap command);
    }
    die "Final performance psycho/speed row set is incomplete\n"
        unless canonical([sort keys %seen]) eq canonical([sort keys %expected]);
}

sub validate_ordered_performance_runs {
    my ($runs, $identity, $policy, $root) = @_;
    my @expected = @{$policy->{ordered_execution_order} // []};
    die "Final performance ordered execution policy is malformed\n"
        unless @expected == 4;
    die "Final performance ordered execution order is stale\n"
        unless canonical([map { $_->{side} // '' } @$runs]) eq canonical(\@expected);
    my @descriptor = qw(command environment process_inventory_before
        process_inventory_after load_before load_after load_admission tap
        time_raw jfr_recording jfr_summary jfr_metrics);
    for my $index (0 .. $#$runs) {
        my $run = $runs->[$index];
        die "Final performance ordered run is malformed: $index\n"
            unless ref($run) eq 'HASH';
        for my $field (@descriptor) {
            die "Final performance ordered artifact is missing: $index/$field\n"
                unless ref($run->{$field}) eq 'HASH';
            validate_descriptor($run->{$field}, $root,
                "final performance ordered $index $field", 0,
                $field eq 'jfr_recording' ? $MAX_BLOB_BYTES : $MAX_JSON_BYTES);
        }
        die "Final performance ordered run did not complete: $index\n"
            unless count_number($run->{exit_code}) && $run->{exit_code} == 0
                && false_value($run->{timeout});
    }
}

sub validate_cpan_inputs {
    my ($inputs, $identity, $jcpan_sha) = @_;
    die "CPAN inputs are missing\n" unless ref($inputs) eq 'HASH';
    reject_extra_keys($inputs, 'CPAN inputs', qw(source perl5 jperl jcpan jar sbom));
    my %expected = (
        source => [commit => $identity->{source_commit}],
        perl5 => [commit => $identity->{perl5_commit}],
        jperl => [sha256 => $identity->{jperl_sha256}],
        jcpan => [sha256 => $jcpan_sha],
        jar => [sha256 => $identity->{jar_sha256}],
        sbom => [sha256 => $identity->{sbom_sha256}],
    );
    for my $name (sort keys %expected) {
        my $entry = $inputs->{$name};
        my ($field, $value) = @{$expected{$name}};
        die "CPAN input $name is missing or stale\n"
            unless ref($entry) eq 'HASH'
                && defined($entry->{path}) && !ref($entry->{path})
                && length($entry->{path})
                && ($entry->{$field} // '') eq $value;
        reject_extra_keys($entry, "CPAN input $name", 'path', $field);
    }
}

sub validate_cpan_artifacts {
    my ($artifacts, $root, $targets, $modes) = @_;
    die "CPAN artifact set is missing\n" unless ref($artifacts) eq 'ARRAY';
    my %expected = ('jperl-version.log' => 'jperl-version');
    for my $target (@$targets) {
        for my $mode (@$modes) {
            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            $expected{File::Spec->catfile($base, 'raw.log')} = 'raw-log';
            $expected{File::Spec->catfile($base, 'result.json')} = 'mode-result';
        }
    }
    my %retained;
    for my $entry (@$artifacts) {
        die "CPAN artifact descriptor is malformed\n" unless ref($entry) eq 'HASH';
        reject_extra_keys($entry, 'CPAN artifact descriptor', qw(path sha256 kind));
        my $relative = $entry->{path} // '';
        die "CPAN artifact is unexpected or duplicated: $relative\n"
            unless defined($expected{$relative})
                && ($entry->{kind} // '') eq $expected{$relative}
                && !$retained{$relative};
        $retained{$relative} = validate_descriptor(
            {path => $relative, sha256 => $entry->{sha256}}, $root,
            "CPAN artifact $relative", 0, $MAX_JSON_BYTES);
    }
    die "CPAN artifact set is incomplete\n"
        unless canonical([sort keys %retained]) eq canonical([sort keys %expected]);
    return \%retained;
}

sub validate_cpan_mode {
    my ($entry, $target, $mode, $identity, $inputs, $root, $retained) = @_;
    reject_extra_keys($entry, "CPAN target $target $mode", qw(target mode status
        argv environment environment_sha256 started_at ended_at duration_seconds
        exit_code signal timeout execution_error total_tests failures skips
        zero_tap malformed truncated warning_diagnostics unapproved_warnings
        raw_log identity));
    die "CPAN target $target $mode identity fields are wrong\n"
        unless ($entry->{target} // '') eq $target && ($entry->{mode} // '') eq $mode;
    validate_lane_identity($entry->{identity}, $identity, qw(source_commit
        runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256));
    reject_extra_keys($entry->{identity}, "CPAN target $target $mode identity",
        qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256
            sbom_sha256 jar_path sbom_path));
    die "CPAN target $target $mode artifact paths are stale\n"
        unless ($entry->{identity}{jar_path} // '') eq $inputs->{jar}{path}
            && ($entry->{identity}{sbom_path} // '') eq $inputs->{sbom}{path};
    my $argv = $entry->{argv};
    die "CPAN target $target $mode command is wrong\n"
        unless ref($argv) eq 'ARRAY' && @$argv == 3
            && defined($argv->[0]) && !ref($argv->[0]) && length($argv->[0])
            && $argv->[1] eq '-t' && $argv->[2] eq $target;
    my $environment = $entry->{environment};
    my @environment_keys = qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME TMPDIR
        PERL_MM_USE_DEFAULT JPERL_INTERPRETER JPERL_UNIMPLEMENTED
        PHASE36_CPAN_TARGET PHASE36_CPAN_MODE);
    die "CPAN target $target $mode environment is wrong\n"
        unless ref($environment) eq 'HASH'
            && canonical([sort keys %$environment]) eq canonical([sort @environment_keys])
            && ($environment->{PERLONJAVA_JAR} // '') eq $inputs->{jar}{path}
            && ($environment->{PERLONJAVA_HOME} // '') eq ($environment->{HOME} // '')
            && ($environment->{PERL_MM_USE_DEFAULT} // '') eq '1'
            && ($environment->{PHASE36_CPAN_TARGET} // '') eq $target
            && ($environment->{PHASE36_CPAN_MODE} // '') eq $mode
            && !defined($environment->{JPERL_UNIMPLEMENTED})
            && ($mode eq 'interpreter'
                ? ($environment->{JPERL_INTERPRETER} // '') eq '1'
                : !defined($environment->{JPERL_INTERPRETER}));
    die "CPAN target $target $mode environment hash is stale\n"
        unless ($entry->{environment_sha256} // '')
            eq Digest::SHA::sha256_hex(canonical($environment));
    die "CPAN target $target $mode timing/count metadata is incomplete\n"
        unless defined($entry->{started_at}) && !ref($entry->{started_at})
            && length($entry->{started_at})
            && defined($entry->{ended_at}) && !ref($entry->{ended_at})
            && length($entry->{ended_at})
            && number($entry->{duration_seconds})
            && $entry->{duration_seconds} >= 0
            && count_number($entry->{skips});
    my $base = File::Spec->catfile('runs', slug("$target-$mode"));
    my $raw_relative = File::Spec->catfile($base, 'raw.log');
    my $meta_relative = File::Spec->catfile($base, 'result.json');
    die "CPAN target $target $mode raw-log identity is wrong\n"
        unless ref($entry->{raw_log}) eq 'HASH'
            && ($entry->{raw_log}{path} // '') eq $raw_relative
            && ($entry->{raw_log}{sha256} // '') eq $retained->{$raw_relative}{sha256};
    my $raw = $retained->{$raw_relative}{bytes};
    my @summaries = $raw =~ /^Files=\d+,\s+Tests=(\d+)\b/mg;
    die "CPAN target $target $mode raw TAP total is missing or wrong\n"
        unless @summaries && count_number($summaries[-1])
            && $summaries[-1] == $entry->{total_tests};
    die "CPAN target $target $mode raw log contains an unapproved warning\n"
        if unapproved_warning_lines($raw);
    my $retained_mode = decode_json_bytes($retained->{$meta_relative}{bytes},
        "CPAN target $target $mode retained result",
        $retained->{$meta_relative}{absolute});
    die "CPAN target $target $mode retained result differs from aggregate\n"
        unless canonical($retained_mode) eq canonical($entry);
}

sub slug {
    my $value = lc $_[0];
    $value =~ s/[^a-z0-9]+/-/g;
    $value =~ s/^-|-$//g;
    return $value;
}

sub unapproved_warning_lines {
    my ($text) = @_;
    return scalar grep {
        my $line = $_;
        $line !~ /^\s*(?:ok|not ok|#)/i
            && $line =~ /(?:Use of uninitialized|uninitialized value|Argument .* isn't numeric|Possible unintended interpolation|Wide character in|Subroutine .* redefined|WARNING:|warning:|\bat\s+\S.*\s+line\s+\d+\.?\s*$)/i
    } split /\n/, $text;
}

sub validate_ordinary_performance {
    my ($document, $identity, $requirements, $root) = @_;
    die "Ordinary performance schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'performance';
    reject_extra_keys($document, 'ordinary performance producer', qw(
        schema_version kind verified alternating_order baseline_seconds
        candidate_seconds execution_order source artifacts status timeout
        incomplete review_stop));
    die "Ordinary performance producer did not verify\n"
        unless true_value($document->{verified});
    reject_completion_flags($document, 'ordinary performance producer');
    die "Ordinary performance candidate source is stale\n"
        unless ref($document->{source}{candidate}) eq 'HASH'
            && ($document->{source}{candidate}{commit} // '')
                eq $identity->{source_commit};
    die "Ordinary performance candidate JAR is stale\n"
        unless ref($document->{artifacts}{candidate_jar}) eq 'HASH'
            && ($document->{artifacts}{candidate_jar}{sha256} // '')
                eq $identity->{jar_sha256};
    my $minimum = $requirements->{performance_acceptance}
        {minimum_ordinary_samples};
    die "Performance minimum sample count is invalid\n"
        unless count_number($minimum) && $minimum > 0
            && $minimum <= $MAX_PERFORMANCE_SAMPLES;
    for my $field (qw(baseline_seconds candidate_seconds)) {
        die "Performance $field is incomplete\n"
            unless ref($document->{$field}) eq 'ARRAY'
                && @{$document->{$field}} >= $minimum
                && @{$document->{$field}} <= $MAX_PERFORMANCE_SAMPLES
                && !grep { !number($_) || $_ <= 0 } @{$document->{$field}};
    }
    die "Performance samples were not alternated\n"
        unless true_value($document->{alternating_order});
    die "Performance sample sets differ in size\n"
        unless @{$document->{baseline_seconds}}
            == @{$document->{candidate_seconds}};
    my $order = $document->{execution_order};
    die "Performance execution order is missing or not alternating\n"
        unless ref($order) eq 'ARRAY'
            && @$order == 2 * @{$document->{baseline_seconds}}
            && !grep {
                $order->[$_] ne ($_ % 2 ? 'candidate' : 'baseline')
            } 0 .. $#$order;
    die "Performance producer has no retained artifact descriptor\n"
        unless validate_nested_artifacts($document->{artifacts}, $root,
            'performance producer artifacts');
    die "Performance candidate median regressed\n"
        if median($document->{candidate_seconds}) > median($document->{baseline_seconds});
    return {
        baseline_seconds => [map { 0 + $_ } @{$document->{baseline_seconds}}],
        candidate_seconds => [map { 0 + $_ } @{$document->{candidate_seconds}}],
        alternating_order => JSON::PP::true,
    };
}

sub validate_prerequisite {
    my ($prerequisites, $identity, $root) = @_;
    die "Authority prerequisites must be an object\n"
        unless ref($prerequisites) eq 'HASH';
    reject_extra_keys($prerequisites, 'authority prerequisites', 'perl5_sync');
    my $entry = $prerequisites->{perl5_sync};
    die "Perl5 sync prerequisite is missing\n" unless ref($entry) eq 'HASH';
    reject_extra_keys($entry, 'Perl5 sync prerequisite', qw(producer artifact));
    die "Perl5 sync prerequisite producer is wrong\n"
        unless ($entry->{producer} // '') eq 'run_phase36_perl5_sync_evidence.pl';
    my $artifact = validate_descriptor($entry->{artifact}, abs_path($root),
        'Perl5 sync prerequisite artifact', 0, $MAX_JSON_BYTES);
    my $document = decode_json_bytes($artifact->{bytes},
        'Perl5 sync prerequisite', $artifact->{absolute});
    reject_extra_keys($document, 'Perl5 sync prerequisite document', qw(
        schema_version kind status expected_source_commit timeout_seconds
        repository command source perl5 upstream tools inputs sync_markers
        protected_targets unicode_name final_source_commit));
    die "Perl5 sync prerequisite schema, kind, or status is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'phase36-perl5-sync-evidence'
            && ($document->{status} // '') eq 'pass';
    die "Perl5 sync prerequisite repository is missing or unsafe\n"
        unless defined($document->{repository}) && !ref($document->{repository})
            && length($document->{repository})
            && $document->{repository} !~ /[\r\n\0]/;
    die "Perl5 sync prerequisite timeout is invalid\n"
        unless count_number($document->{timeout_seconds})
            && $document->{timeout_seconds} > 0;
    validate_checkout_pair($document->{source}, $identity->{source_commit},
        'source', 0);
    validate_checkout_pair($document->{perl5}, $identity->{perl5_commit},
        'Perl5', 1);
    die "Perl5 sync prerequisite source identity is stale\n"
        unless ($document->{expected_source_commit} // '') eq $identity->{source_commit}
            && ($document->{final_source_commit} // '') eq $identity->{source_commit}
            && ref($document->{source}{before}) eq 'HASH'
            && ref($document->{source}{after}) eq 'HASH'
            && ($document->{source}{before}{commit} // '') eq $identity->{source_commit}
            && ($document->{source}{after}{commit} // '') eq $identity->{source_commit}
            && true_value($document->{source}{before}{clean})
            && true_value($document->{source}{after}{clean});
    die "Perl5 sync prerequisite Perl checkout identity is stale or dirty\n"
        unless ref($document->{perl5}{before}) eq 'HASH'
            && ref($document->{perl5}{after}) eq 'HASH'
            && ($document->{perl5}{after}{commit} // '') eq $identity->{perl5_commit}
            && true_value($document->{perl5}{before}{acceptance_clean})
            && true_value($document->{perl5}{after}{acceptance_clean});
    die "Perl5 sync prerequisite command did not complete cleanly\n"
        unless ref($document->{command}) eq 'HASH'
            && count_number($document->{command}{exit_code})
            && $document->{command}{exit_code} == 0
            && count_number($document->{command}{signal})
            && $document->{command}{signal} == 0
            && false_value($document->{command}{timeout});
    validate_sync_command($document->{command});
    die "Perl5 sync prerequisite is partial or not idempotent\n"
        unless ref($document->{sync_markers}) eq 'HASH'
            && count_number($document->{sync_markers}{pass_count})
            && $document->{sync_markers}{pass_count} == 2
            && true_value($document->{sync_markers}{second_pass_seen})
            && true_value($document->{sync_markers}{idempotence_verified});
    validate_sync_markers($document->{sync_markers},
        $document->{command}{complete_log}, $identity->{perl5_commit});
    validate_sync_upstream($document->{upstream}, $document->{repository},
        $identity->{perl5_commit});
    validate_named_identity_map($document->{tools}, [qw(git make patch perl rsync)],
        'Perl5 sync tools');
    validate_named_identity_map($document->{inputs},
        [qw(config makefile producer sync_script update_script)],
        'Perl5 sync inputs');
    validate_identity_array($document->{protected_targets},
        'Perl5 sync protected targets', 1);
    validate_unicode_name($document->{unicode_name});
    validate_sync_binding($document);
    return { producer => $entry->{producer}, artifact => {
        path => $artifact->{path}, sha256 => $artifact->{sha256} },
        source_commit => $identity->{source_commit},
        perl5_commit => $identity->{perl5_commit}, verified => JSON::PP::true };
}

sub validate_notice {
    my ($document, $identity) = @_;
    reject_extra_keys($document, 'notice/license producer', qw(schema_version kind
        verified jar_sha256 sbom_sha256 missing_notices changed_notices
        missing_licenses changed_licenses status timeout incomplete review_stop));
    die "Notice/license schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'notice-license';
    die "Notice/license producer did not verify\n" unless true_value($document->{verified});
    die "Notice/license JAR or SBOM identity is stale\n"
        unless ($document->{jar_sha256} // '') eq $identity->{jar_sha256}
            && ($document->{sbom_sha256} // '') eq $identity->{sbom_sha256};
    reject_completion_flags($document, 'notice/license producer');
    for my $field (qw(missing_notices changed_notices missing_licenses
            changed_licenses)) {
        die "Notice/license $field is missing or nonzero\n"
            unless count_number($document->{$field}) && $document->{$field} == 0;
    }
    return { verified => JSON::PP::true,
        map { $_ => 0 } qw(missing_notices changed_notices missing_licenses
            changed_licenses) };
}

sub validate_checkout_pair {
    my ($pair, $commit, $label, $allow_generated_name) = @_;
    die "$label checkout pair is missing\n" unless ref($pair) eq 'HASH';
    reject_extra_keys($pair, "$label checkout pair", qw(before after));
    for my $phase (qw(before after)) {
        my $value = $pair->{$phase};
        die "$label $phase checkout identity is missing\n"
            unless ref($value) eq 'HASH';
        reject_extra_keys($value, "$label $phase checkout identity", qw(path
            commit branch tracked_clean clean acceptance_clean untracked_paths
            allowed_generated_untracked unexpected_untracked status_sha256));
        die "$label $phase checkout identity is stale or incomplete\n"
            unless (($allow_generated_name && $phase eq 'before')
                    ? ($value->{commit} // '') =~ /\A[0-9a-f]{40}\z/
                    : ($value->{commit} // '') eq $commit)
                && defined($value->{path}) && !ref($value->{path})
                && length($value->{path})
                && defined($value->{branch}) && !ref($value->{branch})
                && length($value->{branch})
                && true_value($value->{tracked_clean})
                && true_value($value->{acceptance_clean})
                && ($value->{status_sha256} // '') =~ /\A[0-9a-f]{64}\z/
                && ref($value->{untracked_paths}) eq 'ARRAY'
                && ref($value->{allowed_generated_untracked}) eq 'ARRAY'
                && ref($value->{unexpected_untracked}) eq 'ARRAY'
                && !@{$value->{unexpected_untracked}};
        die "$label $phase checkout has an invalid generated-untracked policy\n"
            if !$allow_generated_name
                && (@{$value->{untracked_paths}}
                    || @{$value->{allowed_generated_untracked}});
        if ($allow_generated_name) {
            die "$label $phase checkout permits an unexpected untracked path\n"
                if grep { $_ ne 'lib/unicore/Name.pl' }
                    @{$value->{allowed_generated_untracked}};
        }
    }
}

sub validate_sync_command {
    my ($command) = @_;
    reject_extra_keys($command, 'Perl5 sync command', qw(argv environment
        exit_code signal timeout duration_seconds complete_log
        complete_log_sha256));
    die "Perl5 sync command argv is missing\n"
        unless ref($command->{argv}) eq 'ARRAY' && @{$command->{argv}}
            && !grep { !defined($_) || ref($_) || /[\r\n\0]/ }
                @{$command->{argv}};
    die "Perl5 sync command environment is missing\n"
        unless ref($command->{environment}) eq 'HASH';
    die "Perl5 sync command duration is invalid\n"
        unless number($command->{duration_seconds})
            && $command->{duration_seconds} >= 0;
    die "Perl5 sync complete log is missing or oversized\n"
        unless defined($command->{complete_log}) && !ref($command->{complete_log})
            && length($command->{complete_log}) > 0
            && length($command->{complete_log}) <= 16 * 1024 * 1024;
    die "Perl5 sync complete log hash is stale\n"
        unless ($command->{complete_log_sha256} // '')
            eq Digest::SHA::sha256_hex($command->{complete_log});
}

sub validate_sync_markers {
    my ($markers, $log, $tip) = @_;
    die "Perl5 sync markers are missing\n" unless ref($markers) eq 'HASH';
    reject_extra_keys($markers, 'Perl5 sync markers', qw(full_manifest_count
        pass_count successful_per_pass errors_per_pass protected_count_per_pass
        second_pass_seen idempotence_verified));
    die "Perl5 sync full-manifest count is invalid\n"
        unless count_number($markers->{full_manifest_count})
            && $markers->{full_manifest_count} > 0;
    for my $field (qw(successful_per_pass errors_per_pass protected_count_per_pass)) {
        die "Perl5 sync $field is incomplete\n"
            unless ref($markers->{$field}) eq 'ARRAY' && @{$markers->{$field}} == 2
                && !grep { !count_number($_) } @{$markers->{$field}};
    }
    die "Perl5 sync pass summaries are inconsistent\n"
        unless !scalar(grep { $_ != $markers->{full_manifest_count} }
                @{$markers->{successful_per_pass}})
            && !scalar(grep { $_ != 0 } @{$markers->{errors_per_pass}})
            && $markers->{protected_count_per_pass}[0]
                == $markers->{protected_count_per_pass}[1];
    my @upstream = $log =~ /^Perl upstream commit:\s*([0-9a-f]{40})\s*$/mg;
    my @verified = $log =~ /^Verified remote tip:\s*([0-9a-f]{40})\s*$/mg;
    my @full = $log =~ /^Full manifest:\s*(\d+) import\(s\) to process\.\s*$/mg;
    my @second = $log =~ /^Running second sync for idempotence verification\.\s*$/mg;
    my @idempotent = $log =~ /^Idempotence verified: second sync changed no imported outputs\.\s*$/mg;
    my @success = $log =~ /^\s*Successful:\s*(\d+)\s*$/mg;
    my @errors = $log =~ /^\s*Errors:\s*(\d+)\s*$/mg;
    my @protected = $log =~ /^Protected paths from config \((\d+)\):\s*$/mg;
    die "Perl5 sync complete log has missing, duplicate, or inconsistent markers\n"
        unless @upstream == 1 && @verified == 1 && $upstream[0] eq $verified[0]
            && $upstream[0] eq $tip
            && @full == 2 && @second == 1 && @idempotent == 1
            && @success == 2 && @errors == 2 && @protected == 2
            && !scalar(grep { !count_number($_) } (@full, @success, @errors, @protected))
            && !scalar(grep { $_ != $markers->{full_manifest_count} } @full)
            && canonical([map { 0 + $_ } @success])
                eq canonical($markers->{successful_per_pass})
            && canonical([map { 0 + $_ } @errors])
                eq canonical($markers->{errors_per_pass})
            && canonical([map { 0 + $_ } @protected])
                eq canonical($markers->{protected_count_per_pass});
    die "Perl5 sync complete log contains partial-sync evidence\n"
        if $log =~ /(?:Filtered mode|--only|\bFILTER\s*=)/i;
}

sub validate_sync_upstream {
    my ($upstream, $repository, $tip) = @_;
    die "Perl5 sync upstream identity is missing\n" unless ref($upstream) eq 'HASH';
    reject_extra_keys($upstream, 'Perl5 sync upstream identity', qw(before after));
    my $canonical;
    for my $phase (qw(before after)) {
        my $value = $upstream->{$phase};
        die "Perl5 sync upstream $phase identity is missing\n"
            unless ref($value) eq 'HASH';
        reject_extra_keys($value, "Perl5 sync upstream $phase identity",
            qw(remote repository_url branch upstream tip));
        die "Perl5 sync upstream $phase identity is incomplete\n"
            unless ($value->{tip} // '') eq $tip
                && defined($value->{remote}) && length($value->{remote})
                && defined($value->{branch}) && length($value->{branch})
                && ($value->{upstream} // '') eq "$value->{remote}/$value->{branch}"
                && defined($value->{repository_url})
                && length($value->{repository_url})
                && normalized_repository($value->{repository_url})
                    eq normalized_repository($repository);
        $canonical //= canonical($value);
        die "Perl5 sync upstream changed during capture\n"
            unless canonical($value) eq $canonical;
    }
}

sub validate_sync_binding {
    my ($document) = @_;
    my $command = $document->{command};
    my $tools = $document->{tools};
    my $source_path = $document->{source}{after}{path};
    die "Perl5 sync command is not bound to the retained tools/source\n"
        unless canonical($command->{argv}) eq canonical([
            $tools->{make}{path}, '-C', $source_path,
            "PERL=$tools->{perl}{path}", 'perl5-sync-check']);
    my $environment = $command->{environment};
    die "Perl5 sync command environment is not exact\n"
        unless ref($environment) eq 'HASH'
            && canonical([sort keys %$environment])
                eq canonical([sort qw(PERL5_REPOSITORY FILTER PATH LC_ALL LANG)])
            && ($environment->{PERL5_REPOSITORY} // '') eq $document->{repository}
            && !defined($environment->{FILTER})
            && defined($environment->{PATH}) && !ref($environment->{PATH})
            && length($environment->{PATH})
            && ($environment->{LC_ALL} // '') eq 'C'
            && ($environment->{LANG} // '') eq 'C';
    die "Perl5 sync protected marker count differs from retained manifest\n"
        unless $document->{sync_markers}{protected_count_per_pass}[0]
            == @{$document->{protected_targets}};
    my $after = $document->{unicode_name}{after};
    die "Perl5 sync imported/upstream Unicode Name.pl identities differ\n"
        unless $after->{imported}{sha256} eq $after->{upstream}{sha256};
}

sub normalized_repository {
    my ($value) = @_;
    return '' unless defined($value) && !ref($value);
    $value =~ s{\Agit\@github\.com:}{https://github.com/}i;
    $value =~ s{\Assh://git\@github\.com/}{https://github.com/}i;
    $value =~ s{/+\z}{};
    $value =~ s{\.git\z}{};
    return $value;
}

sub validate_named_identity_map {
    my ($map, $names, $label) = @_;
    die "$label is missing\n" unless ref($map) eq 'HASH';
    reject_extra_keys($map, $label, @$names);
    for my $name (@$names) {
        validate_file_identity($map->{$name}, "$label $name");
        die "$label $name path is not an absolute trusted identity\n"
            unless File::Spec->file_name_is_absolute($map->{$name}{path})
                && $map->{$name}{path} !~ /[\r\n\0]/;
    }
}

sub validate_identity_array {
    my ($array, $label, $nonempty) = @_;
    die "$label is missing\n"
        unless ref($array) eq 'ARRAY' && (!$nonempty || @$array);
    my %path;
    for my $entry (@$array) {
        validate_file_identity($entry, "$label entry");
        die "$label contains an unsafe relative path\n"
            if File::Spec->file_name_is_absolute($entry->{path})
                || grep { $_ eq '..' } File::Spec->splitdir($entry->{path});
        die "$label repeats a path\n" if $path{$entry->{path}}++;
    }
}

sub validate_file_identity {
    my ($identity, $label) = @_;
    die "$label identity is missing\n" unless ref($identity) eq 'HASH';
    reject_extra_keys($identity, "$label identity", qw(path sha256 present));
    die "$label path or SHA-256 is invalid\n"
        unless defined($identity->{path}) && !ref($identity->{path})
            && length($identity->{path})
            && ($identity->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    die "$label is not present\n"
        if exists($identity->{present}) && !true_value($identity->{present});
}

sub validate_unicode_name {
    my ($unicode) = @_;
    die "Perl5 sync Unicode Name.pl identity is missing\n"
        unless ref($unicode) eq 'HASH';
    reject_extra_keys($unicode, 'Perl5 sync Unicode Name.pl identity',
        qw(before after));
    for my $phase (qw(before after)) {
        my $pair = $unicode->{$phase};
        die "Perl5 sync Unicode Name.pl $phase identity is missing\n"
            unless ref($pair) eq 'HASH';
        reject_extra_keys($pair, "Perl5 sync Unicode Name.pl $phase identity",
            qw(imported upstream));
        for my $side (qw(imported upstream)) {
            validate_optional_file_identity($pair->{$side},
                "Perl5 sync Unicode Name.pl $phase $side", $phase eq 'after');
        }
    }
}

sub validate_optional_file_identity {
    my ($identity, $label, $required) = @_;
    die "$label identity is missing\n" unless ref($identity) eq 'HASH';
    reject_extra_keys($identity, "$label identity", qw(path sha256 present));
    die "$label path is invalid\n"
        unless defined($identity->{path}) && !ref($identity->{path})
            && length($identity->{path})
            && File::Spec->file_name_is_absolute($identity->{path})
            && $identity->{path} !~ /[\r\n\0]/;
    if (true_value($identity->{present})) {
        die "$label SHA-256 is invalid\n"
            unless ($identity->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
        return;
    }
    die "$label must be present\n" if $required;
    die "$label absence marker is invalid\n"
        unless false_value($identity->{present}) && !defined($identity->{sha256});
}

sub validate_ci {
    my ($document, $identity, $requirements, $requirements_sha256) = @_;
    reject_extra_keys($document, 'CI producer', qw(schema schema_version kind
        producer status mode verified authoritative identity source completion platforms
        evidence tools raw_api_evidence seal));
    die "CI producer schema or kind is wrong\n"
        unless ($document->{schema} // '')
                eq 'perlonjava.phase36.final-envelope-bridge/v1'
            && count_number($document->{schema_version})
            && $document->{schema_version} == 1
            && ($document->{kind} // '') eq 'ci'
            && ($document->{producer} // '') eq 'run_phase36_ci_evidence.pl';
    die "CI producer is not authoritative acceptance evidence\n"
        unless ($document->{status} // '') eq 'pass'
            && ($document->{mode} // '') eq 'acceptance'
            && true_value($document->{verified})
            && true_value($document->{authoritative});
    validate_lane_identity($document->{identity}, $identity, 'source_commit');
    reject_extra_keys($document->{identity}, 'CI identity', 'source_commit');
    my $source = $document->{source};
    die "CI source is missing\n" unless ref($source) eq 'HASH';
    reject_extra_keys($source, 'CI source', qw(repository commit));
    die "CI source is stale or from the wrong repository\n"
        unless ($source->{repository} // '') eq 'fglock/PerlOnJava'
            && ($source->{commit} // '') eq $identity->{source_commit};
    validate_completion_record($document->{completion}, 'CI');

    my $required = $requirements->{required_ci_platforms};
    die "CI platform policy is missing\n" unless ref($required) eq 'ARRAY';
    die "CI platform policy must be exactly ubuntu-latest and windows-latest\n"
        unless canonical([sort @$required])
            eq canonical([sort qw(ubuntu-latest windows-latest)]);
    my $platforms = $document->{platforms};
    die "CI platforms must be an object\n" unless ref($platforms) eq 'HASH';
    die "CI platform set differs from policy\n"
        unless canonical([sort keys %$platforms]) eq canonical([sort @$required]);
    my (%job_id, %check_id, %job_name);
    for my $platform (@$required) {
        my $result = $platforms->{$platform};
        die "CI platform $platform is missing\n" unless ref($result) eq 'HASH';
        reject_extra_keys($result, "CI platform $platform", qw(status
            source_commit job_check_name job_id check_run_id));
        die "CI platform $platform did not succeed or used stale source\n"
            unless ($result->{status} // '') eq 'success'
                && ($result->{source_commit} // '') eq $identity->{source_commit};
        die "CI platform $platform has an invalid check name or ID\n"
            unless defined($result->{job_check_name})
                && !ref($result->{job_check_name})
                && length($result->{job_check_name}) <= 200
                && $result->{job_check_name} =~ /\A[\x20-\x7e]+\z/
                && positive_count($result->{job_id})
                && positive_count($result->{check_run_id})
                && $result->{job_id} == $result->{check_run_id};
        die "CI platform job/check identity is duplicated\n"
            if $job_id{$result->{job_id}}++ || $check_id{$result->{check_run_id}}++
                || $job_name{$result->{job_check_name}}++;
    }

    my $evidence = $document->{evidence};
    die "CI retained evidence is missing\n" unless ref($evidence) eq 'HASH';
    reject_extra_keys($evidence, 'CI retained evidence', qw(schema
        producer_version producer_sha256 fixture_only repository source_commit
        local_clean_exact_commit workflow policy run required_matrix jobs checks));
    die "CI retained evidence is not live exact-source evidence\n"
        unless ($evidence->{schema} // '')
                eq 'perlonjava.phase36.ci-acceptance-evidence/v1'
            && defined($evidence->{producer_version})
            && !ref($evidence->{producer_version})
            && length($evidence->{producer_version})
            && ($evidence->{producer_sha256} // '') =~ /\A[0-9a-f]{64}\z/
            && false_value($evidence->{fixture_only})
            && ($evidence->{repository} // '') eq $source->{repository}
            && ($evidence->{source_commit} // '') eq $identity->{source_commit}
            && true_value($evidence->{local_clean_exact_commit});
    validate_ci_workflow($evidence->{workflow});
    validate_ci_policy($evidence->{policy}, $requirements_sha256);
    my $matrix = $evidence->{required_matrix};
    die "CI retained matrix is missing or stale\n"
        unless ref($matrix) eq 'HASH'
            && canonical([sort keys %$matrix]) eq canonical([sort @$required]);
    for my $platform (@$required) {
        die "CI retained matrix differs from platform evidence: $platform\n"
            unless ($matrix->{$platform} // '')
                eq $platforms->{$platform}{job_check_name};
    }
    my $run = validate_ci_run($evidence->{run}, $identity->{source_commit});
    validate_ci_jobs_checks($evidence->{jobs}, $evidence->{checks}, $platforms,
        $run, $identity->{source_commit});
    validate_ci_tools($document->{tools});
    validate_ci_raw_evidence($document->{raw_api_evidence});
    validate_payload_seal($document, 'CI producer');
    return $document;
}

sub validate_completion_record {
    my ($completion, $label) = @_;
    die "$label completion record is missing\n" unless ref($completion) eq 'HASH';
    reject_extra_keys($completion, "$label completion", qw(exit_code signal
        timeout incomplete review_stop));
    die "$label completion is non-pass, incomplete, timed out, or review-stopped\n"
        unless count_number($completion->{exit_code})
            && $completion->{exit_code} == 0
            && count_number($completion->{signal}) && $completion->{signal} == 0
            && false_value($completion->{timeout})
            && false_value($completion->{incomplete})
            && false_value($completion->{review_stop});
}

sub validate_ci_workflow {
    my ($workflow) = @_;
    die "CI workflow evidence is missing\n" unless ref($workflow) eq 'HASH';
    reject_extra_keys($workflow, 'CI workflow evidence', qw(id name path sha256 size));
    die "CI workflow evidence is invalid\n"
        unless positive_count($workflow->{id})
            && defined($workflow->{name}) && !ref($workflow->{name})
            && length($workflow->{name}) && length($workflow->{name}) <= 200
            && ($workflow->{path} // '')
                =~ m{\A\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml\z}
            && ($workflow->{sha256} // '') =~ /\A[0-9a-f]{64}\z/
            && positive_count($workflow->{size});
}

sub validate_ci_policy {
    my ($policy, $requirements_sha256) = @_;
    die "CI policy evidence is missing\n" unless ref($policy) eq 'HASH';
    reject_extra_keys($policy, 'CI policy evidence', qw(path sha256
        requirements_path requirements_sha256));
    die "CI policy evidence is invalid\n"
        unless ($policy->{path} // '') eq 'dev/tools/phase36_ci_evidence_policy.json'
            && ($policy->{requirements_path} // '')
                eq 'dev/tools/phase36_acceptance_requirements.json'
            && ($policy->{sha256} // '') =~ /\A[0-9a-f]{64}\z/
            && ($policy->{requirements_sha256} // '') eq $requirements_sha256;
}

sub validate_ci_run {
    my ($run, $source_commit) = @_;
    die "CI workflow run evidence is missing\n" unless ref($run) eq 'HASH';
    reject_extra_keys($run, 'CI workflow run evidence', qw(id run_number
        run_attempt workflow_id check_suite_id head_sha event status conclusion
        created_at updated_at));
    die "CI workflow run is not a successful first attempt on the exact source\n"
        unless positive_count($run->{id}) && positive_count($run->{run_number})
            && count_number($run->{run_attempt}) && $run->{run_attempt} == 1
            && positive_count($run->{workflow_id})
            && positive_count($run->{check_suite_id})
            && ($run->{head_sha} // '') eq $source_commit
            && defined($run->{event}) && !ref($run->{event}) && length($run->{event})
            && ($run->{status} // '') eq 'completed'
            && ($run->{conclusion} // '') eq 'success'
            && utc_timestamp($run->{created_at}) && utc_timestamp($run->{updated_at})
            && $run->{updated_at} ge $run->{created_at};
    return $run;
}

sub validate_ci_jobs_checks {
    my ($jobs, $checks, $platforms, $run, $source_commit) = @_;
    die "CI retained jobs or checks are missing\n"
        unless ref($jobs) eq 'ARRAY' && ref($checks) eq 'ARRAY'
            && @$jobs == keys(%$platforms) && @$checks == keys(%$platforms);
    my (%jobs, %checks);
    for my $job (@$jobs) {
        die "CI retained job is malformed\n" unless ref($job) eq 'HASH';
        reject_extra_keys($job, 'CI retained job', qw(id run_id run_attempt name
            head_sha status conclusion started_at completed_at));
        die "CI retained job is stale or incomplete\n"
            unless positive_count($job->{id}) && positive_count($job->{run_id})
                && $job->{run_id} == $run->{id}
                && count_number($job->{run_attempt}) && $job->{run_attempt} == 1
                && defined($job->{name}) && !ref($job->{name})
                && ($job->{head_sha} // '') eq $source_commit
                && ($job->{status} // '') eq 'completed'
                && ($job->{conclusion} // '') eq 'success'
                && utc_timestamp($job->{started_at})
                && utc_timestamp($job->{completed_at})
                && $job->{completed_at} ge $job->{started_at};
        die "CI retained job ID is duplicated\n" if $jobs{$job->{id}}++;
    }
    for my $check (@$checks) {
        die "CI retained check is malformed\n" unless ref($check) eq 'HASH';
        reject_extra_keys($check, 'CI retained check', qw(id name head_sha status
            conclusion started_at completed_at check_suite_id app));
        die "CI retained check app is malformed\n" unless ref($check->{app}) eq 'HASH';
        reject_extra_keys($check->{app}, 'CI retained check app', qw(id slug));
        die "CI retained check is stale or incomplete\n"
            unless positive_count($check->{id})
                && defined($check->{name}) && !ref($check->{name})
                && ($check->{head_sha} // '') eq $source_commit
                && ($check->{status} // '') eq 'completed'
                && ($check->{conclusion} // '') eq 'success'
                && utc_timestamp($check->{started_at})
                && utc_timestamp($check->{completed_at})
                && $check->{completed_at} ge $check->{started_at}
                && positive_count($check->{check_suite_id})
                && $check->{check_suite_id} == $run->{check_suite_id}
                && positive_count($check->{app}{id})
                && ($check->{app}{slug} // '') eq 'github-actions';
        die "CI retained check ID is duplicated\n" if $checks{$check->{id}}++;
    }
    for my $platform (keys %$platforms) {
        my $entry = $platforms->{$platform};
        my ($job) = grep { $_->{id} == $entry->{job_id} } @$jobs;
        my ($check) = grep { $_->{id} == $entry->{check_run_id} } @$checks;
        die "CI platform is not bound to its retained job/check: $platform\n"
            unless $job && $check && $job->{name} eq $entry->{job_check_name}
                && $check->{name} eq $entry->{job_check_name};
    }
}

sub validate_ci_tools {
    my ($tools) = @_;
    die "CI tool evidence is missing\n" unless ref($tools) eq 'HASH';
    reject_extra_keys($tools, 'CI tools', qw(git gh));
    die "CI tool set is incomplete\n" unless keys(%$tools) == 2;
    for my $name (qw(git gh)) {
        my $tool = $tools->{$name};
        die "CI $name tool evidence is missing\n" unless ref($tool) eq 'HASH';
        my @fields = qw(path sha256 size version_sha256 version);
        push @fields, 'offline' if $name eq 'gh';
        reject_extra_keys($tool, "CI $name tool evidence", @fields);
        die "CI $name tool evidence is incomplete\n"
            unless defined($tool->{path}) && !ref($tool->{path}) && length($tool->{path})
                && ($tool->{sha256} // '') =~ /\A[0-9a-f]{64}\z/
                && positive_count($tool->{size})
                && ($tool->{version_sha256} // '') =~ /\A[0-9a-f]{64}\z/
                && defined($tool->{version}) && !ref($tool->{version})
                && length($tool->{version})
                && ($name ne 'gh' || false_value($tool->{offline}));
    }
}

sub validate_ci_raw_evidence {
    my ($records) = @_;
    die "CI raw API evidence is missing\n"
        unless ref($records) eq 'ARRAY' && @$records >= 6 && @$records <= 100;
    my %label;
    for my $record (@$records) {
        die "CI raw API record is malformed\n" unless ref($record) eq 'HASH';
        reject_extra_keys($record, 'CI raw API record', qw(label size sha256
            base64 endpoint));
        my $value = $record->{base64};
        die "CI raw API record encoding is malformed\n"
            unless defined($value) && !ref($value)
                && $value =~ /\A(?:[A-Za-z0-9+\/] {4})*(?:[A-Za-z0-9+\/]{2}==|[A-Za-z0-9+\/]{3}=)?\z/x;
        my $bytes = decode_base64($value);
        die "CI raw API record encoding is not canonical\n"
            unless encode_base64($bytes, '') eq $value;
        die "CI raw API record identity is incomplete\n"
            unless defined($record->{label}) && !ref($record->{label})
                && length($record->{label}) && !$label{$record->{label}}++
                && size_number($record->{size}) && $record->{size} == length($bytes)
                && ($record->{sha256} // '') eq Digest::SHA::sha256_hex($bytes)
                && (!exists($record->{endpoint})
                    || (defined($record->{endpoint}) && !ref($record->{endpoint})
                        && length($record->{endpoint})));
    }
    for my $required (qw(tool:git-version tool:gh-version api:workflow
            api:commit api:jobs api:checks)) {
        die "CI raw API evidence is missing $required\n" unless $label{$required};
    }
    die "CI raw API evidence is missing workflow-run polling\n"
        unless grep { /\Aapi:runs-[0-9]+\z/ } keys %label;
}

sub validate_payload_seal {
    my ($document, $label) = @_;
    my $seal = $document->{seal};
    die "$label seal is missing\n" unless ref($seal) eq 'HASH';
    reject_extra_keys($seal, "$label seal", qw(algorithm payload_sha256));
    my %payload = %$document;
    delete $payload{seal};
    die "$label seal is stale\n"
        unless ($seal->{algorithm} // '') eq 'sha256'
            && ($seal->{payload_sha256} // '')
                eq Digest::SHA::sha256_hex(canonical(\%payload));
}

sub positive_count {
    return defined($_[0]) && !ref($_[0])
        && $_[0] =~ /\A[1-9]\d*\z/ && length($_[0]) <= 15;
}

sub utc_timestamp {
    return defined($_[0]) && !ref($_[0])
        && $_[0] =~ /\A\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\z/;
}

sub validate_make {
    my ($selection, $identity) = @_;
    my $document = $selection->{document};
    reject_extra_keys($document, 'make producer', qw(artifacts authoritative
        command completion failure_scan identity inputs kind mode producer schema
        schema_version seal source status tools verified warning_scan));
    die "Make producer schema or mode is wrong\n"
        unless ($document->{schema} // '') eq
                'perlonjava.phase36.make-evidence/v1'
            && ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'make'
            && ($document->{producer} // '') eq
                'run_phase36_make_evidence.pl'
            && ($document->{mode} // '') eq 'acceptance';
    die "Make producer is not authoritative acceptance evidence\n"
        unless ($document->{status} // '') eq 'pass'
            && true_value($document->{verified})
            && true_value($document->{authoritative});
    my $make_identity = $document->{identity};
    die "Make identity is missing\n" unless ref($make_identity) eq 'HASH';
    reject_extra_keys($make_identity, 'make identity', qw(jar_embedded_commit
        jar_reported_commit jar_sha256 runner_commit source_commit));
    validate_lane_identity($make_identity, $identity,
        qw(source_commit runner_commit jar_sha256));
    for my $field (qw(jar_reported_commit jar_embedded_commit)) {
        my $commit = $make_identity->{$field} // '';
        die "Make $field differs from selected source\n"
            unless $commit =~ /\A[0-9a-f]{7,40}\z/
                && index($identity->{source_commit}, $commit) == 0;
    }
    die "Make completion record is missing\n"
        unless ref($document->{completion}) eq 'HASH';
    reject_extra_keys($document->{completion}, 'make completion', qw(exit_code
        signal timeout incomplete truncated review_stop));
    die "Make completion is non-pass, incomplete, timed out, or truncated\n"
        unless count_number($document->{completion}{exit_code})
            && $document->{completion}{exit_code} == 0
            && count_number($document->{completion}{signal})
            && $document->{completion}{signal} == 0
            && false_value($document->{completion}{timeout})
            && false_value($document->{completion}{incomplete})
            && false_value($document->{completion}{truncated})
            && false_value($document->{completion}{review_stop});
    die "Make source record is missing\n" unless ref($document->{source}) eq 'HASH';
    reject_extra_keys($document->{source}, 'make source', qw(root before after));
    die "Make source root is missing\n"
        unless defined($document->{source}{root})
            && !ref($document->{source}{root})
            && length($document->{source}{root})
            && File::Spec->file_name_is_absolute($document->{source}{root})
            && !-l $document->{source}{root}
            && (abs_path($document->{source}{root}) // '') eq
                $document->{source}{root};
    for my $when (qw(before after)) {
        my $state = $document->{source}{$when};
        die "Make source $when state is missing\n" unless ref($state) eq 'HASH';
        reject_extra_keys($state, "make source $when", qw(all_status_sha256
            diff_sha256 extras head status_sha256 tracked_clean));
        die "Make source $when state is stale or dirty\n"
            unless ($state->{head} // '') eq $identity->{source_commit}
                && true_value($state->{tracked_clean})
                && 3 == grep { ($state->{$_} // '') =~ /\A[0-9a-f]{64}\z/ }
                    qw(all_status_sha256 diff_sha256 status_sha256);
        my $extras = $state->{extras};
        die "Make source $when extras are missing\n" unless ref($extras) eq 'HASH';
        reject_extra_keys($extras, "make source $when extras", qw(authority_inputs
            generated_file_count generated_paths generated_total_bytes));
        die "Make source $when extras are malformed\n"
            unless ref($extras->{authority_inputs}) eq 'ARRAY'
                && ref($extras->{generated_paths}) eq 'ARRAY'
                && count_number($extras->{generated_file_count})
                && count_number($extras->{generated_total_bytes});
    }
    my $root = dirname($selection->{artifact}{absolute});
    die "Make artifacts are missing\n" unless ref($document->{artifacts}) eq 'HASH';
    reject_extra_keys($document->{artifacts}, 'make artifacts', qw(jar jar_embedded
        jar_version make_log source_after source_before tool_versions));
    for my $name (sort keys %{$document->{artifacts}}) {
        my $descriptor = $document->{artifacts}{$name};
        die "Make artifact $name is malformed\n"
            unless ref($descriptor) eq 'HASH';
        reject_extra_keys($descriptor, "make artifact $name", qw(path sha256 size));
        my $limit = $name eq 'jar' ? $MAX_BLOB_BYTES : $MAX_JSON_BYTES;
        my $validated = validate_descriptor({path => $descriptor->{path},
                sha256 => $descriptor->{sha256}}, $root,
            "make artifact $name", 1, $limit, $name eq 'jar');
        die "Make artifact $name size is stale\n"
            unless size_number($descriptor->{size})
                && $descriptor->{size} == -s $validated->{absolute};
    }
    die "Make JAR artifact differs from selected JAR\n"
        unless ($document->{artifacts}{jar}{sha256} // '') eq
            $identity->{jar_sha256};
    for my $scan_name (qw(warning_scan failure_scan)) {
        my $scan = $document->{$scan_name};
        die "Make $scan_name is missing\n" unless ref($scan) eq 'HASH';
        reject_extra_keys($scan, "make $scan_name", qw(classifier
            classifier_sha256 complete_log_sha256 count matches));
        die "Make $scan_name is incomplete or nonzero\n"
            unless defined($scan->{classifier}) && !ref($scan->{classifier})
                && length($scan->{classifier})
                && ($scan->{classifier_sha256} // '') =~ /\A[0-9a-f]{64}\z/
                && ($scan->{complete_log_sha256} // '') eq
                    ($document->{artifacts}{make_log}{sha256} // '')
                && count_number($scan->{count}) && $scan->{count} == 0
                && ref($scan->{matches}) eq 'ARRAY' && !@{$scan->{matches}};
    }
    for my $container (qw(command tools inputs)) {
        die "Make $container is missing\n"
            unless ref($document->{$container}) eq 'HASH';
    }
    reject_extra_keys($document->{command}, 'make command', qw(argv cwd
        duration_milliseconds environment finished_utc started_utc));
    die "Make command record is malformed\n"
        unless ref($document->{command}{argv}) eq 'ARRAY'
            && @{$document->{command}{argv}}
            && ref($document->{command}{environment}) eq 'HASH'
            && defined($document->{command}{cwd})
            && !ref($document->{command}{cwd})
            && count_number($document->{command}{duration_milliseconds})
            && utc_timestamp($document->{command}{started_utc})
            && utc_timestamp($document->{command}{finished_utc});
    reject_extra_keys($document->{tools}, 'make tools', qw(git jar_tool java make
        perl producer shell));
    for my $name (qw(git jar_tool java make perl shell)) {
        my $tool = $document->{tools}{$name};
        die "Make tool $name is missing\n" unless ref($tool) eq 'HASH';
        reject_extra_keys($tool, "make tool $name", qw(path sha256 size
            version_sha256));
        validate_descriptor({path => $tool->{path}, sha256 => $tool->{sha256}},
            $root, "make tool $name", 1, $MAX_BLOB_BYTES, 1);
        die "Make tool $name version identity is missing\n"
            unless size_number($tool->{size})
                && ($tool->{version_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    my $producer_tool = $document->{tools}{producer};
    die "Make producer tool is missing\n" unless ref($producer_tool) eq 'HASH';
    reject_extra_keys($producer_tool, 'make producer tool', qw(path sha256 size));
    my $producer_validated = validate_descriptor({path => $producer_tool->{path},
            sha256 => $producer_tool->{sha256}}, $root,
        'make producer tool', 1, $MAX_JSON_BYTES, 1);
    die "Make producer tool size is stale\n"
        unless size_number($producer_tool->{size})
            && $producer_tool->{size} == -s $producer_validated->{absolute};
    reject_extra_keys($document->{inputs}, 'make inputs', qw(build_gradle
        gradle_wrapper_jar gradle_wrapper_properties gradlew makefile
        settings_gradle));
    for my $name (sort keys %{$document->{inputs}}) {
        my $descriptor = $document->{inputs}{$name};
        die "Make input $name is missing\n" unless ref($descriptor) eq 'HASH';
        reject_extra_keys($descriptor, "make input $name", qw(path sha256 size));
        my $validated = validate_descriptor({path => $descriptor->{path},
                sha256 => $descriptor->{sha256}}, $root,
            "make input $name", 1, $MAX_BLOB_BYTES, 1);
        die "Make input $name size is stale\n"
            unless size_number($descriptor->{size})
                && $descriptor->{size} == -s $validated->{absolute};
    }
    my $seal = $document->{seal};
    die "Make seal is missing\n" unless ref($seal) eq 'HASH';
    reject_extra_keys($seal, 'make seal', qw(algorithm payload_sha256));
    my %payload = %$document;
    delete $payload{seal};
    die "Make seal is stale\n"
        unless ($seal->{algorithm} // '') eq 'SHA-256'
            && ($seal->{payload_sha256} // '') eq
                Digest::SHA::sha256_hex(canonical(\%payload));
    return $document;
}

sub validate_simple_completion {
    my ($document, $kind, $producer, $identity) = @_;
    die "$kind producer schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq $kind;
    my @kind_fields = $kind eq 'packaging'
        ? qw(missing_entries duplicate_entries)
        : $kind eq 'make' ? qw(warnings failures)
        : $kind eq 'ci' ? qw(platforms) : ();
    reject_extra_keys($document, "$kind producer", qw(schema_version kind
        verified identity completion artifacts), @kind_fields);
    die "$kind producer did not verify\n" unless true_value($document->{verified});
    validate_lane_identity($document->{identity}, $identity, qw(source_commit));
    validate_completion_record($document->{completion}, $kind);
    die "$kind producer has no structured hashed artifacts\n"
        unless ref($document->{artifacts}) eq 'HASH'
            && keys %{$document->{artifacts}};
    die "$kind producer has no retained artifact descriptor\n"
        unless validate_nested_artifacts($document->{artifacts}, $authority_root,
            "$kind producer artifacts");
    return $document;
}

sub validate_lane_identity {
    my ($actual, $expected, @fields) = @_;
    die "Producer identity is missing\n" unless ref($actual) eq 'HASH';
    for my $field (@fields) {
        die "Producer identity is stale: $field\n"
            unless ($actual->{$field} // '') eq ($expected->{$field} // '');
    }
}

sub reject_completion_flags {
    my ($document, $label) = @_;
    for my $field (qw(timeout incomplete review_stop)) {
        die "$label reports $field\n" if true_value($document->{$field});
    }
    die "$label reports non-pass status\n"
        if exists($document->{status})
            && ($document->{status} // '') !~ /\A(?:pass|passed|success)\z/;
}

sub validate_artifact_map {
    my ($map, $root, $label) = @_;
    die "$label artifacts must be an object\n" unless ref($map) eq 'HASH';
    my %validated;
    for my $name (keys %$map) {
        die "$label artifact name is unsafe\n"
            unless $name =~ /\A[A-Za-z0-9][A-Za-z0-9_.-]*\z/;
        my $limit = $name =~ /\.(?:json|log|txt)\z/
            ? $MAX_JSON_BYTES : $MAX_BLOB_BYTES;
        $validated{$name} = validate_descriptor($map->{$name}, $root,
            "$label artifact $name", 1, $limit);
    }
    return \%validated;
}

sub validate_nested_artifacts {
    my ($value, $root, $label) = @_;
    if (ref($value) eq 'HASH') {
        if (exists($value->{path}) || exists($value->{sha256})) {
            my $limit = defined($value->{path}) && !ref($value->{path})
                    && $value->{path} =~ /\.json\z/
                ? $MAX_JSON_BYTES : $MAX_BLOB_BYTES;
            my $validated = validate_descriptor({
                path => $value->{path}, sha256 => $value->{sha256},
            }, $root, $label, 1, $limit);
            die "$label size is stale\n"
                if exists($value->{size})
                    && (!size_number($value->{size})
                        || $value->{size} != -s $validated->{absolute});
            my %allowed = map { $_ => 1 } qw(path sha256 size);
            my @extra = grep { !$allowed{$_} } keys %$value;
            die "$label has unsupported fields: " . join(', ', sort @extra) . "\n"
                if @extra;
            return 1;
        }
        my $count = 0;
        $count += validate_nested_artifacts($value->{$_}, $root, "$label $_")
            for sort keys %$value;
        return $count;
    }
    if (ref($value) eq 'ARRAY') {
        my $count = 0;
        $count += validate_nested_artifacts($value->[$_], $root, "$label $_")
            for 0 .. $#$value;
        return $count;
    }
    die "$label is not a hashed artifact descriptor\n";
}

sub validate_descriptor {
    my ($descriptor, $root, $label, $allow_absolute, $limit,
        $allow_outside) = @_;
    die "$label descriptor is missing\n" unless ref($descriptor) eq 'HASH';
    reject_extra_keys($descriptor, "$label descriptor", qw(path sha256));
    my $path = $descriptor->{path} // '';
    die "$label path is missing\n" unless length $path;
    die "$label path is absolute\n"
        if File::Spec->file_name_is_absolute($path) && !$allow_absolute;
    die "$label path has parent traversal\n"
        if grep { $_ eq '..' } File::Spec->splitdir($path);
    my $candidate = File::Spec->file_name_is_absolute($path)
        ? $path : File::Spec->catfile($root, File::Spec->splitdir($path));
    die "$label is a symlink\n" if -l $candidate;
    my $absolute = abs_path($candidate);
    die "$label is missing or empty\n"
        unless defined($absolute) && -f $absolute && -s $absolute;
    die "$label resolves outside the evidence directory\n"
        unless $allow_outside || path_inside($absolute, $root);
    my $sha = $descriptor->{sha256} // '';
    die "$label SHA-256 is malformed\n" unless $sha =~ /\A[0-9a-f]{64}\z/;
    my ($bytes, $observed) = read_immutable_bounded($absolute,
        $limit // $MAX_BLOB_BYTES, $label,
        ($limit // $MAX_BLOB_BYTES) <= $MAX_JSON_BYTES);
    die "$label hash mismatch\n" unless $observed eq $sha;
    my $result = { path => ($allow_outside ? $absolute
            : File::Spec->abs2rel($absolute, $root)),
        absolute => $absolute, sha256 => $sha };
    $result->{bytes} = $bytes if defined $bytes;
    return $result;
}

sub gate_record {
    my ($selection, $identity, $details) = @_;
    return {
        state => 'passed',
        artifact => {
            path => $selection->{artifact}{path},
            sha256 => $selection->{artifact}{sha256},
        },
        identity => $identity,
        details => $details,
    };
}

sub same_artifact {
    my ($left, $right) = @_;
    return $left->{absolute} eq $right->{absolute}
        && $left->{sha256} eq $right->{sha256};
}

sub reject_extra_keys {
    my ($object, $label, @allowed) = @_;
    my %allowed = map { $_ => 1 } @allowed;
    my @extra = sort grep { !$allowed{$_} } keys %$object;
    die "$label has unsupported fields: " . join(', ', @extra) . "\n" if @extra;
}

sub path_inside {
    my ($path, $root) = @_;
    return 1 if $path eq $root;
    return index($path, $root . '/') == 0;
}

sub load_json {
    my ($path, $label, $expected_sha) = @_;
    my ($bytes, $observed) = read_immutable_bounded($path, $MAX_JSON_BYTES,
        $label, 1);
    die "$label hash mismatch\n"
        if defined($expected_sha) && $observed ne $expected_sha;
    return decode_json_bytes($bytes, $label, $path);
}

sub decode_json_bytes {
    my ($bytes, $label, $path) = @_;
    reject_duplicate_json_keys($bytes, $label);
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in " . ($path // '<snapshot>') . "\n"
        unless $document && ref($document) eq 'HASH';
    return $document;
}

sub read_bounded {
    my ($path, $limit, $label) = @_;
    my ($bytes) = read_immutable_bounded($path, $limit, $label, 1);
    return $bytes;
}

sub read_immutable_bounded {
    my ($path, $limit, $label, $retain) = @_;
    die "$label read limit is invalid\n"
        unless whole_number($limit) && $limit > 0 && $limit <= $MAX_BLOB_BYTES;
    die "$label is a symlink\n" if -l $path;
    my @before = lstat($path);
    die "$label is missing or empty\n"
        unless @before && -f _ && $before[7] > 0;
    die "$label exceeds bounded read limit\n" if $before[7] > $limit;
    sysopen(my $fh, $path, O_RDONLY)
        or die "Cannot open immutable $label $path: $!\n";
    binmode $fh;
    my @opened = stat($fh);
    die "$label changed before immutable read\n"
        unless same_stat(\@before, \@opened);
    my $digest = Digest::SHA->new(256);
    my $bytes = '';
    my $total = 0;
    while (1) {
        my $count = read($fh, my $chunk, 1024 * 1024);
        die "Cannot read immutable $label $path: $!\n" unless defined $count;
        last unless $count;
        $total += $count;
        die "$label exceeds bounded read limit\n" if $total > $limit;
        $digest->add($chunk);
        $bytes .= $chunk if $retain;
    }
    my @after_fh = stat($fh);
    close $fh or die "Cannot close immutable $label $path: $!\n";
    my @after_path = lstat($path);
    die "$label changed during immutable read\n"
        unless same_stat(\@opened, \@after_fh)
            && same_stat(\@opened, \@after_path)
            && $total == $opened[7];
    my $observed = $digest->hexdigest;
    $immutable_snapshot{$path} = {stat => [@after_path], sha256 => $observed,
        limit => $limit, label => $label} unless $REVALIDATING_INPUTS;
    return ($retain ? $bytes : undef, $observed);
}

sub wait_at_prepublication_test_boundary {
    return unless $ENV{PHASE36_ASSEMBLER_TEST_PREPUBLICATION_BOUNDARY};
    die "Prepublication boundary is test-only\n" unless $ENV{HARNESS_ACTIVE};
    my $ready = "$output_absolute.validation-ready";
    my $continue = "$output_absolute.validation-continue";
    sysopen my $fh, $ready, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot publish validation-ready marker: $!\n";
    print {$fh} "ready\n" or die "Cannot write validation-ready marker: $!\n";
    close $fh or die "Cannot close validation-ready marker: $!\n";
    for (1 .. 500) {
        return if -f $continue;
        select undef, undef, undef, 0.01;
    }
    die "Timed out waiting at prepublication test boundary\n";
}

sub revalidate_immutable_inputs {
    local $REVALIDATING_INPUTS = 1;
    for my $path (sort keys %immutable_snapshot) {
        my $snapshot = $immutable_snapshot{$path};
        my @now = lstat($path);
        die "$snapshot->{label} changed before envelope publication\n"
            unless same_stat($snapshot->{stat}, \@now);
        my (undef, $sha) = read_immutable_bounded($path, $snapshot->{limit},
            "$snapshot->{label} final revalidation", 0);
        die "$snapshot->{label} changed before envelope publication\n"
            unless $sha eq $snapshot->{sha256};
    }
}

sub same_stat {
    my ($left, $right) = @_;
    return 0 unless @$left && @$right;
    for my $index (0, 1, 2, 7, 9, 10) {
        return 0 unless $left->[$index] == $right->[$index];
    }
    return 1;
}

sub reject_duplicate_json_keys {
    my ($bytes, $label) = @_;
    my $position = 0;
    scan_json_value($bytes, \$position, $label);
    skip_json_space($bytes, \$position);
    die "$label JSON has trailing content\n" unless $position == length($bytes);
}

sub scan_json_value {
    my ($bytes, $position, $label) = @_;
    skip_json_space($bytes, $position);
    die "$label JSON is truncated\n" if $$position >= length($bytes);
    my $char = substr($bytes, $$position, 1);
    if ($char eq '{') {
        ++$$position;
        skip_json_space($bytes, $position);
        return ++$$position if substr($bytes, $$position, 1) eq '}';
        my %seen;
        while (1) {
            skip_json_space($bytes, $position);
            my $raw = scan_json_string($bytes, $position, $label);
            my $key = eval { JSON::PP->new->utf8->decode($raw) };
            die "$label JSON has an invalid object key\n" if $@;
            die "$label JSON has duplicate object key '$key'\n" if $seen{$key}++;
            skip_json_space($bytes, $position);
            die "$label JSON object is missing ':'\n"
                unless substr($bytes, $$position, 1) eq ':';
            ++$$position;
            scan_json_value($bytes, $position, $label);
            skip_json_space($bytes, $position);
            my $next = substr($bytes, $$position, 1);
            return ++$$position if $next eq '}';
            die "$label JSON object is missing ','\n" unless $next eq ',';
            ++$$position;
        }
    }
    if ($char eq '[') {
        ++$$position;
        skip_json_space($bytes, $position);
        return ++$$position if substr($bytes, $$position, 1) eq ']';
        while (1) {
            scan_json_value($bytes, $position, $label);
            skip_json_space($bytes, $position);
            my $next = substr($bytes, $$position, 1);
            return ++$$position if $next eq ']';
            die "$label JSON array is missing ','\n" unless $next eq ',';
            ++$$position;
        }
    }
    if ($char eq '"') {
        scan_json_string($bytes, $position, $label);
        return;
    }
    my $tail = substr($bytes, $$position);
    if ($tail =~ /\A(?:true|false|null)/) {
        $$position += length($&);
        return;
    }
    if ($tail =~ /\A-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/) {
        $$position += length($&);
        return;
    }
    die "$label JSON has invalid syntax near byte $$position\n";
}

sub scan_json_string {
    my ($bytes, $position, $label) = @_;
    my $start = $$position;
    die "$label JSON expected a string\n"
        unless substr($bytes, $$position, 1) eq '"';
    ++$$position;
    while ($$position < length($bytes)) {
        my $char = substr($bytes, $$position, 1);
        if ($char eq '"') {
            ++$$position;
            return substr($bytes, $start, $$position - $start);
        }
        die "$label JSON string contains a control byte\n" if ord($char) < 0x20;
        if ($char eq '\\') {
            ++$$position;
            die "$label JSON string has a truncated escape\n"
                if $$position >= length($bytes);
            my $escape = substr($bytes, $$position, 1);
            if ($escape eq 'u') {
                die "$label JSON string has an invalid Unicode escape\n"
                    unless substr($bytes, $$position + 1, 4) =~ /\A[0-9A-Fa-f]{4}\z/;
                $$position += 5;
                next;
            }
            die "$label JSON string has an invalid escape\n"
                unless $escape =~ /["\\\/bfnrt]/;
        }
        ++$$position;
    }
    die "$label JSON string is truncated\n";
}

sub skip_json_space {
    my ($bytes, $position) = @_;
    ++$$position while $$position < length($bytes)
        && substr($bytes, $$position, 1) =~ /[\x20\x09\x0a\x0d]/;
}

sub publish_exclusive_atomic {
    my ($path, $contents) = @_;
    my $stage = "$path.stage.$$";
    my $fh;
    sysopen($fh, $stage, O_CREAT | O_EXCL | O_WRONLY, 0600)
        or die "Cannot create private stage $stage: $!\n";
    my $ok = eval {
        print {$fh} $contents or die "Cannot write private stage $stage: $!\n";
        $fh->flush or die "Cannot flush private stage $stage: $!\n";
        $fh->sync or die "Cannot fsync private stage $stage: $!\n";
        my @stage_before = stat($fh);
        close($fh) or die "Cannot close private stage $stage: $!\n";
        undef $fh;
        link($stage, $path)
            or die "Cannot exclusively publish $path: $!\n";
        my @published = lstat($path);
        die "Published envelope identity differs from durable stage\n"
            unless @published && $stage_before[0] == $published[0]
                && $stage_before[1] == $published[1]
                && $stage_before[7] == $published[7];
        my (undef, $published_sha) = read_immutable_bounded($path,
            $MAX_JSON_BYTES, 'published final envelope', 0);
        die "Published envelope bytes differ from durable stage\n"
            unless $published_sha eq Digest::SHA::sha256_hex($contents)
                && $published[7] == length($contents);
        sysopen(my $directory, dirname($path), O_RDONLY)
            or die "Cannot open final envelope parent directory: $!\n";
        $directory->sync
            or die "Cannot fsync final envelope parent directory: $!\n";
        close $directory
            or die "Cannot close final envelope parent directory: $!\n";
        1;
    };
    my $error = $@;
    close $fh if $fh;
    unlink $stage if -e $stage;
    die $error unless $ok;
}

sub number {
    my ($value) = @_;
    return 0 unless defined($value) && !ref($value)
        && $value =~ /\A-?(?:\d+(?:\.\d*)?|\.\d+)\z/;
    (my $digits = $value) =~ s/[^0-9]//g;
    return 0 if length($digits) > $MAX_DECIMAL_DIGITS;
    my $numeric = 0 + $value;
    return $numeric >= -$MAX_COUNT && $numeric <= $MAX_COUNT;
}

sub count_number {
    my ($value) = @_;
    return defined($value) && !ref($value)
        && $value =~ /\A(?:0|[1-9]\d*)\z/
        && length($value) <= $MAX_DECIMAL_DIGITS
        && $value <= $MAX_COUNT;
}

sub whole_number {
    my ($value) = @_;
    return defined($value) && !ref($value)
        && $value =~ /\A(?:0|[1-9]\d*)\z/
        && length($value) <= $MAX_DECIMAL_DIGITS
        && $value <= $MAX_BLOB_BYTES;
}

sub size_number {
    my ($value) = @_;
    return defined($value) && !ref($value)
        && $value =~ /\A(?:0|[1-9]\d*)\z/
        && length($value) <= $MAX_DECIMAL_DIGITS
        && $value <= $MAX_BLOB_BYTES;
}

sub true_value {
    my ($value) = @_;
    return defined($value) && JSON::PP::is_bool($value) && $value;
}

sub false_value {
    my ($value) = @_;
    return defined($value) && JSON::PP::is_bool($value) && !$value;
}

sub boolean_value {
    my ($value) = @_;
    return true_value($value) || false_value($value);
}

sub median {
    my ($values) = @_;
    my @sorted = sort { $a <=> $b } @$values;
    my $middle = int(@sorted / 2);
    return @sorted % 2 ? $sorted[$middle]
        : ($sorted[$middle - 1] + $sorted[$middle]) / 2;
}

sub canonical { return JSON::PP->new->canonical->encode($_[0]); }

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: assemble_phase36_acceptance_envelope.pl --authority FILE
    --expected-authority-sha256 SHA256 --expected-candidate SHA
    --expected-baseline SHA256 --expected-perl5 SHA --expected-runner SHA
    --expected-jperl-sha256 SHA256 --expected-jar-sha256 SHA256
    --expected-sbom-sha256 SHA256 --output FILE [OPTIONS]

Consume one authority-selected identity and ten hashed structured lane artifacts,
then exclusively and atomically publish the schema-v1 Phase 36 acceptance
envelope. The assembler validates producer identities and failure boundaries;
the A232 release wrapper remains the final strict authority.

Options:
  --requirements FILE       ten-gate policy (default checked-in policy)
  --authority FILE          authority selection manifest (required)
  --expected-authority-sha256 SHA256 trusted authority-manifest identity
  --expected-requirements-sha256 SHA256 trusted acceptance-policy identity
  --expected-candidate SHA  exact frozen source commit (required)
  --expected-baseline SHA   exact immutable baseline hash (required)
  --expected-perl5 SHA      trusted latest-Perl checkout commit
  --expected-runner SHA     trusted runner/source commit
  --expected-jperl-sha256 SHA256 trusted launcher identity
  --expected-jar-sha256 SHA256 trusted candidate JAR identity
  --expected-sbom-sha256 SHA256 trusted candidate SBOM identity
  --output FILE             new output beside the authority manifest (required)
  --help                    show this help
USAGE
    exit $status;
}
