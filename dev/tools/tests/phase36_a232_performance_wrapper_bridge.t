use strict;
use warnings;

use Cwd qw(abs_path);
use Config;
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use JSON::PP;
use Test::More;
use Time::HiRes ();

my $root = File::Spec->rel2abs(File::Spec->catdir($Bin, '..', '..', '..'));
my $legacy = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_acceptance_manifest.pl');
my $wrapper = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_release_manifest.pl');
my $temporary = tempdir(CLEANUP => 1);
my $json = JSON::PP->new->canonical->pretty;
my $source = '1' x 40;
my $baseline = '2' x 64;
my $system_perl = abs_path($Config{perlpath});

do $wrapper or die "Cannot load $wrapper: $@ $!";

subtest 'legacy checker validates delegation and rejects old or mixed authority' => sub {
    my $requirements = write_json('requirements.json', {
        schema_version => 1,
        policy => 'current upstream; no pinned Perl revision',
        baseline_sha256 => $baseline,
        cpan_acceptance => {
            policy_sha256 => '3' x 64,
            expected_targets => ['Fixture'],
            required_modes => [qw(jvm interpreter)],
        },
        allowed_cpan_excluded_audit_classifications => ['pre-existing-non-regex'],
        required_gates => [{ id => 'performance', kind => 'performance' }],
    });
    my $final = write_file('final-performance.json', "{\"sealed\":true}\n");
    my $sha = sha_file($final);
    my $valid_details = {
        final_performance_contract => 'phase36-final-performance/v1',
        final_performance_sha256 => $sha,
        performance_authority => 'final-release-wrapper',
    };

    my ($status, $report) = legacy_check($requirements, $final,
        { %$valid_details }, 'delegated');
    is($status, 0, 'descriptor-only delegation passes');
    is($report->{gates}{performance}{performance_authority},
        'final-release-wrapper', 'legacy report exposes delegation marker');
    ok($report->{summary}{authoritative},
        'legacy authority covers only its envelope-validation layer');

    ($status, $report) = legacy_check($requirements, $final, {
        baseline_seconds => [1, 1, 1, 1, 1],
        candidate_seconds => [0.9, 0.9, 0.9, 0.9, 0.9],
        alternating_order => JSON::PP::true,
    }, 'legacy-only');
    isnt($status, 0, 'legacy-summary-only evidence is rejected');
    like(join('\n', @{$report->{gates}{performance}{issues}}),
        qr/legacy or mixed authority|delegation fields are incomplete/,
        'legacy-only rejection identifies the authority violation');

    ($status, $report) = legacy_check($requirements, $final, {
        %$valid_details, baseline_seconds => [1], candidate_seconds => [0.9],
    }, 'mixed');
    isnt($status, 0, 'mixed final-artifact and timing authority is rejected');
    like(join('\n', @{$report->{gates}{performance}{issues}}),
        qr/legacy or mixed authority/, 'mixed authority has an exact diagnostic');

    ($status, $report) = legacy_check($requirements, $final, {
        %$valid_details, performance_authority => 'producer-selected-wrapper',
    }, 'self-selected-authority');
    isnt($status, 0, 'producer self-selection cannot replace wrapper authority');
};

subtest 'wrapper invokes one strict checker and retains exact bounded bytes' => sub {
    my $checker = write_file('pinned-checker.pl', "#!/usr/bin/env perl\n1;\n");
    my $requirements = write_file('pinned-requirements.json', "{}\n");
    my $final_path = write_file('selected-final.json', "{\"final\":true}\n");
    my $checker_record = pinned_record($checker, 'performance checker');
    my $requirements_record = pinned_record($requirements, 'requirements');
    my $final_record = pinned_record($final_path, 'final artifact');
    my $perl_record = authority_file_record($system_perl, 'perl', 1);
    my $sealed = { owner => '/private/snapshot-root', inputs => {
        performance_checker => $checker_record,
        requirements => $requirements_record,
    } };
    my $trusted = {
        records => { perl => $perl_record },
        perl => $system_perl, java => $system_perl, git => $system_perl,
        ps => $system_perl, uptime => $system_perl, authority_key => '/private/key',
        baseline_source => '/baseline', candidate_source => '/candidate',
        perl5_source => '/perl5', expected_commit => $source,
        source_state => {
            baseline_source_commit => '4' x 40,
            candidate_source_commit => $source,
            candidate_parent_commit => '4' x 40,
            perl5_commit => '5' x 40,
        },
    };
    my $final = { record => $final_record, original_path => 'selected-final.json' };
    my $strict = strict_report();
    my $bytes = $json->encode($strict);
    my $calls = 0;
    my $retained;
    {
        no warnings 'redefine';
        local *main::run_exact_process = sub {
            $calls++;
            my ($argv) = @_;
            is((grep { $_ eq 'strict' } @$argv), 1,
                'accepted checker argv contains strict mode exactly once');
            return (0, $bytes, '');
        };
        $retained = run_final_performance_checker($sealed, $final, $trusted);
    }
    is($calls, 1, 'strict checker is invoked exactly once');
    is($retained->{strict_report_sha256}, sha256_hex($bytes),
        'exact strict-report byte hash is retained');
    is($retained->{strict_report_final_artifact_sha256}, $final_record->{sha256},
        'strict result is explicitly bound to selected final artifact');
    unlike($json->encode($retained), qr{/private/key},
        'authority secret path is not retained');

    for my $case (
        ['report-only', { %{strict_report()}, check_mode => 'report' }, 0, ''],
        ['review-stop', { %{strict_report()}, decision => 'review-stop' }, 0, ''],
        ['nonzero', strict_report(), 1 << 8, ''],
        ['review-stop-exit', strict_report(), 2 << 8, ''],
        ['signal', strict_report(), 9, ''],
        ['extra-stderr', strict_report(), 0, "unexpected\n"],
        ['malformed', undef, 0, ''],
        ['duplicate-json', strict_report(), 0, '', 'duplicate'],
    ) {
        my ($name, $report, $wait, $stderr, $duplicate) = @$case;
        my $output = defined($report) ? $json->encode($report) : "{partial";
        $output .= $output if $duplicate;
        my $error;
        {
            no warnings 'redefine';
            local *main::run_exact_process = sub { return ($wait, $output, $stderr) };
            eval { run_final_performance_checker($sealed, $final, $trusted) };
            $error = $@;
        }
        like($error, qr/(?:authoritative strict pass|rejected strict evidence|unexpected stderr|Invalid|terminated by signal)/,
            "$name checker result fails closed");
    }
};

subtest 'complete accepted A231 transitive inputs are pinned' => sub {
    my $owner = tempdir(DIR => $temporary, CLEANUP => 1);
    my $sealed = { owner => $owner, copied_files => 0, copied_bytes => 0,
        retained_bytes => 0 };
    my $inputs = pin_validation_inputs($sealed);
    my @required = qw(legacy requirements verifier performance_checker
        performance_module performance_helper performance_orchestrator
        performance_ordinary_producer performance_benchmark performance_schema
        performance_assembler);
    ok($inputs->{$_}, "$_ is pinned") for @required;
    is_deeply([sort grep { /\Aperformance_/ } keys %$inputs],
        [sort grep { /\Aperformance_/ } @required],
        'checker, policy, module, helper, assembler, producers, and workload are exact');
    for my $name (@required) {
        is(sha_file($inputs->{$name}{snapshot}), $inputs->{$name}{sha256},
            "$name snapshot retains exact accepted bytes");
    }
};

subtest 'final artifact cannot override wrapper artifact or code authority' => sub {
    my @selected = qw(baseline_jar candidate_jar baseline_launcher
        candidate_launcher interpreter_launcher java perl git ps uptime
        jfr_tool jfc time_executable ordered_test_source
        ordered_fixture_manifest dbix_archive);
    my %record;
    for my $name (@selected) {
        my $path = write_file("selector-$name", "$name authority bytes\n");
        chmod 0700, $path if $name =~ /\A(?:baseline_launcher|candidate_launcher|interpreter_launcher|java|perl|git|ps|uptime|jfr_tool|time_executable)\z/;
        $record{$name} = authority_file_record(abs_path($path), $name,
            -x $path ? 1 : 0);
    }
    my $state = {
        baseline_source_commit => 'a' x 40,
        candidate_source_commit => 'b' x 40,
        candidate_parent_commit => 'a' x 40,
        perl5_commit => 'c' x 40,
    };
    my $trusted = { records => \%record, source_state => $state };
    my %pinned;
    for my $name (qw(performance_benchmark performance_ordinary_producer
            performance_module performance_helper)) {
        $pinned{$name} = pinned_record(
            write_file("selector-$name", "$name pinned bytes\n"), $name);
    }
    my %identity = %$state;
    my %mapping = (
        baseline_jar => 'baseline_jar', candidate_jar => 'candidate_jar',
        baseline_launcher => 'baseline_launcher',
        candidate_launcher => 'candidate_launcher',
        interpreter_launcher => 'interpreter_launcher',
        jdk_executable => 'java', perl_interpreter => 'perl',
        git_executable => 'git', ps_executable => 'ps',
        uptime_executable => 'uptime', jfr_tool => 'jfr_tool', jfc => 'jfc',
        time_executable => 'time_executable', ordered_test_source => 'ordered_test_source',
        ordered_fixture_manifest => 'ordered_fixture_manifest',
        dbix_archive => 'dbix_archive');
    for my $field (keys %mapping) {
        my $selected_record = $record{$mapping{$field}};
        $identity{$field} = { sha256 => $selected_record->{sha256},
            size => $selected_record->{size} };
    }
    my %pin_mapping = (
        benchmark => 'performance_benchmark',
        ordinary_performance_producer => 'performance_ordinary_producer',
        performance_evaluator => 'performance_module',
        jfr_metrics_producer => 'performance_helper');
    for my $field (keys %pin_mapping) {
        my $selected_record = $pinned{$pin_mapping{$field}};
        $identity{$field} = { sha256 => $selected_record->{sha256},
            size => $selected_record->{size} };
    }
    my $document = { schema_version => 1,
        kind => 'phase36-final-performance', identity => \%identity };
    my $valid = eval {
        validate_final_performance_document($document, $trusted,
            { inputs => \%pinned });
        1;
    };
    ok($valid,
        'exact wrapper-selected identities pass the selector barrier');
    $document->{identity}{candidate_jar}{sha256} = 'd' x 64;
    my $error = eval {
        validate_final_performance_document($document, $trusted,
            { inputs => \%pinned });
        '';
    };
    $error = $@ if $@;
    like($error, qr/candidate_jar differs from wrapper authority/,
        'evidence-selected candidate JAR substitution fails closed');
};

subtest 'bounded exact child rejects timeout and oversized output' => sub {
    my $error = eval {
        run_exact_process([$system_perl, '-e', 'select undef,undef,undef,5'],
            0.05, 1024);
        '';
    };
    $error = $@ if $@;
    like($error, qr/timed out/, 'timeout is fail closed');

    $error = eval {
        run_exact_process([$system_perl, '-e', 'print "x" x 4096'], 5, 128);
        '';
    };
    $error = $@ if $@;
    like($error, qr/exceeded bounded output/, 'oversized output is fail closed');
};

done_testing;

sub strict_report {
    return {
        schema_version => 1, check_mode => 'strict', decision => 'passed',
        authoritative => JSON::PP::true, envelope_issues => [],
        evaluation => {
            decision => 'passed', verified => JSON::PP::true,
            issues => [], review_stops => [],
        },
    };
}

sub legacy_check {
    my ($requirements, $final, $details, $name) = @_;
    my $evidence = write_json("$name-evidence.json", {
        schema_version => 1, mode => 'acceptance',
        identity => {
            source_commit => $source, perl5_commit => '6' x 40,
            runner_commit => $source, jperl_sha256 => '7' x 64,
            jar_sha256 => '8' x 64, sbom_sha256 => '9' x 64,
            baseline_sha256 => $baseline,
        },
        gates => { performance => {
            state => 'passed',
            artifact => { path => File::Spec->abs2rel($final, $temporary),
                sha256 => sha_file($final) },
            identity => { source_commit => $source }, details => $details,
        } },
    });
    my $report = File::Spec->catfile($temporary, "$name-report.json");
    system $system_perl, $legacy, '--requirements', $requirements,
        '--evidence', $evidence, '--mode', 'strict',
        '--expected-commit', $source, '--output', $report;
    return ($? >> 8, read_json($report));
}

sub pinned_record {
    my ($path, $label) = @_;
    my @identity = Time::HiRes::lstat($path);
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $bytes = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return { snapshot => $path, source => $path, label => $label,
        sha256 => sha_file($path), size => -s $path, identity => \@identity,
        source_identity => \@identity, retained_bytes => \$bytes };
}

sub write_json { return write_file($_[0], $json->encode($_[1])) }

sub write_file {
    my ($name, $bytes) = @_;
    my $path = File::Spec->file_name_is_absolute($name)
        ? $name : File::Spec->catfile($temporary, $name);
    make_path(File::Spec->catdir((File::Spec->splitpath($path))[1]));
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $bytes or die "Cannot write $path: $!";
    close $fh or die "Cannot close $path: $!";
    return $path;
}

sub read_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    return JSON::PP->new->decode(do { local $/; <$fh> });
}

sub sha_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    return Digest::SHA->new(256)->addfile($fh)->hexdigest;
}
