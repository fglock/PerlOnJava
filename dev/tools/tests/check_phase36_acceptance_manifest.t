use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_acceptance_manifest.pl');
my $requirements = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_acceptance_requirements.json');
my $cpan_policy = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_cpan_targets.json');
my $temporary = tempdir(CLEANUP => 1);
my $json = JSON::PP->new->canonical->pretty;

my $source = '1' x 40;
my $perl5 = '2' x 40;
my $jperl_sha = '3' x 64;
my $jar_sha = '4' x 64;
my $sbom_sha = '5' x 64;
my $baseline_sha =
    '9adef3dde92414bee49cbb571f65e8fcc705e034189de37d6a6136672bc67211';
my $cpan_policy_sha =
    'b35b479d260550f933c144205c4c0b940e4b3df8731609ff215f687cc1a74872';
my @cpan_targets = qw(DBIx::Class DateTime Moo Regexp::Common String::Random
    Template Type::Tiny WWW::Mechanize);
my @cpan_modes = qw(jvm interpreter);
my @gate_ids = qw(ledger jvm interpreter direct-thread cpan performance
    packaging notice-license make ci);
is(sha256_file($cpan_policy), $cpan_policy_sha,
    'sealed acceptance requirement pins the exact checked-in CPAN policy');

my %artifact;
for my $id (@gate_ids) {
    my $name = "$id.artifact";
    my $path = File::Spec->catfile($temporary, $name);
    my $contents = "retained $id evidence\n";
    write_file($path, $contents);
    $artifact{$id} = { path => $name, sha256 => sha256_hex($contents) };
}
$artifact{cpan} = create_cpan_fixture('valid');
my %object_artifact;
for my $kind (qw(current exact-parent)) {
    my $name = "object-insideout-$kind.artifact";
    my $contents = "retained Object::InsideOut $kind evidence\n";
    write_file(File::Spec->catfile($temporary, $name), $contents);
    $object_artifact{$kind} = {
        path => $name,
        sha256 => sha256_hex($contents),
    };
}

my $valid = valid_evidence();
my ($status, $report) = run_check('valid', $valid, 'strict');
is($status, 0, 'complete exact-identity evidence passes strict mode');
ok($report->{summary}{authoritative}, 'strict complete evidence is authoritative');
is_deeply($report->{summary}, {
        authoritative => JSON::PP::true,
        failed => 0,
        passed => 10,
        pending => 0,
        required_gates => 10,
    }, 'all ten release gates pass');

my $wrong_baseline = clone($valid);
$wrong_baseline->{identity}{baseline_sha256} = '6' x 64;
for my $gate (qw(jvm interpreter cpan)) {
    $wrong_baseline->{gates}{$gate}{identity}{baseline_sha256} = '6' x 64;
}
my ($wrong_baseline_status, $wrong_baseline_report) = run_check(
    'wrong-required-baseline', $wrong_baseline, 'strict');
is($wrong_baseline_status, 1,
    'internally consistent evidence for another baseline is rejected');
like(join("\n", report_issues($wrong_baseline_report)),
    qr/evidence baseline does not match the required baseline/,
    'baseline substitution has an exact global diagnostic');

my $latest_count = clone($valid);
$latest_count->{gates}{ledger}{details}{runner_files} = 1;
$latest_count->{gates}{jvm}{details}{expected_files} = 1;
$latest_count->{gates}{jvm}{details}{candidate_files} = 1;
$latest_count->{gates}{interpreter}{details}{expected_files} = 1;
$latest_count->{gates}{interpreter}{details}{candidate_files} = 1;
my ($latest_count_status) = run_check(
    'latest-count-is-not-pinned', $latest_count, 'strict');
is($latest_count_status, 0,
    'complete current ledger count is accepted without pinning an upstream size');

my $zero_count = clone($latest_count);
$zero_count->{gates}{ledger}{details}{runner_files} = 0;
my ($zero_count_status, $zero_count_report) = run_check(
    'zero-current-ledger', $zero_count, 'strict');
is($zero_count_status, 1, 'zero-file current ledger is rejected');
like(join("\n", report_issues($zero_count_report)),
    qr/ledger runner file count is missing or zero/,
    'zero-file ledger has an exact diagnostic');

my @invalid = (
    ['missing', sub { delete $_[0]{gates}{jvm} }, 'required gate evidence is missing'],
    ['zero-tap', sub { $_[0]{gates}{jvm}{details}{zero_tap} = 1 },
        'zero_tap is missing or nonzero'],
    ['timeout', sub { $_[0]{gates}{interpreter}{details}{timeouts} = 1 },
        'timeouts is missing or nonzero'],
    ['truncated', sub { $_[0]{gates}{interpreter}{details}{truncated} = 1 },
        'truncated is missing or nonzero'],
    ['wrong-executable', sub {
        $_[0]{gates}{'direct-thread'}{identity}{jperl_sha256} = '9' x 64
    }, 'gate executable identity is wrong'],
    ['wrong-commit', sub {
        $_[0]{gates}{make}{identity}{source_commit} = '9' x 40
    }, 'gate source commit is wrong'],
    ['missing-thread-only', sub {
        $_[0]{gates}{'direct-thread'}{details}{actual_thread_only} = 0
    }, 'thread-only test count is incomplete'],
    ['baseline-regression', sub { $_[0]{gates}{jvm}{details}{regressions} = 1 },
        'regressions is missing or nonzero'],
    ['missing-artifact', sub {
        $_[0]{gates}{cpan}{artifact}{path} = 'does-not-exist.artifact'
    }, 'artifact is missing or empty'],
    ['missing-excluded-audits', sub {
        delete $_[0]{gates}{cpan}{details}{excluded_audits}
    }, 'excluded CPAN audits must be an array'],
    ['excluded-overlap', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]{target} = 'Type::Tiny'
    }, 'excluded CPAN audit overlaps passing target'],
    ['excluded-classification', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]{classification} = 'ignored'
    }, 'has unsupported classification'],
    ['excluded-passing', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]{status} = 'pass'
    }, 'has invalid status'],
    ['excluded-regex-relevant', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]{regex_relevant}
            = JSON::PP::true
    }, 'is not explicitly non-regex'],
    ['excluded-parent-delta', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]
            {exact_parent}{failure_map_identical} = JSON::PP::false
    }, 'parent failure map is not identical'],
    ['excluded-wrong-runner', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]
            {identity}{runner_commit} = '9' x 40
    }, 'runner commit differs from its source'],
    ['excluded-missing-perl5-identity', sub {
        delete $_[0]{gates}{cpan}{details}{excluded_audits}[0]
            {identity}{perl5_commit}
    }, 'perl5_commit is not a full Git SHA'],
    ['excluded-parent-artifact', sub {
        $_[0]{gates}{cpan}{details}{excluded_audits}[0]
            {exact_parent}{artifact}{path} = 'missing-parent.artifact'
    }, 'artifact is missing or empty'],
);
for my $case (@invalid) {
    my ($name, $mutate, $diagnostic) = @$case;
    my $evidence = clone($valid);
    $mutate->($evidence);
    my ($case_status, $case_report) = run_check($name, $evidence, 'strict');
    is($case_status, 1, "$name evidence is rejected in strict mode");
    like(join("\n", report_issues($case_report)), qr/\Q$diagnostic\E/,
        "$name has an exact diagnostic");
}

my @invalid_cpan = (
    ['cpan-policy-substitution', sub {
        $_[0]{identity}{policy_sha256} = 'a' x 64;
    }, 'CPAN acceptance policy identity is wrong'],
    ['cpan-source-substitution', sub {
        $_[0]{identity}{source_commit} = 'a' x 40;
        $_[0]{identity}{runner_commit} = 'a' x 40;
    }, 'CPAN acceptance source identity is wrong'],
    ['cpan-perl5-substitution', sub {
        $_[0]{identity}{perl5_commit} = 'a' x 40;
    }, 'CPAN acceptance perl5 identity is wrong'],
    ['cpan-jar-substitution', sub {
        $_[0]{identity}{jar_sha256} = 'a' x 64;
    }, 'CPAN acceptance JAR identity is wrong'],
    ['cpan-sbom-substitution', sub {
        $_[0]{identity}{sbom_sha256} = 'a' x 64;
    }, 'CPAN acceptance SBOM identity is wrong'],
    ['cpan-target-omitted', sub {
        pop @{$_[0]{expected_targets}};
        delete $_[0]{results}{'WWW::Mechanize'};
    }, 'CPAN acceptance target set is wrong'],
    ['cpan-mode-omitted', sub {
        delete $_[0]{results}{Moo}{modes}{interpreter};
    }, 'CPAN acceptance mode set is wrong'],
    ['cpan-mode-duplicated', sub {
        $_[0]{results}{Moo}{modes}{interpreter}{mode} = 'jvm';
    }, 'CPAN acceptance mode identity is wrong'],
    ['cpan-backend-selector-substituted', sub {
        $_[0]{results}{Moo}{modes}{interpreter}{environment}
            {JPERL_INTERPRETER} = undef;
    }, 'CPAN acceptance backend selector is wrong'],
    ['cpan-aggregate-failure', sub {
        $_[0]{status} = 'fail';
    }, 'CPAN acceptance aggregate did not pass'],
    ['cpan-mode-warning-metadata', sub {
        $_[0]{results}{Moo}{modes}{jvm}{unapproved_warnings}
            = ['Use of uninitialized value at Moo.pm line 1.'];
    }, 'CPAN acceptance has unapproved warnings'],
    ['cpan-warning-diagnostic-laundered', sub {
        $_[0]{results}{Moo}{modes}{jvm}{warning_diagnostics}
            = ['Use of uninitialized value at Moo.pm line 1.'];
    }, 'CPAN acceptance has warning diagnostics'],
    ['cpan-input-substitution', sub {
        $_[0]{identity}{inputs}{jar}{sha256} = 'a' x 64;
    }, 'CPAN acceptance input identity is wrong'],
    ['cpan-excluded-substitution', sub {
        $_[0]{excluded_audits} = [{ target => 'Hidden::Failure' }];
    }, 'CPAN acceptance has excluded audits'],
);
for my $case (@invalid_cpan) {
    my ($name, $mutate, $diagnostic) = @$case;
    my $evidence = clone($valid);
    $evidence->{gates}{cpan}{artifact} = create_cpan_fixture($name, $mutate);
    my ($case_status, $case_report) = run_check($name, $evidence, 'strict');
    is($case_status, 1, "$name evidence is rejected in strict mode");
    like(join("\n", report_issues($case_report)), qr/\Q$diagnostic\E/,
        "$name has an exact diagnostic");
}

{
    my $evidence = clone($valid);
    $evidence->{gates}{cpan}{details}{results}{Moo}{total_tests} = 999;
    my ($case_status, $case_report) = run_check(
        'cpan-stale-envelope-summary', $evidence, 'strict');
    is($case_status, 1, 'a stale CPAN envelope summary is rejected');
    like(join("\n", report_issues($case_report)),
        qr/CPAN gate summary differs from sealed acceptance/,
        'stale CPAN envelope summary has an exact diagnostic');
}

{
    my $name = 'cpan-raw-warning-laundered';
    my $evidence = clone($valid);
    $evidence->{gates}{cpan}{artifact} = create_cpan_fixture($name, sub {
        my ($document, $fixture) = @_;
        write_file($fixture->{raw}{Moo}{jvm},
            "ok 1 - result\n1..1\nUse of uninitialized value at Moo.pm line 1.\n"
            . "Files=1, Tests=1, 0 wallclock secs\n");
    });
    my ($case_status, $case_report) = run_check($name, $evidence, 'strict');
    is($case_status, 1, 'warning-bearing raw CPAN evidence is rejected after resealing');
    like(join("\n", report_issues($case_report)),
        qr/CPAN acceptance raw log has an unapproved warning/,
        'raw warning laundering has an exact diagnostic');
}

{
    my $name = 'cpan-raw-hash-substitution';
    my $evidence = clone($valid);
    my ($descriptor, $fixture) = create_cpan_fixture($name);
    write_file($fixture->{raw}{Moo}{jvm}, "substituted after sealing\n");
    $evidence->{gates}{cpan}{artifact} = $descriptor;
    my ($case_status, $case_report) = run_check($name, $evidence, 'strict');
    is($case_status, 1, 'a substituted CPAN raw log is rejected');
    like(join("\n", report_issues($case_report)),
        qr/CPAN acceptance raw log hash mismatch/,
        'raw-log substitution has an exact diagnostic');
}

{
    my $name = 'cpan-seal-substitution';
    my $evidence = clone($valid);
    my ($descriptor, $fixture) = create_cpan_fixture($name);
    write_file($fixture->{manifest} . '.sha256', ('a' x 64) . "  cpan-acceptance.json\n");
    $evidence->{gates}{cpan}{artifact} = $descriptor;
    my ($case_status, $case_report) = run_check($name, $evidence, 'strict');
    is($case_status, 1, 'a CPAN manifest with a substituted seal is rejected');
    like(join("\n", report_issues($case_report)),
        qr/CPAN acceptance seal does not match its manifest/,
        'seal substitution has an exact diagnostic');
}

{
    my $evidence = clone($valid);
    $evidence->{gates}{cpan}{identity}{baseline_sha256} = 'a' x 64;
    my ($case_status, $case_report) = run_check(
        'cpan-baseline-substitution', $evidence, 'strict');
    is($case_status, 1, 'CPAN evidence cannot be attached to a different baseline');
    like(join("\n", report_issues($case_report)),
        qr/CPAN gate baseline_sha256 identity is wrong/,
        'CPAN baseline substitution has an exact diagnostic');
}

my $dry = clone($valid);
$dry->{mode} = 'dry-run';
$dry->{gates}{$_}{state} = 'pending' for @gate_ids;
my ($report_status, $dry_report) = run_check('dry-report', $dry, 'report');
is($report_status, 0, 'report mode accepts incomplete pre-integration evidence');
ok(!$dry_report->{summary}{authoritative}, 'report mode is explicitly non-authoritative');
is($dry_report->{summary}{failed}, 10, 'report mode inventories all pending gate failures');
my ($strict_status) = run_check('dry-strict', $dry, 'strict');
is($strict_status, 1, 'the same dry-run evidence fails strict mode');

my $empty_report = File::Spec->catfile($temporary, 'empty-report.json');
system $^X, $tool, '--requirements', $requirements,
    '--mode', 'report', '--output', $empty_report;
is($? >> 8, 0, 'report mode works before any evidence manifest exists');
my $empty = read_json($empty_report);
is($empty->{summary}{pending}, 10, 'no-evidence report enumerates every pending gate');

done_testing;

sub valid_evidence {
    my %base_identity = (source_commit => $source);
    my %runner_identity = (%base_identity,
        runner_commit => $source, jperl_sha256 => $jperl_sha);
    my %cpan_identity = (%runner_identity,
        perl5_commit => $perl5, jar_sha256 => $jar_sha,
        sbom_sha256 => $sbom_sha, baseline_sha256 => $baseline_sha,
        policy_sha256 => $cpan_policy_sha);
    my %comparison_identity = (%runner_identity, baseline_sha256 => $baseline_sha);
    my %comparison = (
        expected_files => 623, candidate_files => 623,
        regressions => 0, missing_files => 0, zero_tap => 0,
        timeouts => 0, truncated => 0, execution_issues => 0,
        wrong_executable => 0, wrong_commit => 0,
    );
    return {
        schema_version => 1,
        mode => 'acceptance',
        identity => {
            source_commit => $source,
            perl5_commit => $perl5,
            runner_commit => $source,
            jperl_sha256 => $jperl_sha,
            jar_sha256 => $jar_sha,
            sbom_sha256 => $sbom_sha,
            baseline_sha256 => $baseline_sha,
        },
        gates => {
            ledger => gate('ledger', \%base_identity, {
                scope => 'complete', runner_files => 623,
                direct_thread_pairs => 10, thread_only_tests => 1,
                unresolved_references => 0, missing_files => 0,
            }),
            jvm => gate('jvm', \%comparison_identity, { %comparison }),
            interpreter => gate('interpreter', \%comparison_identity, { %comparison }),
            'direct-thread' => gate('direct-thread', \%runner_identity, {
                expected_pairs => 10, actual_pairs => 10,
                expected_modes => 4, actual_modes => 4,
                expected_thread_only => 1, actual_thread_only => 1,
                expected_thread_only_modes => 2, actual_thread_only_modes => 2,
                mismatches => 0, missing => 0, zero_tap => 0,
                timeouts => 0, truncated => 0, execution_issues => 0,
            }),
            cpan => gate('cpan', \%cpan_identity, {
                expected_targets => [@cpan_targets],
                results => { map { $_ => {
                    status => 'pass', total_tests => 2,
                    modes => { map { $_ => { status => 'pass' } } @cpan_modes },
                } } @cpan_targets },
                excluded_audits => [{
                    target => 'Object::InsideOut',
                    classification => 'pre-existing-non-regex',
                    reason => 'exact-parent-identical compatibility failure map',
                    status => 'fail',
                    regex_relevant => JSON::PP::false,
                    artifact => { %{ $object_artifact{current} } },
                    identity => {
                        source_commit => '8' x 40,
                        runner_commit => '8' x 40,
                        perl5_commit => '2' x 40,
                        jperl_sha256 => '9' x 64,
                    },
                    exact_parent => {
                        source_commit => '7' x 40,
                        failure_map_identical => JSON::PP::true,
                        compared_programs => 18,
                        artifact => { %{ $object_artifact{'exact-parent'} } },
                    },
                }],
            }),
            performance => gate('performance', \%base_identity, {
                baseline_seconds => [10.0, 10.2, 10.1, 10.3, 10.0],
                candidate_seconds => [9.8, 9.9, 9.7, 10.0, 9.8],
                alternating_order => JSON::PP::true,
            }),
            packaging => gate('packaging', \%base_identity, {
                verified => JSON::PP::true,
                jar_sha256 => $jar_sha, sbom_sha256 => $sbom_sha,
                missing_entries => 0, duplicate_entries => 0,
            }),
            'notice-license' => gate('notice-license', \%base_identity, {
                verified => JSON::PP::true,
                missing_notices => 0, changed_notices => 0,
                missing_licenses => 0, changed_licenses => 0,
            }),
            make => gate('make', \%base_identity, {
                passed => JSON::PP::true, warnings => 0, failures => 0,
            }),
            ci => gate('ci', \%base_identity, {
                platforms => {
                    'ubuntu-latest' => { status => 'success', source_commit => $source },
                    'windows-latest' => { status => 'success', source_commit => $source },
                },
            }),
        },
    };
}

sub gate {
    my ($id, $identity, $details) = @_;
    return {
        state => 'passed',
        artifact => { %{ $artifact{$id} } },
        identity => { %$identity },
        details => $details,
    };
}

sub run_check {
    my ($name, $evidence, $mode) = @_;
    my $evidence_path = File::Spec->catfile($temporary, "$name-evidence.json");
    my $report_path = File::Spec->catfile($temporary, "$name-report.json");
    write_file($evidence_path, $json->encode($evidence));
    system $^X, $tool,
        '--requirements', $requirements,
        '--evidence', $evidence_path,
        '--mode', $mode,
        '--expected-commit', $source,
        '--output', $report_path;
    return ($? >> 8, read_json($report_path));
}

sub report_issues {
    my ($report) = @_;
    return (@{ $report->{global_issues} },
        map { @{ $report->{gates}{$_}{issues} } } keys %{ $report->{gates} });
}

sub clone {
    return $json->decode($json->encode($_[0]));
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}

sub read_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $document = JSON::PP->new->decode(do { local $/; <$fh> });
    close $fh or die "Cannot close $path: $!\n";
    return $document;
}

sub create_cpan_fixture {
    my ($name, $mutate) = @_;
    my $directory = File::Spec->catdir($temporary, "cpan-$name");
    make_path($directory);
    my (%results, %raw, %meta_path);
    my $version_relative = 'jperl-version.log';
    write_file(File::Spec->catfile($directory, $version_relative), "jperl $source\n");
    for my $target (@cpan_targets) {
        my %modes;
        for my $mode (@cpan_modes) {
            my $slug = lc "$target-$mode";
            $slug =~ s/[^a-z0-9]+/-/g;
            $slug =~ s/^-|-$//g;
            my $run_directory = File::Spec->catdir($directory, 'runs', $slug);
            make_path($run_directory);
            my $raw_path = File::Spec->catfile($run_directory, 'raw.log');
            my $meta = File::Spec->catfile($run_directory, 'result.json');
            write_file($raw_path,
                "ok 1 - result\n1..1\nFiles=1, Tests=1, 0 wallclock secs\n");
            $raw{$target}{$mode} = $raw_path;
            $meta_path{$target}{$mode} = $meta;
            my $raw_relative = File::Spec->abs2rel($raw_path, $directory);
            my $home = File::Spec->catdir($run_directory, 'home');
            my $tmp = File::Spec->catdir($run_directory, 'tmp');
            make_path($home, $tmp);
            my $environment = {
                PERLONJAVA_JAR => '/candidate.jar',
                PERLONJAVA_HOME => $home, HOME => $home, TMPDIR => $tmp,
                PERL_MM_USE_DEFAULT => 1,
                JPERL_INTERPRETER => $mode eq 'interpreter' ? 1 : undef,
                PHASE36_CPAN_TARGET => $target,
                PHASE36_CPAN_MODE => $mode,
            };
            $modes{$mode} = {
                target => $target, mode => $mode, status => 'pass',
                argv => ['/jcpan', '-t', $target],
                environment => $environment,
                environment_sha256 => sha256_hex(
                    JSON::PP->new->canonical->encode($environment)),
                timeout => JSON::PP::false, signal => 0, exit_code => 0,
                execution_error => JSON::PP::false,
                total_tests => 1, failures => 0, skips => 0,
                zero_tap => JSON::PP::false, malformed => JSON::PP::false,
                truncated => JSON::PP::false,
                warning_diagnostics => [], unapproved_warnings => [],
                raw_log => { path => $raw_relative, sha256 => sha256_file($raw_path) },
                identity => {
                    source_commit => $source, runner_commit => $source,
                    perl5_commit => $perl5, jperl_sha256 => $jperl_sha,
                    jar_sha256 => $jar_sha, sbom_sha256 => $sbom_sha,
                },
            };
        }
        $results{$target} = {
            status => 'pass', total_tests => 2,
            timeout => JSON::PP::false, truncated => JSON::PP::false,
            execution_error => JSON::PP::false, modes => \%modes,
        };
    }
    my $document = {
        schema_version => 1, mode => 'acceptance', status => 'pass',
        expected_targets => [@cpan_targets], results => \%results,
        total_tests => 2 * @cpan_targets, excluded_audits => [],
        identity => {
            source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl_sha,
            jar_sha256 => $jar_sha, sbom_sha256 => $sbom_sha,
            policy_sha256 => $cpan_policy_sha,
            manifest_sha256 => '7' x 64, jcpan_sha256 => '8' x 64,
            inputs => {
                source => { path => '/source', commit => $source },
                perl5 => { path => '/perl5', commit => $perl5 },
                jperl => { path => '/jperl', sha256 => $jperl_sha },
                jcpan => { path => '/jcpan', sha256 => '8' x 64 },
                jar => { path => '/candidate.jar', sha256 => $jar_sha },
                sbom => { path => '/candidate-sbom.json', sha256 => $sbom_sha },
            },
        },
    };
    my $fixture = {
        directory => $directory, raw => \%raw, meta => \%meta_path,
        manifest => File::Spec->catfile($directory, 'cpan-acceptance.json'),
    };
    $mutate->($document, $fixture) if $mutate;

    my @artifacts = ({
        path => $version_relative,
        sha256 => sha256_file(File::Spec->catfile($directory, $version_relative)),
        kind => 'jperl-version',
    });
    for my $target (@{$document->{expected_targets}}) {
        for my $mode (sort keys %{$document->{results}{$target}{modes} // {}}) {
            my $mode_result = $document->{results}{$target}{modes}{$mode};
            my $raw_path = $raw{$target}{$mode};
            my $meta = $meta_path{$target}{$mode};
            $mode_result->{raw_log}{sha256} = sha256_file($raw_path);
            write_file($meta, $json->encode($mode_result));
            push @artifacts,
                { path => File::Spec->abs2rel($raw_path, $directory),
                  sha256 => sha256_file($raw_path), kind => 'raw-log' },
                { path => File::Spec->abs2rel($meta, $directory),
                  sha256 => sha256_file($meta), kind => 'mode-result' };
        }
    }
    $document->{artifacts} = \@artifacts;
    write_file($fixture->{manifest}, $json->encode($document));
    write_file($fixture->{manifest} . '.sha256',
        sha256_file($fixture->{manifest}) . "  cpan-acceptance.json\n");
    my $descriptor = {
        path => $fixture->{manifest}, sha256 => sha256_file($fixture->{manifest}),
    };
    return wantarray ? ($descriptor, $fixture) : $descriptor;
}

sub sha256_file {
    my ($path) = @_;
    return sha256_hex(read_file($path));
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $contents = <$fh>;
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}
