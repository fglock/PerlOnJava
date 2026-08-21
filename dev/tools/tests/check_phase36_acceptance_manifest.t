use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
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
my $temporary = tempdir(CLEANUP => 1);
my $json = JSON::PP->new->canonical->pretty;

my $source = '1' x 40;
my $perl5 = '2' x 40;
my $jperl_sha = '3' x 64;
my $jar_sha = '4' x 64;
my $sbom_sha = '5' x 64;
my $baseline_sha = '6' x 64;
my @gate_ids = qw(ledger jvm interpreter direct-thread cpan performance
    packaging notice-license make ci);

my %artifact;
for my $id (@gate_ids) {
    my $name = "$id.artifact";
    my $path = File::Spec->catfile($temporary, $name);
    my $contents = "retained $id evidence\n";
    write_file($path, $contents);
    $artifact{$id} = { path => $name, sha256 => sha256_hex($contents) };
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
    ['baseline-regression', sub { $_[0]{gates}{jvm}{details}{regressions} = 1 },
        'regressions is missing or nonzero'],
    ['missing-artifact', sub {
        $_[0]{gates}{cpan}{artifact}{path} = 'does-not-exist.artifact'
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
                unresolved_references => 0, missing_files => 0,
            }),
            jvm => gate('jvm', \%comparison_identity, { %comparison }),
            interpreter => gate('interpreter', \%comparison_identity, { %comparison }),
            'direct-thread' => gate('direct-thread', \%runner_identity, {
                expected_pairs => 11, actual_pairs => 11,
                expected_modes => 4, actual_modes => 4,
                mismatches => 0, missing => 0, zero_tap => 0,
                timeouts => 0, truncated => 0, execution_issues => 0,
            }),
            cpan => gate('cpan', \%runner_identity, {
                expected_targets => ['Image::ExifTool', 'Regexp::Common'],
                results => {
                    'Image::ExifTool' => { status => 'pass', total_tests => 10 },
                    'Regexp::Common' => { status => 'pass', total_tests => 20 },
                },
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
