use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP;
use Symbol qw(gensym);
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'assemble_phase36_acceptance_envelope.pl');
my $requirements = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_acceptance_requirements.json');
my $json = JSON::PP->new->canonical->pretty;
my $source = '1' x 40;
my $perl5 = '2' x 40;
my $jperl = '3' x 64;
my $jar = sha256_hex("jar\n");
my $sbom = sha256_hex("{}\n");
my $baseline = '9adef3dde92414bee49cbb571f65e8fcc705e034189de37d6a6136672bc67211';
my $policy = 'b35b479d260550f933c144205c4c0b940e4b3df8731609ff215f687cc1a74872';
my @gates = qw(ledger jvm interpreter direct-thread cpan performance
    packaging notice-license make ci);
my @targets = qw(DBIx::Class DateTime Moo Regexp::Common String::Random
    Template Type::Tiny WWW::Mechanize);

my ($compile_status, $compile_log) = run_command($^X, '-c', $tool);
is($compile_status, 0, 'assembler compiles with system Perl') or diag $compile_log;

{
    my $fixture = fixture();
    my ($status, $log) = run_assembler($fixture);
    is($status, 0, 'complete structured producer set assembles') or diag $log;
    my $envelope = read_json($fixture->{output});
    is($envelope->{schema_version}, 1, 'output uses legacy checker schema v1');
    is($envelope->{mode}, 'acceptance', 'output is acceptance evidence');
    is_deeply([sort keys %{$envelope->{gates}}], [sort @gates],
        'output has exactly the ten policy gates');
    is($envelope->{gates}{ledger}{details}{runner_files}, 7,
        'mutable discovered file count is translated, not pinned to 623');
    is($envelope->{gates}{jvm}{details}{candidate_files}, 7,
        'comparison uses the same current ledger count');

    my ($second_status, $second_log) = run_assembler($fixture);
    isnt($second_status, 0, 'existing output is never overwritten');
    like($second_log, qr/Refusing to overwrite output|exclusively publish/,
        'exclusive publication has a specific diagnostic');
}

for my $gate (@gates) {
    my $missing = fixture();
    @{$missing->{authority}{lanes}} = grep { $_->{gate} ne $gate }
        @{$missing->{authority}{lanes}};
    rewrite_authority($missing);
    my ($missing_status, $missing_log) = run_assembler($missing);
    isnt($missing_status, 0, "missing $gate gate rejects");
    like($missing_log, qr/Missing authority gates:.*\Q$gate\E/,
        "missing $gate identifies the gate");

    my $tampered = fixture();
    my ($lane) = grep { $_->{gate} eq $gate } @{$tampered->{authority}{lanes}};
    my $artifact = File::Spec->catfile($tampered->{directory}, $lane->{artifact}{path});
    append_file($artifact, "tampered\n");
    my ($tamper_status, $tamper_log) = run_assembler($tampered);
    isnt($tamper_status, 0, "tampered $gate artifact rejects");
    like($tamper_log, qr/hash mismatch/,
        "tampered $gate is rejected by its selected hash");
}

{
    my $fixture = fixture();
    delete $fixture->{authority}{prerequisites};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing latest-Perl sync prerequisite rejects');
    like($log, qr/Authority prerequisites must be an object/,
        'missing sync attachment is explicit');
}

{
    my $fixture = fixture();
    my $entry = $fixture->{authority}{prerequisites}{perl5_sync};
    append_file(File::Spec->catfile($fixture->{directory},
        $entry->{artifact}{path}), "tampered\n");
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'tampered latest-Perl sync prerequisite rejects');
    like($log, qr/Perl5 sync prerequisite artifact hash mismatch/,
        'sync attachment is hash-bound');
}

{
    my $fixture = fixture();
    push @{$fixture->{authority}{lanes}}, {%{$fixture->{authority}{lanes}[0]}};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'duplicate gate selection rejects');
    like($log, qr/Duplicate authority gate/, 'duplicate diagnostic is exact');
}

{
    my $fixture = fixture();
    $fixture->{authority}{lanes}[0]{summary} = {state => 'passed'};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'mixed legacy summary in authority rejects');
    like($log, qr/unsupported fields: summary/,
        'authority cannot self-declare a gate summary');
}

{
    my $fixture = fixture();
    $fixture->{authority}{lanes}[0]{artifact}{path} = '../outside.json';
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'parent-traversal artifact path rejects');
    like($log, qr/parent traversal/, 'unsafe path diagnostic is exact');
}

{
    my $fixture = fixture();
    $fixture->{authority}{identity}{source_commit} = 'a' x 40;
    $fixture->{authority}{identity}{runner_commit} = 'a' x 40;
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'stale authority source rejects');
    like($log, qr/source differs from --expected-candidate/,
        'candidate binding diagnostic is exact');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{completion}{timeout} = JSON::PP::true;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'timeout record rejects even after authority rehash');
    like($log, qr/incomplete, timed out, or review-stopped/,
        'timeout cannot be laundered by resealing');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'direct-thread' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{details}{zero_tap} = 1;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'zero-TAP direct/thread record rejects after rehash');
    like($log, qr/incomplete or failing evidence/,
        'zero-TAP cannot be laundered by resealing');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{results}{Moo}{modes}{jvm}{total_tests} = 0;
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'zero-TAP CPAN mode rejects after complete reseal');
    like($log, qr/CPAN target Moo jvm did not pass/,
        'structured producer data overrides a passing aggregate');
}

done_testing;

sub fixture {
    my $directory = tempdir(CLEANUP => 1);
    my %paths;

    my $ledger = {
        summary => { runner_files => 7, unresolved_references => 0,
            missing_files => 0 },
        direct_thread_pairs => [{direct => 'a.t', thread => 'a_thr.t'}],
        thread_only_tests => [],
    };
    $paths{ledger} = write_named_json($directory, 'regex-ledger.json', $ledger);
    for my $backend (qw(jvm interpreter)) {
        my %runner_rows = map { ("test-$_.t" => {
            status => 'pass', total_tests => 1, exit_code => 0,
            timeout => JSON::PP::false, truncated => JSON::PP::false,
            execution_error => JSON::PP::false,
            planned_tests => 1, actual_tests_run => 1,
        }) } 1 .. 7;
        $paths{"$backend-results"} = write_named_json($directory,
            "$backend-results.json", {results => \%runner_rows});
        $paths{"$backend-comparison"} = write_named_json($directory,
            "$backend-comparison.json", {
                expected_files => 7,
                summary => { candidate_files => 7 },
                regressions => [], missing_files => [], zero_tap => [],
                truncated => [], execution_issues => [], new_invalid => [],
            });
    }
    write_file(File::Spec->catfile($directory, 'packaging.log'), "strict packaging passed\n");
    $paths{packaging} = 'packaging.log';
    my %regex_artifacts = map {
        my $path = File::Spec->catfile($directory, $paths{$_});
        my $name = $_ eq 'ledger' ? 'regex-ledger.json'
            : $_ eq 'packaging' ? 'packaging.log' : "$_.json";
        $name => { path => $paths{$_}, sha256 => sha_file($path) }
    } qw(ledger jvm-results interpreter-results jvm-comparison
        interpreter-comparison);
    my $regex = {
        schema_version => 1, mode => 'acceptance',
        source => { starting_sha => $source, final_sha => $source,
            perl5_sha_as_provenance => $perl5 },
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5,
            launcher => { path => 'jperl', sha256 => $jperl },
            jar => { path => 'candidate.jar', sha256 => $jar },
            sbom => { path => 'sbom.json', sha256 => $sbom },
            baseline => { path => 'baseline.log', sha256 => $baseline } },
        expected_files => 7,
        exit_statuses => { ledger => 0, 'jvm-comparison' => 0,
            'interpreter-comparison' => 0, packaging => 0 },
        artifacts => \%regex_artifacts,
    };
    my $regex_path = write_named_json($directory, 'regex-acceptance.json', $regex);

    my $direct_path = write_named_json($directory, 'direct-thread.json', {
        schema_version => 1, kind => 'direct-thread', verified => JSON::PP::true,
        identity => { source_commit => $source, runner_commit => $source,
            jperl_sha256 => $jperl },
        details => { expected_pairs => 1, actual_pairs => 1,
            expected_modes => 4, actual_modes => 4,
            expected_thread_only => 0, actual_thread_only => 0,
            expected_thread_only_modes => 2, actual_thread_only_modes => 2,
            mismatches => 0, missing => 0, zero_tap => 0, timeouts => 0,
            truncated => 0, execution_issues => 0 },
    });

    my %cpan_results;
    for my $target (@targets) {
        my %modes = map { $_ => { status => 'pass', total_tests => 1,
            exit_code => 0, signal => 0, timeout => JSON::PP::false,
            execution_error => JSON::PP::false, zero_tap => JSON::PP::false,
            malformed => JSON::PP::false, truncated => JSON::PP::false,
            failures => 0, unapproved_warnings => [], warning_diagnostics => [],
            identity => { source_commit => $source, runner_commit => $source,
                perl5_commit => $perl5, jperl_sha256 => $jperl,
                jar_sha256 => $jar, sbom_sha256 => $sbom } }
        } qw(jvm interpreter);
        $cpan_results{$target} = { status => 'pass', total_tests => 2,
            modes => \%modes };
    }
    my $cpan_path = write_named_json($directory, 'cpan-acceptance.json', {
        schema_version => 2, mode => 'acceptance', status => 'pass',
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl, jar_sha256 => $jar,
            sbom_sha256 => $sbom, policy_sha256 => $policy },
        expected_targets => [@targets], results => \%cpan_results,
        excluded_audits => [],
    });
    write_file(File::Spec->catfile($directory, 'cpan-acceptance.json.sha256'),
        sha_file(File::Spec->catfile($directory, $cpan_path))
            . "  cpan-acceptance.json\n");

    write_file(File::Spec->catfile($directory, 'candidate.jar'), "jar\n");
    my $ordinary_performance_path = write_named_json($directory,
        'ordinary-performance.json', {
        schema_version => 1, kind => 'performance', verified => JSON::PP::true,
        alternating_order => JSON::PP::true,
        baseline_seconds => [(2) x 5], candidate_seconds => [(1) x 5],
        execution_order => [map { ('baseline', 'candidate') } 1 .. 5],
        source => { candidate => { commit => $source } },
        artifacts => { candidate_jar => {
            path => 'candidate.jar', sha256 => $jar } },
    });
    my $performance_path = write_named_json($directory, 'final-performance.json', {
        schema_version => 1, kind => 'phase36-final-performance',
        verified => JSON::PP::true, decision => 'passed', review_explanations => [],
        identity => { candidate_source_commit => $source, perl5_commit => $perl5,
            candidate_jar => {sha256 => $jar},
            candidate_launcher => {sha256 => $jperl} },
        ordinary => { artifact => { path => $ordinary_performance_path,
            sha256 => sha_file(File::Spec->catfile($directory,
                $ordinary_performance_path)) } },
    });
    my $package_path = write_named_json($directory, 'package.json', {
        schema_version => 1, kind => 'packaging',
        producer => 'run_phase36_package_evidence.pl', verified => JSON::PP::true,
        identity => {source_commit => $source, jar_sha256 => $jar,
            sbom_sha256 => $sbom},
        completion => {exit_code => 0, signal => 0, timeout => JSON::PP::false,
            incomplete => JSON::PP::false, review_stop => JSON::PP::false},
        missing_entries => 0, duplicate_entries => 0,
    });
    my $notice_path = write_named_json($directory, 'notice-license.json', {
        schema_version => 1, kind => 'notice-license', verified => JSON::PP::true,
        jar_sha256 => $jar, sbom_sha256 => $sbom,
        missing_notices => 0, changed_notices => 0,
        missing_licenses => 0, changed_licenses => 0,
    });
    my $completion = { exit_code => 0, signal => 0,
        timeout => JSON::PP::false, incomplete => JSON::PP::false,
        review_stop => JSON::PP::false };
    my $make_path = write_named_json($directory, 'make.json', {
        schema_version => 1, kind => 'make', producer => 'run_phase36_make_evidence.pl',
        verified => JSON::PP::true, identity => { source_commit => $source },
        completion => {%$completion}, warnings => 0, failures => 0,
    });
    my $ci_path = write_named_json($directory, 'ci.json', {
        schema_version => 1, kind => 'ci', producer => 'run_phase36_ci_evidence.pl',
        verified => JSON::PP::true, identity => { source_commit => $source },
        completion => {%$completion}, platforms => {
            'ubuntu-latest' => {status => 'success', source_commit => $source},
            'windows-latest' => {status => 'success', source_commit => $source},
        },
    });

    my $sync_path = write_named_json($directory, 'perl5-sync.json', {
        schema_version => 1, kind => 'phase36-perl5-sync-evidence', status => 'pass',
        expected_source_commit => $source, final_source_commit => $source,
        source => {before => {commit => $source, clean => JSON::PP::true},
            after => {commit => $source, clean => JSON::PP::true}},
        perl5 => {before => {commit => $perl5, acceptance_clean => JSON::PP::true},
            after => {commit => $perl5, acceptance_clean => JSON::PP::true}},
        command => {exit_code => 0, signal => 0, timeout => JSON::PP::false},
        sync_markers => {pass_count => 2, second_pass_seen => JSON::PP::true,
            idempotence_verified => JSON::PP::true},
    });

    my %producer = (
        ledger => 'run_phase36_regex_acceptance.pl',
        jvm => 'run_phase36_regex_acceptance.pl',
        interpreter => 'run_phase36_regex_acceptance.pl',
        packaging => 'run_phase36_package_evidence.pl',
        'direct-thread' => 'collect_phase36_direct_thread.pl',
        cpan => 'run_phase36_cpan_acceptance.pl',
        performance => 'run_phase36_final_performance.pl',
        'notice-license' => 'verify_phase36_notice_license.pl',
        make => 'run_phase36_make_evidence.pl', ci => 'run_phase36_ci_evidence.pl',
    );
    my %gate_path = ((map { $_ => $regex_path }
            qw(ledger jvm interpreter)),
        packaging => $package_path,
        'direct-thread' => $direct_path, cpan => $cpan_path,
        performance => $performance_path, 'notice-license' => $notice_path,
        make => $make_path, ci => $ci_path);
    my $authority = {
        schema_version => 1, kind => 'phase36-envelope-authority', mode => 'acceptance',
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl, jar_sha256 => $jar,
            sbom_sha256 => $sbom, baseline_sha256 => $baseline },
        prerequisites => { perl5_sync => {
            producer => 'run_phase36_perl5_sync_evidence.pl',
            artifact => {path => $sync_path,
                sha256 => sha_file(File::Spec->catfile($directory, $sync_path))},
        } },
        lanes => [map { my $gate = $_; {
            gate => $gate, producer => $producer{$gate}, artifact => {
                path => $gate_path{$gate},
                sha256 => sha_file(File::Spec->catfile($directory, $gate_path{$gate})),
            } } } @gates],
    };
    my $fixture = { directory => $directory, authority => $authority,
        authority_path => File::Spec->catfile($directory, 'authority.json'),
        output => File::Spec->catfile($directory, 'envelope.json') };
    rewrite_authority($fixture);
    return $fixture;
}

sub rewrite_authority {
    my ($fixture) = @_;
    write_json($fixture->{authority_path}, $fixture->{authority});
}

sub run_assembler {
    my ($fixture) = @_;
    return run_command($^X, $tool, '--authority', $fixture->{authority_path},
        '--requirements', $requirements, '--expected-candidate', $source,
        '--expected-baseline', $baseline, '--output', $fixture->{output});
}

sub run_command {
    my (@command) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, @command);
    local $/;
    my $output = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub write_named_json {
    my ($directory, $name, $value) = @_;
    write_json(File::Spec->catfile($directory, $name), $value);
    return $name;
}

sub write_json { write_file($_[0], $json->encode($_[1])); }

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub append_file {
    my ($path, $contents) = @_;
    open my $fh, '>>:raw', $path or die "Cannot append $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub read_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $value = JSON::PP->new->decode(do { local $/; <$fh> });
    close $fh or die "Cannot close $path: $!";
    return $value;
}

sub sha_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $path: $!";
    return sha256_hex($bytes);
}
