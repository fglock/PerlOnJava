use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP;
use MIME::Base64 qw(encode_base64);
use Symbol qw(gensym);
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'assemble_acceptance_envelope.pl');
my $requirements_template = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'acceptance_requirements.json');
my $json = JSON::PP->new->canonical->pretty;
my $source = '1' x 40;
my $perl5 = '2' x 40;
my $jperl = sha256_hex("jperl\n");
my $jar = sha256_hex("jar\n");
my $sbom = sha256_hex("{}\n");
my $baseline = sha256_hex("baseline\n");
my $policy = 'b35b479d260550f933c144205c4c0b940e4b3df8731609ff215f687cc1a74872';
my @gates = qw(ledger jvm interpreter direct-thread cpan performance packaging notice-license make ci);
my @targets = qw(DBIx::Class DateTime Moo Regexp::Common String::Random Template Type::Tiny WWW::Mechanize);
my (undef, $help) = run_command($^X, $tool, '--help');
my $has_requirements_hash = $help =~ /--expected-requirements-sha256/;

my ($compile_status, $compile_log) = run_command($^X, '-c', $tool);
is($compile_status, 0, 'assembler compiles with system Perl') or diag $compile_log;

subtest 'complete final-envelope graph is a green control' => sub {
    my $f = fixture();
    my ($status, $log) = run_assembler($f);
    is($status, 0, 'complete ordinary/psycho/ordered graph is accepted')
        or diag("HARNESS/GREEN-CONTROL ERROR: $log");
    ok(-f $f->{output}, 'green control publishes the accepted envelope');
};

subtest 'S1 rejects a skeletal A231/A232 graph' => sub {
    my $f = fixture();
    mutate_lane($f, 'performance', sub {
        $_[0]{ordinary}{artifact} = {path => 'missing.json', sha256 => ('0' x 64), size => 0};
        $_[0]{authority} = {kind => 'regex_implementation-performance-authority'};
    });
    expect_reject($f, qr/(?:ordinary|performance).*(?:artifact|graph|authority)/i,
        'skeletal ordinary graph');
};

subtest 'S3 authenticates every selected release-tuple byte source' => sub {
    for my $case (['launcher','jperl'], ['jar','candidate.jar'],
            ['sbom','sbom.json'], ['baseline','baseline.log']) {
        my ($label,$name)=@$case; my $f=fixture();
        append_file($f->{selected}{$label},"substituted\n");
        expect_reject($f, qr/\Q$label\E.*(?:hash|byte|changed|identity)|(?:hash|byte|changed|identity).*\Q$label\E/i,
            "substituted selected $label bytes");
    }
};

subtest 'S4 authenticates requirements and gate kinds' => sub {
    my $kind=fixture();
    mutate_requirements($kind,sub { $_->{kind}='comparison' for grep {$_->{id} eq 'ledger'} @{$_[0]{required_gates}} });
    expect_reject($kind,qr/(?:requirements|gate).*kind|kind.*(?:requirements|gate)/i,'wrong gate kind');

    my $policy_f=fixture();
    mutate_requirements($policy_f,sub {
        $_[0]{minimum_performance_samples}=999;
        $_[0]{allowed_cpan_excluded_audit_classifications}=['unreviewed-regression'];
    });
    expect_reject($policy_f,qr/(?:requirements|policy).*(?:hash|authentic|differ)|(?:hash|authentic|differ).*(?:requirements|policy)/i,
        'substituted requirements policy');

};

subtest 'S5 requires exact JSON booleans' => sub {
    my @cases = (
        ['CI authoritative string boolean', 'ci', sub {
            $_[0]{authoritative}='true'; reseal_payload($_[0]);
        }, qr/ci.*boolean|boolean.*ci/i],
        ['CPAN stale schema', 'cpan', sub { $_[0]{schema_version}=1 },
            qr/cpan.*schema|schema.*cpan/i],
        ['CPAN execution authority string boolean', 'cpan', sub {
            $_[0]{identity}{execution_authorized}='true';
        }, qr/cpan.*boolean|boolean.*cpan|execution_authorized/i],
    );
    for my $case (@cases) {
        my ($label,$gate,$mutation,$diagnostic)=@$case;
        my $f=fixture(); mutate_lane($f,$gate,$mutation);
        expect_reject($f,$diagnostic,$label);
    }
};

subtest 'S5 durable publication failure leaves no authority' => sub {
    my $f=fixture();
    my $lib=File::Spec->catdir($f->{directory},'fault-lib'); make_path($lib);
    write_file(File::Spec->catfile($lib,'RegexImplementationSyncFailure.pm'), <<'MODULE');
package RegexImplementationSyncFailure;
use strict;
use warnings;
use IO::Handle ();
BEGIN { no warnings 'redefine'; *IO::Handle::sync = sub { $! = 5; return } }
1;
MODULE
    local $ENV{PERL5LIB}=join(':',$lib,grep {defined && length} ($ENV{PERL5LIB}));
    local $ENV{PERL5OPT}='-MRegexImplementationSyncFailure';
    my ($status,$log)=run_assembler($f);
    if ($status == 0) {
        fail('file-sync failure rejects');
        fail('file-sync failure publishes no envelope');
        pass('current production false accept is distinguished from a harness error');
        diag("UNEXPECTED FALSE ACCEPT: $log");
    } else {
        pass('file-sync failure rejects');
        ok(!-e $f->{output},'file-sync failure publishes no envelope');
        like($log,qr/(?:sync|durab|publish)/i,'durability failure is diagnosed')
            or diag("HARNESS/WRONG-GATE ERROR: $log");
    }
};

done_testing;

sub expect_reject {
    my ($f,$diag,$label)=@_; my ($status,$log)=run_assembler($f);
    if ($status == 0) {
        fail("$label rejects");
        fail("$label publishes no accepted envelope");
        pass("$label is a production false accept, not a harness rejection");
        diag("UNEXPECTED FALSE ACCEPT: $log");
        return;
    }
    pass("$label rejects");
    ok(!-e $f->{output},"$label publishes no accepted envelope");
    like($log,$diag,"$label reaches intended check")
        or diag("HARNESS/WRONG-GATE ERROR: $log");
}
sub mutate_requirements {
    my ($f,$cb)=@_; my $r=read_json($f->{requirements}); $cb->($r); write_json($f->{requirements},$r);
}
sub mutate_lane {
    my ($f,$gate,$cb)=@_;
    my ($lane)=grep {$_->{gate} eq $gate} @{$f->{authority}{lanes}};
    my $path=File::Spec->catfile($f->{directory},$lane->{artifact}{path});
    my $r=read_json($path); $cb->($r); write_json($path,$r);
    my $sha=sha_file($path);
    write_file("$path.sha256", "$sha  $lane->{artifact}{path}\n")
        if $gate eq 'cpan' && -e "$path.sha256";
    $_->{artifact}{sha256}=$sha for grep {$_->{artifact}{path} eq $lane->{artifact}{path}} @{$f->{authority}{lanes}};
    rewrite_authority($f);
}

sub fixture {
    my $directory = tempdir(CLEANUP => 1);
    my $source_directory = tempdir(CLEANUP => 1);
    my $tool_directory = tempdir(CLEANUP => 1);
    write_file(File::Spec->catfile($directory, 'baseline.log'), "baseline\n");
    write_file(File::Spec->catfile($directory, 'sbom.json'), "{}\n");
    write_file(File::Spec->catfile($directory, 'jperl'), "jperl\n");
    my $requirements_record = read_json($requirements_template);
    $requirements_record->{baseline_sha256} = $baseline;
    my $fixture_requirements = File::Spec->catfile($source_directory,
        'dev', 'regex', 'tools', 'acceptance_requirements.json');
    make_path(File::Spec->catdir($source_directory, 'dev', 'regex', 'tools'));
    write_json($fixture_requirements, $requirements_record);
    my $trusted_requirements_sha256 = sha_file($fixture_requirements);
    write_file(File::Spec->catfile($source_directory, 'candidate.jar'), "jar\n");
    my %paths;

    my $ledger = {
        schema_version => 1,
        policy => 'current latest upstream perl5 checkout; no pinned revision',
        scope => 'complete',
        summary => {core_re_files => 1, auxiliary_regex_files => 0,
            runner_files => 7, documented_unit_gates => 0,
            direct_thread_pairs => 1, thread_only_tests => 0,
            unresolved_references => 0},
        core_re_files => ['test-1.t'], auxiliary_regex_files => [],
        runner_files => [map { "test-$_.t" } 1 .. 7],
        documented_unit_gates => [],
        direct_thread_pairs => [{direct => 'test-2.t', thread => 'test-3.t'}],
        thread_only_tests => [], unresolved_references => [],
    };
    $paths{ledger} = write_named_json($directory, 'regex-ledger.json', $ledger);
    $paths{'strict-regex-ledger'} = write_named_json($directory,
        'strict-regex-ledger.json', $ledger);
    for my $backend (qw(jvm interpreter)) {
        my %runner_rows = map { my $file = "test-$_.t"; ($file => {
            file => $file, duration => 0.1,
            status => 'pass', total_tests => 1, exit_code => 0,
            ok_count => 1, not_ok_count => 0, incomplete_tests => 0,
            skip_count => 0, todo_count => 0, errors => [], missing_features => [],
            timeout => JSON::PP::false, truncated => JSON::PP::false,
            execution_error => JSON::PP::false,
            planned_tests => 1, actual_tests_run => 1,
        }) } 1 .. 7;
        $paths{"$backend-results"} = write_named_json($directory,
            "$backend-results.json", {timestamp => 'synthetic',
                jperl_path => 'jperl', summary => {}, feature_impact => {},
                results => \%runner_rows});
        $paths{"$backend-comparison"} = write_named_json($directory,
            "$backend-comparison.json", {
                expected_files => 7,
                summary => {baseline_ok => 7, candidate_ok => 7, delta_ok => 0,
                    baseline_total => 7, candidate_total => 7, delta_total => 0,
                    baseline_files => 7, candidate_files => 7},
                regressions => [], missing_files => [], zero_tap => [],
                truncated => [], execution_issues => [], new_invalid => [],
                improvements => [], plan_changes => [], added_files => [],
                inherited_invalid => [],
            });
        $paths{"$backend-strict-regex-comparison"} = write_named_json($directory,
            "$backend-strict-regex-comparison.json", {
                expected_files => 3,
                summary => {baseline_ok => 3, candidate_ok => 3, delta_ok => 0,
                    baseline_total => 3, candidate_total => 3, delta_total => 0,
                    baseline_files => 3, candidate_files => 3},
                regressions => [], missing_files => [], zero_tap => [],
                truncated => [], execution_issues => [], new_invalid => [],
                improvements => [], plan_changes => [], added_files => [],
                inherited_invalid => [],
            });
    }
    write_file(File::Spec->catfile($directory, 'packaging.log'), "strict packaging passed\n");
    write_file(File::Spec->catfile($directory, 'regex-jperl-version.log'),
        "This is PerlOnJava ($source)\n");
    $paths{packaging} = 'packaging.log';
    my %regex_artifacts = map {
        my $path = File::Spec->catfile($directory, $paths{$_});
        my $name = $_ eq 'ledger' ? 'regex-ledger.json'
            : $_ eq 'packaging' ? 'packaging.log' : "$_.json";
        $name => { path => $paths{$_}, sha256 => sha_file($path) }
    } qw(ledger jvm-results interpreter-results jvm-comparison
        interpreter-comparison strict-regex-ledger
        jvm-strict-regex-comparison interpreter-strict-regex-comparison);
    $regex_artifacts{'jperl-version.log'} = {path => 'regex-jperl-version.log',
        sha256 => sha_file(File::Spec->catfile($directory,
            'regex-jperl-version.log'))};
    my $regex = {
        schema_version => 1, mode => 'acceptance',
        source => { starting_sha => $source, final_sha => $source,
            perl5_sha_as_provenance => $perl5,
            tracked_state_signature => sha256_hex('') },
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5,
            launcher => { path => 'jperl', sha256 => $jperl },
            jar => { path => File::Spec->catfile($source_directory,
                    'candidate.jar'), sha256 => $jar },
            sbom => { path => File::Spec->catfile($directory,
                    'sbom.json'), sha256 => $sbom },
            baseline => { path => File::Spec->catfile($directory,
                    'baseline.log'), sha256 => $baseline },
            runner_policy => {timeout => 1800, jobs => 10,
                cpu_heavy_jobs => 2} },
        expected_files => 7,
        strict_regex_expected_files => 3, verified_runner_sha => $source,
        ledger_summary => $ledger->{summary},
        strict_regex_ledger_summary => $ledger->{summary},
        baseline => File::Spec->catfile($directory, 'baseline.log'),
        artifact_directory => $directory,
        exit_statuses => { ledger => 0, 'jvm-comparison' => 0,
            'interpreter-comparison' => 0, packaging => 0 },
        artifacts => \%regex_artifacts,
    };
    my $regex_path = write_named_json($directory, 'regex-acceptance.json', $regex);

    my $direct_path = write_named_json($directory, 'direct-thread.json', {
        schema_version => 1, kind => 'direct-thread', verified => JSON::PP::true,
        identity => { source_commit => $source, runner_commit => $source,
            jperl_sha256 => $jperl },
        observations => {description_differences => []},
        details => { expected_pairs => 1, actual_pairs => 1,
            expected_modes => 4, actual_modes => 4,
            expected_thread_only => 0, actual_thread_only => 0,
            expected_thread_only_modes => 2, actual_thread_only_modes => 2,
            mismatches => 0, missing => 0, zero_tap => 0, timeouts => 0,
            truncated => 0, execution_issues => 0,
            assertion_status_mismatches => 0, description_differences => 0,
            classified_shared_failures => 0, unclassified_shared_failures => 0,
            standalone_failures => 0, unused_allowlist => 0,
            status_counts => {}, rows => [], supplemental_core_artifacts => []},
        failures => {map { $_ => [] } qw(missing mismatches zero_tap timeouts
            truncated execution_issues classified_shared_failures
            unclassified_shared_failures standalone_failures unused_allowlist)},
    });

    my $jcpan_hash = sha256_hex('jcpan');
    my $manifest_hash = sha256_hex('manifest');
    my %cpan_authority_hash = map { $_ => sha256_hex("cpan-authority-$_") }
        qw(tuple marker bridge launch seal);
    my %cpan_inputs = (
        source => {path => '/source', commit => $source},
        perl5 => {path => '/perl5', commit => $perl5},
        jperl => {path => '/jperl', sha256 => $jperl},
        jcpan => {path => '/jcpan', sha256 => $jcpan_hash},
        jar => {path => '/candidate.jar', sha256 => $jar},
        sbom => {path => '/sbom.json', sha256 => $sbom},
    );
    my (@cpan_artifacts, %cpan_results);
    write_file(File::Spec->catfile($directory, 'jperl-version.log'), "jperl version\n");
    push @cpan_artifacts, {path => 'jperl-version.log', kind => 'jperl-version',
        sha256 => sha_file(File::Spec->catfile($directory, 'jperl-version.log'))};
    for my $target (@targets) {
        my %modes;
        for my $mode (qw(jvm interpreter)) {
            my $base = File::Spec->catdir('runs', slug("$target-$mode"));
            make_path(File::Spec->catdir($directory, $base));
            my $raw_relative = File::Spec->catfile($base, 'raw.log');
            my $meta_relative = File::Spec->catfile($base, 'result.json');
            my $raw_path = File::Spec->catfile($directory, $raw_relative);
            write_file($raw_path, "ok 1 - synthetic\nFiles=1, Tests=1, 0 wallclock secs\n");
            my %environment = (PERLONJAVA_JAR => '/candidate.jar',
                PERLONJAVA_HOME => '/tmp/home', HOME => '/tmp/home',
                TMPDIR => '/tmp/work', PERL_MM_USE_DEFAULT => '1',
                JPERL_INTERPRETER => $mode eq 'interpreter' ? '1' : undef,
                JPERL_UNIMPLEMENTED => undef, REGEX_IMPLEMENTATION_CPAN_TARGET => $target,
                REGEX_IMPLEMENTATION_CPAN_MODE => $mode);
            my $mode_result = {target => $target, mode => $mode,
                status => 'pass', total_tests => 1, exit_code => 0, signal => 0,
                timeout => JSON::PP::false, execution_error => JSON::PP::false,
                zero_tap => JSON::PP::false, malformed => JSON::PP::false,
                truncated => JSON::PP::false, failures => 0, skips => 0,
                started_at => '2026-08-23T00:00:00Z',
                ended_at => '2026-08-23T00:00:01Z', duration_seconds => 1,
                unapproved_warnings => [], warning_diagnostics => [],
                argv => ['/jcpan', '-t', $target], environment => \%environment,
                environment_sha256 => sha256_hex(
                    JSON::PP->new->canonical->encode(\%environment)),
                raw_log => {path => $raw_relative, sha256 => sha_file($raw_path)},
                identity => { source_commit => $source, runner_commit => $source,
                    perl5_commit => $perl5, jperl_sha256 => $jperl,
                    jar_sha256 => $jar, sbom_sha256 => $sbom,
                    jar_path => '/candidate.jar', sbom_path => '/sbom.json'} };
            $modes{$mode} = $mode_result;
            write_json(File::Spec->catfile($directory, $meta_relative), $mode_result);
            push @cpan_artifacts,
                {path => $raw_relative, kind => 'raw-log', sha256 => sha_file($raw_path)},
                {path => $meta_relative, kind => 'mode-result', sha256 => sha_file(
                    File::Spec->catfile($directory, $meta_relative))};
        }
        $cpan_results{$target} = { status => 'pass', total_tests => 2,
            timeout => JSON::PP::false, truncated => JSON::PP::false,
            execution_error => JSON::PP::false, rationale => 'sealed target',
            focused_selector_permitted => JSON::PP::false, modes => \%modes };
    }
    my $cpan_path = write_named_json($directory, 'cpan-acceptance.json', {
        schema_version => 2, mode => 'acceptance', status => 'pass',
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl, jar_sha256 => $jar,
            sbom_sha256 => $sbom, policy_sha256 => $policy,
            manifest_sha256 => $manifest_hash, jcpan_sha256 => $jcpan_hash,
            authority_tuple_sha256 => $cpan_authority_hash{tuple},
            execution_authorized => JSON::PP::true,
            authority_marker_sha256 => $cpan_authority_hash{marker},
            authority_bridge_sha256 => $cpan_authority_hash{bridge},
            authority_launch_sha256 => $cpan_authority_hash{launch},
            authority_seal_sha256 => $cpan_authority_hash{seal},
            inputs => \%cpan_inputs },
        authority => {
            schema => 'perlonjava.regex_implementation.cpan-launch-authority/v1',
            execution_authorized => JSON::PP::true,
            tuple_sha256 => $cpan_authority_hash{tuple},
            marker_sha256 => $cpan_authority_hash{marker},
            bridge_sha256 => $cpan_authority_hash{bridge},
            launch_sha256 => $cpan_authority_hash{launch},
            seal_sha256 => $cpan_authority_hash{seal},
        },
        expected_targets => [@targets], results => \%cpan_results,
        total_tests => 16, excluded_audits => [], artifacts => \@cpan_artifacts,
    });
    write_file(File::Spec->catfile($directory, 'cpan-acceptance.json.sha256'),
        sha_file(File::Spec->catfile($directory, $cpan_path))
            . "  cpan-acceptance.json\n");

    write_file(File::Spec->catfile($directory, 'candidate.jar'), "jar\n");
    my %ordinary_artifacts = (
        benchmark => retained($directory, 'ordinary-benchmark.pl', "benchmark\n"),
        baseline_jar => retained($directory, 'ordinary-baseline.jar', "baseline jar\n"),
        candidate_jar => retained($directory, 'candidate.jar', "jar\n"),
        baseline_launcher => retained($directory, 'ordinary-baseline-jperl', "baseline launcher\n"),
        candidate_launcher => retained($directory, 'jperl', "jperl\n"),
        java => retained($directory, 'ordinary-java', "java\n"),
        raw_logs => {},
    );
    for my $side (qw(baseline candidate)) {
        $ordinary_artifacts{raw_logs}{$side} = [
            retained($directory, "ordinary-$side-identity.log",
                "PerlOnJava source $source\n"),
            map { retained($directory, "ordinary-$side-$_.log",
                "REGEX_IMPLEMENTATION_REGEX_PERFORMANCE elapsed_seconds="
                . ($side eq 'baseline' ? 2 : 1)
                . " throughput=1 checksum=fixture source_commit=$source\n") }
                1 .. 5,
        ];
    }
    my $ordinary_performance_path = write_named_json($directory,
        'ordinary-performance.json', {
        schema_version => 1, kind => 'performance', verified => JSON::PP::true,
        alternating_order => JSON::PP::true,
        baseline_seconds => [(2) x 5], candidate_seconds => [(1) x 5],
        execution_order => [map { ('baseline', 'candidate') } 1 .. 5],
        source => { baseline => {commit => ('0' x 40)},
            candidate => {commit => $source, parent_commit => ('0' x 40)} },
        semantic_checksum => sha256_hex('fixture semantics'),
        artifacts => \%ordinary_artifacts,
    });
    my @performance_rows;
    for my $backend (qw(jvm interpreter)) {
        for my $spec (['re/pat_psycho.t',17,2],
                ['re/pat_psycho_thr.t',17,2], ['re/speed.t',59,0],
                ['re/speed_thr.t',59,0]) {
            my ($test,$plan,$skips)=@$spec;
            my $slug=slug("$backend-$test");
            my $tap="1..$plan\n" . join('', map {
                "ok $_ - fixture" . ($_ > $plan-$skips
                    ? ' # skip architecture' : '') . "\n"
            } 1..$plan);
            my $test_source=retained($directory,"$slug-test.t","test $test\n");
            my $tap_artifact=retained($directory,"$slug.tap",$tap);
            my $command=retained_json($directory,"$slug-command.json",{
                schema_version=>1, authority_selected=>JSON::PP::true,
                argv=>["sealed/$backend-jperl",$test], timeout_seconds=>600,
                source_commit=>$source, jar_sha256=>$jar,
                launcher_sha256=>$jperl,
                test_source_sha256=>$test_source->{sha256},
                environment_contract_sha256=>sha256_hex('environment'),
            });
            push @performance_rows,{backend=>$backend,test=>$test,
                source_commit=>$source,jar_sha256=>$jar,
                launcher_sha256=>$jperl,exit_code=>0,
                timeout=>JSON::PP::false,truncated=>JSON::PP::false,
                test_source=>$test_source,tap=>$tap_artifact,command=>$command};
        }
    }
    my @ordered_runs;
    my @ordered_sides=qw(baseline candidate candidate baseline);
    for my $index (0..$#ordered_sides) {
        my $side=$ordered_sides[$index];
        my $prefix="ordered-$index-$side";
        my %artifact = map { $_ => retained($directory,"$prefix-$_.log",
            "$_ fixture\n") } qw(process_inventory_before
                process_inventory_after load_before load_after jfr_summary);
        $artifact{tap}=retained($directory,"$prefix.tap",
            "1..1271\n" . join('',map {"ok $_ - fixture\n"} 1..1271)
                . "Auto checked 5 references for leaks - none detected\n");
        $artifact{time_raw}=retained($directory,"$prefix.time",
            "100 real\n120 user\n2 sys\n800000000 maximum resident set size\n");
        $artifact{jfr_recording}=retained($directory,"$prefix.jfr","jfr fixture\n");
        $artifact{environment}=retained_json($directory,"$prefix-environment.json",
            {schema_version=>1,complete=>JSON::PP::true});
        $artifact{load_admission}=retained_json($directory,"$prefix-admission.json",
            {schema_version=>1,complete=>JSON::PP::true,
                unexpected_perlonjava_jvms=>[]});
        $artifact{command}=retained_json($directory,"$prefix-command.json",{
            schema_version=>1,authority_selected=>JSON::PP::true,
            argv=>['timeout','900','sealed/jperl','t/87ordered.t'],
            timeout_seconds=>900,source_commit=>$source,jar_sha256=>$jar,
            launcher_sha256=>$jperl,
        });
        $artifact{jfr_metrics}=retained_json($directory,"$prefix-metrics.json",{
            schema_version=>1,complete=>JSON::PP::true,
            truncated=>JSON::PP::false,data_loss_events=>0,
            gc_pairing_complete=>JSON::PP::true,
            post_old_gc_observed=>JSON::PP::true,nmt_status=>'supported'});
        push @ordered_runs,{side=>$side,source_commit=>$source,
            jar_sha256=>$jar,launcher_sha256=>$jperl,
            jdk_executable_sha256=>sha256_hex('java'),
            jdk_version_log_sha256=>sha256_hex('java version'),
            jfc_sha256=>sha256_hex('jfc'),exit_code=>0,
            timeout=>JSON::PP::false,timeout_seconds=>900,%artifact};
    }
    my $performance_path = write_named_json($directory, 'final-performance.json', {
        schema_version => 1, kind => 'regex_implementation-final-performance',
        verified => JSON::PP::true, decision => 'passed', review_explanations => [],
        identity => { candidate_source_commit => $source, perl5_commit => $perl5,
            candidate_jar => {sha256 => $jar},
            candidate_launcher => {sha256 => $jperl} },
        ordinary => { artifact => { path => $ordinary_performance_path,
            sha256 => sha_file(File::Spec->catfile($directory,
                $ordinary_performance_path)) } },
        psycho_speed => {rows => \@performance_rows},
        ordered => {runs => \@ordered_runs},
        authority => {schema_version=>1,kind=>'regex_implementation-performance-authority',
            complete=>JSON::PP::true,execution_attested=>JSON::PP::true},
        policy_sha256 => $policy,
        evaluation => {schema_version => 1, decision => 'passed',
            verified => JSON::PP::true, policy_sha256 => $policy,
            issues => [], review_stops => [], metrics => {}},
    });
    my $notice_path = write_named_json($directory, 'notice-license.json', {
        schema_version => 1, kind => 'notice-license', verified => JSON::PP::true,
        jar_sha256 => $jar, sbom_sha256 => $sbom,
        missing_notices => 0, changed_notices => 0,
        missing_licenses => 0, changed_licenses => 0,
    });
    my %package_file = (jar => 'package-retained.jar', sbom => 'package-sbom.json',
        deb => 'package.deb', java_bom => 'package-java-bom.json',
        perl_bom => 'package-perl-bom.json', report => 'package-report.json');
    write_file(File::Spec->catfile($directory, $package_file{jar}), "jar\n");
    write_file(File::Spec->catfile($directory, $package_file{sbom}), "{}\n");
    for my $name (qw(deb java_bom perl_bom report)) {
        write_file(File::Spec->catfile($directory, $package_file{$name}),
            "$name\n");
    }
    my %package_descriptor = map { my $file = $package_file{$_};
        my $path = File::Spec->catfile($directory, $file);
        $_ => {path => $file, sha256 => sha_file($path), size => -s $path}
    } keys %package_file;
    my $package_path = write_named_json($directory, 'package.json', {
        schema_version => 1, kind => 'packaging',
        producer => 'run_package_evidence.pl',
        verified => JSON::PP::true,
        identity => {source_commit => $source, jar_sha256 => $jar,
            sbom_sha256 => $sbom},
        completion => {exit_code => 0, signal => 0, timeout => JSON::PP::false,
            incomplete => JSON::PP::false, review_stop => JSON::PP::false},
        artifacts => {
            report => $package_descriptor{report},
            deliverables => {map { $_ => $package_descriptor{$_} }
                qw(jar sbom deb)},
            sbom_inputs => {map { $_ => $package_descriptor{$_} }
                qw(java_bom perl_bom)},
            logs => {packaging => {path => 'packaging.log',
                sha256 => sha_file(File::Spec->catfile($directory, 'packaging.log')),
                size => -s File::Spec->catfile($directory, 'packaging.log')}},
            notice_license => {path => $notice_path,
                sha256 => sha_file(File::Spec->catfile($directory, $notice_path)),
                size => -s File::Spec->catfile($directory, $notice_path)}},
        missing_entries => 0, duplicate_entries => 0,
    });
    my $completion = { exit_code => 0, signal => 0,
        timeout => JSON::PP::false, incomplete => JSON::PP::false,
        review_stop => JSON::PP::false };
    write_file(File::Spec->catfile($directory, 'make.log'), "make passed\n");
    write_file(File::Spec->catfile($directory, 'ci.log'), "ci passed\n");
    my %make_artifact_name = (jar => 'candidate.jar', make_log => 'make.log',
        jar_embedded => 'make-jar-embedded.json', jar_version => 'make-jar-version.log',
        source_after => 'make-source-after.json',
        source_before => 'make-source-before.json',
        tool_versions => 'make-tool-versions.json');
    for my $name (values %make_artifact_name) {
        my $path = File::Spec->catfile($directory, $name);
        write_file($path, "$name\n") unless -e $path;
    }
    my %make_artifacts = map { my $name = $make_artifact_name{$_};
        my $path = $_ eq 'jar'
            ? File::Spec->catfile($source_directory, 'candidate.jar')
            : File::Spec->catfile($directory, $name);
        $_ => {path => $path, sha256 => sha_file($path), size => -s $path}
    } keys %make_artifact_name;
    my (%make_tools, %make_inputs);
    for my $name (qw(git jar_tool java make perl shell producer)) {
        my $file = "make-tool-$name";
        my $path = File::Spec->catfile($tool_directory, $file);
        write_file($path, "$name\n");
        my $descriptor = {path => $path, sha256 => sha_file($path), size => -s $path};
        $make_tools{$name} = $name eq 'producer' ? $descriptor
            : {%$descriptor, version_sha256 => sha256_hex("$name version\n")};
    }
    for my $name (qw(build_gradle gradle_wrapper_jar gradle_wrapper_properties
            gradlew makefile settings_gradle)) {
        my $file = "make-input-$name";
        my $path = File::Spec->catfile($source_directory, $file);
        write_file($path, "$name\n");
        $make_inputs{$name} = {path => $path, sha256 => sha_file($path), size => -s $path};
    }
    my $source_extras = {authority_inputs => [], generated_file_count => 0,
        generated_paths => [], generated_total_bytes => 0};
    my $source_state = {all_status_sha256 => sha256_hex('all-status'),
        diff_sha256 => sha256_hex('diff'), extras => $source_extras, head => $source,
        status_sha256 => sha256_hex('status'), tracked_clean => JSON::PP::true};
    my $make_document = {
        schema => 'perlonjava.regex_implementation.make-evidence/v1', schema_version => 1,
        kind => 'make', producer => 'run_make_evidence.pl',
        mode => 'acceptance', status => 'pass', verified => JSON::PP::true,
        authoritative => JSON::PP::true,
        identity => {source_commit => $source, runner_commit => $source,
            jar_sha256 => $jar, jar_reported_commit => $source,
            jar_embedded_commit => $source},
        source => {root => $source_directory, before => $source_state,
            after => {%$source_state}},
        command => {cwd => $directory, argv => ['make'], environment => {},
            started_utc => '2026-08-23T08:00:00Z',
            finished_utc => '2026-08-23T08:01:00Z', duration_milliseconds => 1},
        tools => \%make_tools, inputs => \%make_inputs,
        completion => {%$completion, truncated => JSON::PP::false},
        warning_scan => {classifier => 'regex_implementation-v1',
            classifier_sha256 => sha256_hex('classifier'),
            complete_log_sha256 => $make_artifacts{make_log}{sha256},
            count => 0, matches => []},
        failure_scan => {classifier => 'regex_implementation-v1',
            classifier_sha256 => sha256_hex('classifier'),
            complete_log_sha256 => $make_artifacts{make_log}{sha256},
            count => 0, matches => []},
        artifacts => \%make_artifacts,
    };
    $make_document->{seal} = {algorithm => 'SHA-256', payload_sha256 =>
        sha256_hex(JSON::PP->new->utf8->canonical->encode($make_document))};
    my $make_path = write_named_json($directory, 'make.json', $make_document);
    my $make_seal_path = File::Spec->catfile($directory, 'make.json.seal');
    write_file($make_seal_path, "SHA-256 "
        . $make_document->{seal}{payload_sha256} . " "
        . sha_file(File::Spec->catfile($directory, $make_path)) . "\n");
    my $regex_record = read_json(File::Spec->catfile($directory, $regex_path));
    $regex_record->{release_authority} = {
        schema_version => 1, kind => 'regex_implementation-release-authority',
        authoritative => JSON::PP::true, mode => 'acceptance',
        package_evidence => {path => File::Spec->catfile($directory, $package_path),
            sha256 => sha_file(File::Spec->catfile($directory, $package_path)),
            identity => {source_commit => $source, jar_sha256 => $jar,
                sbom_sha256 => $sbom}},
        make_evidence => {path => File::Spec->catfile($directory, $make_path),
            sha256 => sha_file(File::Spec->catfile($directory, $make_path)),
            seal => {path => $make_seal_path, sha256 => sha_file($make_seal_path)},
            identity => {source_commit => $source, runner_commit => $source,
                jar_sha256 => $jar, jar_reported_commit => $source,
                jar_embedded_commit => $source}},
        selected => {source_root => $source_directory, source_commit => $source,
            runner_commit => $source,
            jar => {path => File::Spec->catfile($source_directory, 'candidate.jar'),
                sha256 => $jar},
            sbom => {path => File::Spec->catfile($directory, 'sbom.json'),
                sha256 => $sbom},
            baseline => {path => File::Spec->catfile($directory, 'baseline.log'),
                sha256 => $baseline}},
    };
    write_json(File::Spec->catfile($directory, $regex_path), $regex_record);
    my %ci_platforms = (
        'ubuntu-latest' => {status => 'success', source_commit => $source,
            job_check_name => 'build (ubuntu-latest)', job_id => 501,
            check_run_id => 501},
        'windows-latest' => {status => 'success', source_commit => $source,
            job_check_name => 'build (windows-latest)', job_id => 502,
            check_run_id => 502},
    );
    my $ci_run = {id => 100, run_number => 1, run_attempt => 1,
        workflow_id => 201274429, check_suite_id => 900, head_sha => $source,
        event => 'push', status => 'completed', conclusion => 'success',
        created_at => '2026-08-23T08:00:00Z',
        updated_at => '2026-08-23T08:05:00Z'};
    my @ci_jobs = map { my $id = $_; my $platform = $id == 501
            ? 'ubuntu-latest' : 'windows-latest'; {
        id => $id, run_id => 100, run_attempt => 1,
        name => "build ($platform)", head_sha => $source,
        status => 'completed', conclusion => 'success',
        started_at => '2026-08-23T08:01:00Z',
        completed_at => '2026-08-23T08:04:00Z'} } (501, 502);
    my @ci_checks = map { my $id = $_; my $platform = $id == 501
            ? 'ubuntu-latest' : 'windows-latest'; {
        id => $id, name => "build ($platform)", head_sha => $source,
        status => 'completed', conclusion => 'success',
        started_at => '2026-08-23T08:01:00Z',
        completed_at => '2026-08-23T08:04:00Z', check_suite_id => 900,
        app => {id => 15368, slug => 'github-actions'}} } (501, 502);
    my @raw_labels = qw(tool:git-version tool:gh-version api:workflow
        api:commit api:runs-1 api:jobs api:checks);
    my @raw_api = map { my $bytes = "$_ evidence\n"; {
        label => $_, size => length($bytes), sha256 => sha256_hex($bytes),
        base64 => encode_base64($bytes, ''),
        ($_ =~ /\Aapi:/ ? (endpoint => "repos/fixture/$_") : ())} } @raw_labels;
    my $ci_document = {
        schema => 'perlonjava.regex_implementation.final-envelope-bridge/v1',
        schema_version => 1, kind => 'ci', producer => 'run_ci_evidence.pl',
        status => 'pass', mode => 'acceptance',
        verified => JSON::PP::true, authoritative => JSON::PP::true,
        identity => {source_commit => $source},
        source => {repository => 'fglock/PerlOnJava', commit => $source},
        completion => {%$completion}, platforms => \%ci_platforms,
        evidence => {
            schema => 'perlonjava.regex_implementation.ci-acceptance-evidence/v1',
            producer_version => '1.1.0', producer_sha256 => sha256_hex('ci-producer'),
            fixture_only => JSON::PP::false, repository => 'fglock/PerlOnJava',
            source_commit => $source, local_clean_exact_commit => JSON::PP::true,
            workflow => {id => 201274429, name => 'Java CI with Gradle',
                path => '.github/workflows/gradle.yml',
                sha256 => sha256_hex('workflow'), size => 10},
            policy => {path => 'dev/regex/tools/ci_evidence_policy.json',
                sha256 => sha256_hex('ci-policy'),
                requirements_path => 'dev/regex/tools/acceptance_requirements.json',
                requirements_sha256 => sha256_hex('requirements')},
            run => $ci_run,
            required_matrix => {'ubuntu-latest' => 'build (ubuntu-latest)',
                'windows-latest' => 'build (windows-latest)'},
            jobs => \@ci_jobs, checks => \@ci_checks,
        },
        tools => {
            git => {path => '/usr/bin/git', sha256 => sha256_hex('git'), size => 3,
                version_sha256 => sha256_hex('git version'), version => 'git 2.0'},
            gh => {path => '/usr/bin/gh', sha256 => sha256_hex('gh'), size => 2,
                version_sha256 => sha256_hex('gh version'), version => 'gh 2.0',
                offline => JSON::PP::false},
        },
        raw_api_evidence => \@raw_api,
    };
    $ci_document->{seal} = {algorithm => 'sha256',
        payload_sha256 => sha256_hex(JSON::PP->new->utf8->canonical->encode(
            $ci_document))};
    my $ci_path = write_named_json($directory, 'ci.json', $ci_document);

    my $sync_log = "Perl upstream commit: $perl5\nVerified remote tip: $perl5\n"
        . "Full manifest: 5 import(s) to process.\n"
        . "Protected paths from config (1):\n  Successful: 5\n  Errors: 0\n"
        . "Running second sync for idempotence verification.\n"
        . "Full manifest: 5 import(s) to process.\n"
        . "Protected paths from config (1):\n  Successful: 5\n  Errors: 0\n"
        . "Idempotence verified: second sync changed no imported outputs.\n";
    my $checkout_source = {path => '/source', commit => $source,
        branch => 'candidate', tracked_clean => JSON::PP::true,
        clean => JSON::PP::true, acceptance_clean => JSON::PP::true,
        untracked_paths => [], allowed_generated_untracked => [],
        unexpected_untracked => [], status_sha256 => sha256_hex('source-status')};
    my $checkout_perl5 = {path => '/perl5', commit => $perl5,
        branch => 'blead', tracked_clean => JSON::PP::true,
        clean => JSON::PP::true, acceptance_clean => JSON::PP::true,
        untracked_paths => [], allowed_generated_untracked => [],
        unexpected_untracked => [], status_sha256 => sha256_hex('perl5-status')};
    my $upstream = {remote => 'origin', repository_url => 'https://example/perl5.git',
        branch => 'blead', upstream => 'origin/blead', tip => $perl5};
    my %sync_tools = map { $_ => {path => "/tools/$_", sha256 => sha256_hex($_)} }
        qw(git make patch perl rsync);
    my %sync_inputs = map { $_ => {path => "/source/$_", sha256 => sha256_hex($_)} }
        qw(config makefile producer sync_script update_script);
    my $name_identity = {path => '/source/Name.pl', sha256 => sha256_hex('Name.pl'),
        present => JSON::PP::true};
    my $sync_path = write_named_json($directory, 'perl5-sync.json', {
        schema_version => 1, kind => 'regex_implementation-perl5-sync-evidence', status => 'pass',
        expected_source_commit => $source, final_source_commit => $source,
        timeout_seconds => 60, repository => 'https://example/perl5.git',
        source => {before => {%$checkout_source}, after => {%$checkout_source}},
        perl5 => {before => {%$checkout_perl5, commit => ('a' x 40)},
            after => {%$checkout_perl5}},
        upstream => {before => {%$upstream}, after => {%$upstream}},
        tools => \%sync_tools, inputs => \%sync_inputs,
        command => {argv => ['/tools/make', '-C', '/source',
                'PERL=/tools/perl', 'perl5-sync-check'],
            environment => {PERL5_REPOSITORY => 'https://example/perl5.git',
                FILTER => undef, PATH => '/tools', LC_ALL => 'C', LANG => 'C'},
            exit_code => 0, signal => 0, timeout => JSON::PP::false,
            duration_seconds => 1, complete_log => $sync_log,
            complete_log_sha256 => sha256_hex($sync_log)},
        sync_markers => {full_manifest_count => 5, pass_count => 2,
            successful_per_pass => [5, 5], errors_per_pass => [0, 0],
            protected_count_per_pass => [1, 1], second_pass_seen => JSON::PP::true,
            idempotence_verified => JSON::PP::true},
        protected_targets => [{path => 'protected.txt', sha256 => sha256_hex('protected')}],
        unicode_name => {before => {imported => {%$name_identity},
            upstream => {%$name_identity}}, after => {imported => {%$name_identity},
            upstream => {%$name_identity}}},
    });

    my %producer = (
        ledger => 'run_regex_acceptance.pl',
        jvm => 'run_regex_acceptance.pl',
        interpreter => 'run_regex_acceptance.pl',
        packaging => 'run_package_evidence.pl',
        'direct-thread' => 'collect_direct_thread.pl',
        cpan => 'run_cpan_acceptance.pl',
        performance => 'run_final_performance.pl',
        'notice-license' => 'verify_notice_license.pl',
        make => 'run_make_evidence.pl', ci => 'run_ci_evidence.pl',
    );
    my %gate_path = ((map { $_ => $regex_path }
            qw(ledger jvm interpreter)),
        packaging => $package_path,
        'direct-thread' => $direct_path, cpan => $cpan_path,
        performance => $performance_path, 'notice-license' => $notice_path,
        make => $make_path, ci => $ci_path);
    my $authority = {
        schema_version => 1, kind => 'regex_implementation-envelope-authority', mode => 'acceptance',
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl, jar_sha256 => $jar,
            sbom_sha256 => $sbom, baseline_sha256 => $baseline },
        prerequisites => { perl5_sync => {
            producer => 'run_perl5_sync_evidence.pl',
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
        source_directory => $source_directory, requirements => $fixture_requirements,
        requirements_sha256 => $trusted_requirements_sha256,
        selected => {
            launcher => File::Spec->catfile($directory,'jperl'),
            jar => File::Spec->catfile($source_directory,'candidate.jar'),
            sbom => File::Spec->catfile($directory,'sbom.json'),
            baseline => File::Spec->catfile($directory,'baseline.log'),
        },
        authority_path => File::Spec->catfile($directory, 'authority.json'),
        output => File::Spec->catfile($directory, 'envelope.json') };
    rewrite_authority($fixture);
    return $fixture;
}

sub rewrite_authority {
    my ($fixture) = @_;
    write_json($fixture->{authority_path}, $fixture->{authority});
}

sub mutate_regex_result {
    my ($fixture, $backend, $mutator) = @_;
    my ($lane) = grep { $_->{gate} eq 'ledger' }
        @{$fixture->{authority}{lanes}};
    my $manifest_path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $manifest = read_json($manifest_path);
    my $descriptor = $manifest->{artifacts}{"$backend-results.json"};
    my $result_path = File::Spec->catfile($fixture->{directory},
        $descriptor->{path});
    my $runner = read_json($result_path);
    $mutator->($runner->{results});
    write_json($result_path, $runner);
    $descriptor->{sha256} = sha_file($result_path);
    write_json($manifest_path, $manifest);
    my $manifest_sha = sha_file($manifest_path);
    $_->{artifact}{sha256} = $manifest_sha
        for grep { $_->{producer} eq 'run_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
}

sub mutate_regex_manifest {
    my ($fixture, $mutator) = @_;
    my ($lane) = grep { $_->{gate} eq 'ledger' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $record = read_json($path);
    $mutator->($record);
    write_json($path, $record);
    my $sha = sha_file($path);
    $_->{artifact}{sha256} = $sha
        for grep { $_->{producer} eq 'run_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
}

sub mutate_regex_artifact {
    my ($fixture, $name, $mutator) = @_;
    my ($lane) = grep { $_->{gate} eq 'ledger' }
        @{$fixture->{authority}{lanes}};
    my $manifest_path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $manifest = read_json($manifest_path);
    my $descriptor = $manifest->{artifacts}{$name};
    my $path = $descriptor->{path};
    $path = File::Spec->catfile($fixture->{directory}, $path)
        unless File::Spec->file_name_is_absolute($path);
    my $record = read_json($path);
    $mutator->($record);
    write_json($path, $record);
    $descriptor->{sha256} = sha_file($path);
    write_json($manifest_path, $manifest);
    my $sha = sha_file($manifest_path);
    $_->{artifact}{sha256} = $sha
        for grep { $_->{producer} eq 'run_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
}

sub mutate_make_external_seal {
    my ($fixture, $mutator) = @_;
    mutate_regex_manifest($fixture, sub {
        my $seal = $_[0]{release_authority}{make_evidence}{seal};
        my $bytes = read_file($seal->{path});
        $mutator->($bytes);
        write_file($seal->{path}, $bytes);
        $seal->{sha256} = sha_file($seal->{path});
    });
}

sub run_assembler_with_prepublication_mutation {
    my ($fixture) = @_;
    local $ENV{HARNESS_ACTIVE} = 1;
    local $ENV{REGEX_IMPLEMENTATION_ASSEMBLER_TEST_PREPUBLICATION_BOUNDARY} = 1;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error,
        $^X, $tool, assembler_arguments($fixture));
    my $ready = "$fixture->{output}.validation-ready";
    my $continue = "$fixture->{output}.validation-continue";
    for (1 .. 500) {
        last if -f $ready;
        select undef, undef, undef, 0.01;
    }
    die "assembler did not reach prepublication boundary\n" unless -f $ready;
    my ($make_lane) = grep { $_->{gate} eq 'make' }
        @{$fixture->{authority}{lanes}};
    my $make = read_json(File::Spec->catfile($fixture->{directory},
        $make_lane->{artifact}{path}));
    append_file($make->{artifacts}{make_log}{path},
        "mutated after validation\n");
    write_file($continue, "continue\n");
    local $/;
    my $output = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub reseal_payload {
    my ($record) = @_;
    delete $record->{seal};
    $record->{seal} = {algorithm => 'sha256', payload_sha256 =>
        sha256_hex(JSON::PP->new->utf8->canonical->encode($record))};
}

sub reseal_make_payload {
    my ($record) = @_;
    delete $record->{seal};
    $record->{seal} = {algorithm => 'SHA-256', payload_sha256 =>
        sha256_hex(JSON::PP->new->utf8->canonical->encode($record))};
}

sub run_assembler {
    my ($fixture) = @_;
    return run_command($^X, $tool, assembler_arguments($fixture));
}

sub assembler_arguments {
    my ($fixture) = @_;
    my @args = ('--authority', $fixture->{authority_path},
        '--requirements', $fixture->{requirements}, '--expected-candidate', $source,
        '--expected-baseline', $baseline, '--expected-perl5', $perl5,
        '--expected-runner', $source, '--expected-jperl-sha256', $jperl,
        '--expected-jar-sha256', $jar, '--expected-sbom-sha256', $sbom,
        '--expected-authority-sha256', sha_file($fixture->{authority_path}),
        '--output', $fixture->{output});
    push @args, '--expected-requirements-sha256', $fixture->{requirements_sha256}
        if $has_requirements_hash;
    return @args;
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

sub retained {
    my ($directory,$name,$bytes)=@_;
    my $path=File::Spec->catfile($directory,$name); write_file($path,$bytes);
    return {path=>$name,sha256=>sha_file($path),size=>-s $path};
}

sub retained_json {
    my ($directory,$name,$value)=@_;
    return retained($directory,$name,$json->encode($value));
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

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $path: $!";
    return $bytes;
}

sub sha_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $path: $!";
    return sha256_hex($bytes);
}

sub slug {
    my $value = lc $_[0];
    $value =~ s/[^a-z0-9]+/-/g;
    $value =~ s/^-|-$//g;
    return $value;
}
