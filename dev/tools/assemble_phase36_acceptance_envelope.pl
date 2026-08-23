#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA;
use Fcntl qw(O_CREAT O_EXCL O_WRONLY);
use File::Basename qw(basename dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $MAX_JSON_BYTES = 64 * 1024 * 1024;
my ($authority_path, $requirements_path, $expected_candidate,
    $expected_baseline, $output, $help);
$requirements_path = 'dev/tools/phase36_acceptance_requirements.json';
GetOptions(
    'authority=s' => \$authority_path,
    'requirements=s' => \$requirements_path,
    'expected-candidate=s' => \$expected_candidate,
    'expected-baseline=s' => \$expected_baseline,
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
die "--output is required\n" unless defined $output && length $output;

my $authority = load_json($authority_path, 'authority');
my $requirements = load_json($requirements_path, 'requirements');
die "Authority schema_version must be 1\n"
    unless ($authority->{schema_version} // 0) == 1;
die "Authority kind must be phase36-envelope-authority\n"
    unless ($authority->{kind} // '') eq 'phase36-envelope-authority';
die "Authority mode must be acceptance\n"
    unless ($authority->{mode} // '') eq 'acceptance';
die "Requirements schema_version must be 1\n"
    unless ($requirements->{schema_version} // 0) == 1;
die "Requirements baseline differs from --expected-baseline\n"
    unless ($requirements->{baseline_sha256} // '') eq $expected_baseline;

my $identity = validate_global_identity($authority->{identity});
die "Authority source differs from --expected-candidate\n"
    unless $identity->{source_commit} eq $expected_candidate;
die "Authority baseline differs from --expected-baseline\n"
    unless $identity->{baseline_sha256} eq $expected_baseline;
my $prerequisite = validate_prerequisite($authority->{prerequisites},
    $identity, dirname(File::Spec->rel2abs($authority_path)));

my @required = @{$requirements->{required_gates} // []};
die "Requirements must contain exactly ten gates\n" unless @required == 10;
my %required = map { ($_->{id} // '') => ($_->{kind} // '') } @required;
my @expected_ids = qw(ledger jvm interpreter direct-thread cpan performance
    packaging notice-license make ci);
die "Requirements gate set is not the Phase 36 ten-gate set\n"
    unless canonical([sort keys %required]) eq canonical([sort @expected_ids]);

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
        "gate $gate artifact");
    my $cache_key = join("\0", $producer, $artifact->{absolute},
        $artifact->{sha256});
    my $document = $producer_document{$cache_key} //=
        load_json($artifact->{absolute}, "gate $gate producer artifact");
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
    $authority_root);
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
my $package = validate_simple_completion($selection{packaging}{document},
    'packaging', $selection{packaging}{producer}, $identity);
validate_lane_identity($package->{identity}, $identity,
    qw(source_commit jar_sha256 sbom_sha256));
die "Packaging missing or duplicate entry counts are missing or nonzero\n"
    unless number($package->{missing_entries}) && $package->{missing_entries} == 0
        && number($package->{duplicate_entries})
        && $package->{duplicate_entries} == 0;
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
    $requirements, $selection{cpan}{artifact}{absolute});
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

my $performance = validate_performance($selection{performance}{document},
    $identity, $requirements, $authority_root);
$gates{performance} = gate_record($selection{performance},
    { source_commit => $identity->{source_commit} }, $performance);

my $notice = validate_notice($selection{'notice-license'}{document}, $identity);
$gates{'notice-license'} = gate_record($selection{'notice-license'},
    { source_commit => $identity->{source_commit} }, $notice);

my $make = validate_simple_completion($selection{make}{document}, 'make',
    $selection{make}{producer}, $identity);
die "make warnings or failures are missing or nonzero\n"
    unless number($make->{warnings}) && $make->{warnings} == 0
        && number($make->{failures}) && $make->{failures} == 0;
$gates{make} = gate_record($selection{make},
    { source_commit => $identity->{source_commit} }, {
        passed => JSON::PP::true,
        warnings => 0 + $make->{warnings}, failures => 0 + $make->{failures},
    });

my $ci = validate_simple_completion($selection{ci}{document}, 'ci',
    $selection{ci}{producer}, $identity);
my $platforms = $ci->{platforms};
die "CI platforms must be an object\n" unless ref($platforms) eq 'HASH';
for my $platform (@{$requirements->{required_ci_platforms} // []}) {
    my $result = $platforms->{$platform};
    die "CI platform $platform is missing\n" unless ref($result) eq 'HASH';
    die "CI platform $platform did not succeed\n"
        unless ($result->{status} // '') eq 'success';
    die "CI platform $platform used stale source\n"
        unless ($result->{source_commit} // '') eq $identity->{source_commit};
}
die "CI platform set differs from policy\n"
    unless canonical([sort keys %$platforms]) eq canonical(
        [sort @{$requirements->{required_ci_platforms} // []}]);
$gates{ci} = gate_record($selection{ci},
    { source_commit => $identity->{source_commit} }, { platforms => $platforms });

my $envelope = {
    schema_version => 1,
    mode => 'acceptance',
    identity => $identity,
    prerequisites => { perl5_sync => $prerequisite },
    gates => \%gates,
};
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
    my ($selection, $identity, $root) = @_;
    my $document = $selection->{document};
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
    my $producer_identity = $document->{identity};
    die "Regex producer identity is missing\n"
        unless ref($producer_identity) eq 'HASH';
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
    }
    my $statuses = $document->{exit_statuses};
    die "Regex producer exit statuses are missing\n" unless ref($statuses) eq 'HASH';
    die "Regex producer has incomplete or non-pass commands\n"
        if !keys(%$statuses) || grep { !number($statuses->{$_}) || $statuses->{$_} != 0 }
            keys %$statuses;
    reject_completion_flags($document, 'regex producer');
    my $artifacts = validate_artifact_map($document->{artifacts}, $root,
        'regex producer');
    for my $name (qw(regex-ledger.json jvm-results.json interpreter-results.json
            jvm-comparison.json interpreter-comparison.json)) {
        die "Regex producer artifact is missing: $name\n" unless $artifacts->{$name};
    }
    my $ledger = load_json($artifacts->{'regex-ledger.json'}{absolute}, 'regex ledger');
    my $runner_files = $document->{expected_files};
    die "Regex producer discovered file count is missing or zero\n"
        unless number($runner_files) && $runner_files > 0;
    die "Regex ledger discovered count differs from producer\n"
        unless number($ledger->{summary}{runner_files})
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
        unresolved_references => 0 + ($ledger->{summary}{unresolved_references} // 0),
        missing_files => 0 + ($ledger->{summary}{missing_files} // 0),
    });
    for my $backend (qw(jvm interpreter)) {
        my $runner = load_json($artifacts->{"$backend-results.json"}{absolute},
            "$backend runner results");
        die "$backend runner result set is missing\n"
            unless ref($runner->{results}) eq 'HASH';
        die "$backend runner discovered count is stale\n"
            unless keys(%{$runner->{results}}) == $runner_files;
        for my $file (keys %{$runner->{results}}) {
            my $row = $runner->{results}{$file};
            die "$backend runner row is malformed: $file\n"
                unless ref($row) eq 'HASH';
            die "$backend runner row is incomplete, timed out, or zero-TAP: $file\n"
                unless ($row->{status} // '') =~ /\A(?:pass|partial|fail)\z/
                    && number($row->{total_tests}) && $row->{total_tests} > 0
                    && (!exists($row->{exit_code})
                        || number($row->{exit_code}) && $row->{exit_code} == 0)
                    && !true_value($row->{timeout})
                    && !true_value($row->{truncated})
                    && !true_value($row->{execution_error})
                    && (!number($row->{planned_tests})
                        || !number($row->{actual_tests_run})
                        || $row->{actual_tests_run} >= $row->{planned_tests});
        }
        my $comparison = load_json(
            $artifacts->{"$backend-comparison.json"}{absolute},
            "$backend comparison");
        my $summary = $comparison->{summary};
        die "$backend comparison summary is missing\n" unless ref($summary) eq 'HASH';
        my $candidate_files = $summary->{candidate_files};
        die "$backend comparison uses a stale discovered count\n"
            unless number($candidate_files) && $candidate_files == $runner_files
                && number($comparison->{expected_files})
                && $comparison->{expected_files} == $runner_files;
        for my $field (qw(regressions missing_files zero_tap truncated
                execution_issues new_invalid)) {
            die "$backend comparison $field is missing\n"
                unless ref($comparison->{$field}) eq 'ARRAY';
        }
        die "$backend comparison did not pass\n"
            if grep { @{$comparison->{$_}} }
                qw(regressions missing_files zero_tap truncated execution_issues new_invalid);
        $result{$backend} = {
            expected_files => 0 + $runner_files,
            candidate_files => 0 + $candidate_files,
            regressions => 0, missing_files => 0, zero_tap => 0,
            timeouts => 0, truncated => 0, execution_issues => 0,
            wrong_executable => 0, wrong_commit => 0,
        };
    }
    return \%result;
}

sub validate_direct_thread {
    my ($document, $identity) = @_;
    die "Direct/thread schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'direct-thread';
    die "Direct/thread producer did not verify\n" unless true_value($document->{verified});
    validate_lane_identity($document->{identity}, $identity,
        qw(source_commit runner_commit jperl_sha256));
    reject_completion_flags($document, 'direct/thread producer');
    my $details = $document->{details};
    die "Direct/thread details are missing\n" unless ref($details) eq 'HASH';
    for my $field (qw(expected_pairs actual_pairs expected_modes actual_modes
            expected_thread_only actual_thread_only expected_thread_only_modes
            actual_thread_only_modes mismatches missing zero_tap timeouts
            truncated execution_issues)) {
        die "Direct/thread $field is missing\n" unless number($details->{$field});
    }
    die "Direct/thread producer reports incomplete or failing evidence\n"
        if $details->{expected_pairs} <= 0
            || $details->{actual_pairs} != $details->{expected_pairs}
            || $details->{expected_modes} != 4 || $details->{actual_modes} != 4
            || $details->{actual_thread_only} != $details->{expected_thread_only}
            || $details->{expected_thread_only_modes} != 2
            || $details->{actual_thread_only_modes} != 2
            || grep { $details->{$_} != 0 }
                qw(mismatches missing zero_tap timeouts truncated execution_issues);
    return { map { $_ => 0 + $details->{$_} } qw(expected_pairs actual_pairs
        expected_modes actual_modes expected_thread_only actual_thread_only
        expected_thread_only_modes actual_thread_only_modes mismatches missing
        zero_tap timeouts truncated execution_issues) };
}

sub validate_cpan {
    my ($document, $identity, $requirements, $path) = @_;
    die "CPAN producer artifact must be cpan-acceptance.json\n"
        unless basename($path) eq 'cpan-acceptance.json';
    die "CPAN producer schema_version must be 1 or 2\n"
        unless ($document->{schema_version} // 0) == 1
            || ($document->{schema_version} // 0) == 2;
    die "CPAN producer is not a passing acceptance run\n"
        unless ($document->{mode} // '') eq 'acceptance'
            && ($document->{status} // '') eq 'pass';
    validate_lane_identity($document->{identity}, $identity,
        qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256
            sbom_sha256));
    die "CPAN policy identity is wrong\n"
        unless ($document->{identity}{policy_sha256} // '')
            eq ($requirements->{cpan_acceptance}{policy_sha256} // '');
    reject_completion_flags($document, 'CPAN producer');
    my @expected = @{$requirements->{cpan_acceptance}{expected_targets} // []};
    die "CPAN target set differs from policy\n"
        unless canonical([sort @{$document->{expected_targets} // []}])
            eq canonical([sort @expected]);
    my $results = $document->{results};
    die "CPAN results are missing\n" unless ref($results) eq 'HASH';
    my %summary;
    for my $target (@expected) {
        my $result = $results->{$target};
        die "CPAN target $target is missing or did not pass\n"
            unless ref($result) eq 'HASH' && ($result->{status} // '') eq 'pass'
                && number($result->{total_tests}) && $result->{total_tests} > 0;
        my $modes = $result->{modes};
        die "CPAN target $target modes are missing\n" unless ref($modes) eq 'HASH';
        for my $mode (@{$requirements->{cpan_acceptance}{required_modes}}) {
            my $entry = $modes->{$mode};
            die "CPAN target $target $mode did not pass\n"
                unless ref($entry) eq 'HASH' && ($entry->{status} // '') eq 'pass'
                    && number($entry->{total_tests}) && $entry->{total_tests} > 0
                    && number($entry->{exit_code}) && $entry->{exit_code} == 0
                    && number($entry->{signal}) && $entry->{signal} == 0
                    && false_value($entry->{timeout})
                    && false_value($entry->{execution_error})
                    && false_value($entry->{zero_tap})
                    && false_value($entry->{malformed})
                    && false_value($entry->{truncated});
            validate_lane_identity($entry->{identity}, $identity,
                qw(source_commit runner_commit perl5_commit jperl_sha256
                    jar_sha256 sbom_sha256));
            die "CPAN target $target $mode has failures or warnings\n"
                unless number($entry->{failures}) && $entry->{failures} == 0
                    && ref($entry->{unapproved_warnings}) eq 'ARRAY'
                    && !@{$entry->{unapproved_warnings}}
                    && ref($entry->{warning_diagnostics}) eq 'ARRAY'
                    && !@{$entry->{warning_diagnostics}};
        }
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
    my $seal = "$path.sha256";
    die "CPAN producer seal is missing\n" unless -f $seal && -s $seal;
    my $seal_text = read_bounded($seal, 1024, 'CPAN seal');
    die "CPAN producer seal is malformed or stale\n"
        unless $seal_text =~ /\A([0-9a-f]{64})\s+cpan-acceptance\.json\s*\z/
            && $1 eq sha256_file($path);
    return { expected_targets => [@expected], results => \%summary,
        excluded_audits => [] };
}

sub validate_performance {
    my ($document, $identity, $requirements, $root) = @_;
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
    my $ordinary_descriptor = ref($document->{ordinary}) eq 'HASH'
        ? $document->{ordinary}{artifact} : undef;
    my $ordinary_artifact = validate_descriptor($ordinary_descriptor, $root,
        'final performance ordinary artifact', 1);
    my $ordinary = load_json($ordinary_artifact->{absolute},
        'final ordinary performance');
    return validate_ordinary_performance($ordinary, $identity, $requirements,
        $root);
}

sub validate_ordinary_performance {
    my ($document, $identity, $requirements, $root) = @_;
    die "Ordinary performance schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'performance';
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
    my $minimum = $requirements->{minimum_performance_samples} // 5;
    for my $field (qw(baseline_seconds candidate_seconds)) {
        die "Performance $field is incomplete\n"
            unless ref($document->{$field}) eq 'ARRAY'
                && @{$document->{$field}} >= $minimum
                && !grep { !number($_) || $_ <= 0 } @{$document->{$field}};
    }
    die "Performance samples were not alternated\n"
        unless true_value($document->{alternating_order});
    my $order = $document->{execution_order};
    die "Performance execution order is missing or not alternating\n"
        unless ref($order) eq 'ARRAY'
            && @$order == 2 * @{$document->{baseline_seconds}}
            && !grep {
                $order->[$_] ne ($_ % 2 ? 'candidate' : 'baseline')
            } 0 .. $#$order;
    validate_nested_artifacts($document->{artifacts}, $root,
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
        'Perl5 sync prerequisite artifact');
    my $document = load_json($artifact->{absolute}, 'Perl5 sync prerequisite');
    die "Perl5 sync prerequisite schema, kind, or status is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'phase36-perl5-sync-evidence'
            && ($document->{status} // '') eq 'pass';
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
            && number($document->{command}{exit_code})
            && $document->{command}{exit_code} == 0
            && number($document->{command}{signal})
            && $document->{command}{signal} == 0
            && false_value($document->{command}{timeout});
    die "Perl5 sync prerequisite is partial or not idempotent\n"
        unless ref($document->{sync_markers}) eq 'HASH'
            && number($document->{sync_markers}{pass_count})
            && $document->{sync_markers}{pass_count} == 2
            && true_value($document->{sync_markers}{second_pass_seen})
            && true_value($document->{sync_markers}{idempotence_verified});
    return { producer => $entry->{producer}, artifact => {
        path => $artifact->{path}, sha256 => $artifact->{sha256} },
        source_commit => $identity->{source_commit},
        perl5_commit => $identity->{perl5_commit}, verified => JSON::PP::true };
}

sub validate_notice {
    my ($document, $identity) = @_;
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
            unless number($document->{$field}) && $document->{$field} == 0;
    }
    return { verified => JSON::PP::true,
        map { $_ => 0 } qw(missing_notices changed_notices missing_licenses
            changed_licenses) };
}

sub validate_simple_completion {
    my ($document, $kind, $producer, $identity) = @_;
    die "$kind producer schema_version or kind is wrong\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq $kind;
    die "$kind producer name is not pinned in its record\n"
        unless ($document->{producer} // '') eq $producer;
    die "$kind producer did not verify\n" unless true_value($document->{verified});
    validate_lane_identity($document->{identity}, $identity, qw(source_commit));
    my $completion = $document->{completion};
    die "$kind completion record is missing\n" unless ref($completion) eq 'HASH';
    die "$kind completion is non-pass, incomplete, timed out, or review-stopped\n"
        unless number($completion->{exit_code}) && $completion->{exit_code} == 0
            && number($completion->{signal}) && $completion->{signal} == 0
            && false_value($completion->{timeout})
            && false_value($completion->{incomplete})
            && false_value($completion->{review_stop});
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
        $validated{$name} = validate_descriptor($map->{$name}, $root,
            "$label artifact $name", 1);
    }
    return \%validated;
}

sub validate_nested_artifacts {
    my ($value, $root, $label) = @_;
    if (ref($value) eq 'HASH') {
        if (exists($value->{path}) || exists($value->{sha256})) {
            my $validated = validate_descriptor({
                path => $value->{path}, sha256 => $value->{sha256},
            }, $root, $label, 1);
            die "$label size is stale\n"
                if exists($value->{size})
                    && (!number($value->{size})
                        || $value->{size} != -s $validated->{absolute});
            my %allowed = map { $_ => 1 } qw(path sha256 size);
            my @extra = grep { !$allowed{$_} } keys %$value;
            die "$label has unsupported fields: " . join(', ', sort @extra) . "\n"
                if @extra;
            return;
        }
        validate_nested_artifacts($value->{$_}, $root, "$label $_")
            for sort keys %$value;
        return;
    }
    if (ref($value) eq 'ARRAY') {
        validate_nested_artifacts($value->[$_], $root, "$label $_")
            for 0 .. $#$value;
        return;
    }
    die "$label is not a hashed artifact descriptor\n";
}

sub validate_descriptor {
    my ($descriptor, $root, $label, $allow_absolute) = @_;
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
        unless path_inside($absolute, $root);
    my $sha = $descriptor->{sha256} // '';
    die "$label SHA-256 is malformed\n" unless $sha =~ /\A[0-9a-f]{64}\z/;
    die "$label hash mismatch\n" unless sha256_file($absolute) eq $sha;
    return { path => File::Spec->abs2rel($absolute, $root),
        absolute => $absolute, sha256 => $sha };
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
    my ($path, $label) = @_;
    my $bytes = read_bounded($path, $MAX_JSON_BYTES, $label);
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in $path\n"
        unless $document && ref($document) eq 'HASH';
    return $document;
}

sub read_bounded {
    my ($path, $limit, $label) = @_;
    die "$label is a symlink\n" if -l $path;
    my $size = -s $path;
    die "$label is missing or empty\n" unless defined($size) && $size > 0;
    die "$label exceeds bounded read limit\n" if $size > $limit;
    open my $fh, '<:raw', $path or die "Cannot read $label $path: $!\n";
    my $bytes = do { local $/; <$fh> };
    close $fh or die "Cannot close $label $path: $!\n";
    return $bytes;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!\n";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $sha->hexdigest;
}

sub publish_exclusive_atomic {
    my ($path, $contents) = @_;
    my $stage = "$path.stage.$$";
    my $fh;
    sysopen($fh, $stage, O_CREAT | O_EXCL | O_WRONLY, 0600)
        or die "Cannot create private stage $stage: $!\n";
    my $ok = eval {
        print {$fh} $contents or die "Cannot write private stage $stage: $!\n";
        close($fh) or die "Cannot close private stage $stage: $!\n";
        undef $fh;
        link($stage, $path)
            or die "Cannot exclusively publish $path: $!\n";
        1;
    };
    my $error = $@;
    close $fh if $fh;
    unlink $stage if -e $stage;
    die $error unless $ok;
}

sub number {
    my ($value) = @_;
    return defined($value) && !ref($value)
        && $value =~ /\A-?(?:\d+(?:\.\d*)?|\.\d+)\z/;
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

sub canonical { return JSON::PP->new->canonical->encode($_[0]); }

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: assemble_phase36_acceptance_envelope.pl --authority FILE
    --expected-candidate SHA --expected-baseline SHA256 --output FILE [OPTIONS]

Consume one authority-selected identity and ten hashed structured lane artifacts,
then exclusively and atomically publish the schema-v1 Phase 36 acceptance
envelope. The assembler validates producer identities and failure boundaries;
the A232 release wrapper remains the final strict authority.

Options:
  --requirements FILE       ten-gate policy (default checked-in policy)
  --authority FILE          authority selection manifest (required)
  --expected-candidate SHA  exact frozen source commit (required)
  --expected-baseline SHA   exact immutable baseline hash (required)
  --output FILE             new output beside the authority manifest (required)
  --help                    show this help
USAGE
    exit $status;
}
