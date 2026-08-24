use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::Phase36PerformanceEvidence qw(evaluate_performance seal_authority);

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $assembler = File::Spec->catfile($root, 'dev', 'tools',
    'assemble_phase36_final_performance.pl');
my $checker = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_final_performance.pl');
my $orchestrator = File::Spec->catfile($root, 'dev', 'tools',
    'run_phase36_final_performance.pl');
my $requirements = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_acceptance_requirements.json');
my $json = JSON::PP->new->canonical->pretty;
my $trusted_dir = tempdir(CLEANUP => 1);
my $trusted_java = File::Spec->catfile($trusted_dir, 'java');
write_raw($trusted_java, fake_java());
chmod 0700, $trusted_java or die $!;
my $authority_key = File::Spec->catfile($trusted_dir, 'authority.key');
write_raw($authority_key, 'phase36-test-authority-key-' . ('x' x 64));
chmod 0600, $authority_key or die $!;
my ($baseline_source, $candidate_source, $perl5_source,
    $baseline_commit, $candidate_commit, $perl5_commit) = trusted_repositories();
my $rules = load($requirements);

subtest 'complete raw exact-parent evidence passes' => sub {
    my ($dir, $draft, $candidate) = fixture();
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status, $output) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 0, 'assembler accepts complete raw evidence') or diag $output;
    my $document = load($final);
    diag join("\n", @{$document->{evaluation}{issues}})
        if $document->{decision} ne 'passed';
    is($document->{decision}, 'passed', 'producer records a passing decision');
    ok($document->{verified}, 'producer marks only complete evidence verified');
    my $report = File::Spec->catfile($dir, 'report.json');
    ($status, $output) = run($^X, $checker, '--requirements', $requirements,
        '--evidence', $final, '--expected-candidate', $candidate,
        '--mode', 'strict', '--output', $report);
    is($status, 0, 'strict checker independently revalidates all artifacts')
        or diag $output;
    ok(load($report)->{authoritative}, 'strict report is authoritative');
};

subtest 'hand-authored timing summary cannot pass' => sub {
    my $dir = tempdir(CLEANUP => 1);
    my $draft = File::Spec->catfile($dir, 'draft.json');
    write_raw($draft, $json->encode({
        schema_version => 1, kind => 'phase36-final-performance',
        baseline_seconds => [(10) x 5], candidate_seconds => [(9) x 5],
        alternating_order => JSON::PP::true,
    }));
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 1, 'summary without raw identity/TAP/JFR/NMT evidence fails');
    is(load($final)->{decision}, 'failed', 'failure is machine-readable');
};

subtest 'identity, raw hash, and DataLoss failures fail closed' => sub {
    for my $case (qw(parent hash dataloss summary-mismatch oversized-summary
            fake-launcher fake-java load-change timing-summary ordinary-summary)) {
        my ($dir, $draft) = fixture($case);
        my $final = File::Spec->catfile($dir, 'final.json');
        my ($status) = run($^X, $assembler, '--requirements', $requirements,
            '--input', $draft, '--output', $final);
        is($status, 1, "$case corruption is rejected");
        my $issues = join "\n", @{load($final)->{evaluation}{issues}};
        like($issues, $case eq 'parent' ? qr/exact performance baseline/
                : $case eq 'hash' ? qr/hash mismatch/
                : $case eq 'summary-mismatch' ? qr/differ from bounded raw-JFR replay/
                : $case eq 'oversized-summary' ? qr/exceeds its bounded size/
                : $case eq 'fake-launcher' ? qr/evidence-supplied JFR replay launcher is forbidden/
                : $case eq 'fake-java' ? qr/differs from authority-selected --java/
                : $case eq 'load-change' ? qr/competing owner set changed/
                : $case eq 'timing-summary' ? qr/hand-authored normalized timing summary is forbidden/
                : $case eq 'ordinary-summary' ? qr/declared timing summary differs from raw logs/
                : qr/data loss/i,
            "$case has a specific diagnostic");
    }
};

subtest 'authority-selected launchers, workloads, artifacts, and Git cannot be substituted' => sub {
    for my $case (qw(authority-launcher authority-benchmark authority-workload
            authority-producer authority-perl authority-evaluator
            authority-tap authority-time authority-command authority-admission
            candidate-git perl5-git)) {
        my ($dir, $draft) = fixture($case);
        my $final = File::Spec->catfile($dir, 'final.json');
        my ($status) = run($^X, $assembler, '--requirements', $requirements,
            '--input', $draft, '--output', $final);
        is($status, 1, "$case substitution is rejected");
        my $issues = join "\n", @{load($final)->{evaluation}{issues}};
        like($issues, $case eq 'candidate-git'
                ? qr/candidate source identity differs|actual Git parent/
                : $case eq 'perl5-git'
                ? qr/latest Perl identity differs/
                : $case eq 'authority-workload'
                ? qr/evidence source differs|authority evidence contract/
                : qr/authority evidence contract|authority HMAC/,
            "$case reaches an authority-specific diagnostic");
    }
};

subtest 'oversized decimals and incomplete GC pairing fail before arithmetic' => sub {
    for my $case (qw(huge-ordinary huge-time huge-rss huge-jfr incomplete-gc)) {
        my ($dir, $draft) = fixture($case);
        my $final = File::Spec->catfile($dir, 'final.json');
        my ($status) = run($^X, $assembler, '--requirements', $requirements,
            '--input', $draft, '--output', $final);
        is($status, 1, "$case is rejected");
        like(join("\n", @{load($final)->{evaluation}{issues}}),
            $case eq 'incomplete-gc' ? qr/incomplete GC pairing/
                : qr/malformed|bounded range|overflow|non-integral/i,
            "$case has a bounded numeric/completeness diagnostic");
    }
    my ($dir, $draft) = fixture();
    my $document = load($draft);
    my $bad_rules = load($requirements);
    $bad_rules->{performance_acceptance}{thresholds}
        {live_heap_absolute_allowance_bytes} = '9' x 200;
    my $evaluation = evaluate_performance($document, $bad_rules, $dir, {
        java => $trusted_java, authority_key => $authority_key,
        perl => $^X,
        baseline_source => $baseline_source, candidate_source => $candidate_source,
        perl5_source => $perl5_source, orchestrator => $orchestrator,
        ordinary_performance_producer => File::Spec->catfile($root, 'dev',
            'tools', 'run_phase36_regex_performance.pl'),
        performance_evaluator => File::Spec->catfile($root, 'dev', 'tools',
            'lib', 'PerlOnJava', 'Phase36PerformanceEvidence.pm'),
        benchmark => File::Spec->catfile($root, 'dev', 'tools',
            'phase36_regex_benchmark.pl'),
        jfr_metrics_producer => File::Spec->catfile($root, 'dev', 'tools',
            'Phase36JfrMetrics.java'), requirements => $requirements,
    });
    like(join("\n", @{$evaluation->{issues}}), qr/ratified performance threshold/,
        'oversized threshold is rejected before threshold arithmetic');
};

subtest 'recorded effective environment cannot contain ambient injection' => sub {
    my ($dir, $draft) = fixture('ambient-effective');
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 1, 'sealed ambient JVM injection is rejected');
    like(join("\n", @{load($final)->{evaluation}{issues}}),
        qr/effective environment leaks JAVA_TOOL_OPTIONS/,
        'effective-environment validator names the leaked variable');
};

subtest 'recorded launcher Java binding cannot be omitted or substituted' => sub {
    my ($dir, $draft) = fixture('java-env-tamper');
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 1, 'sealed launcher Java substitution is rejected');
    like(join("\n", @{load($final)->{evaluation}{issues}}),
        qr/effective Java path\/identity is not authority-selected/,
        'environment validator binds launcher Java to trusted --java identity');
};

subtest 'committed heap or RSS growth is a sealed non-passing review stop' => sub {
    my ($dir, $draft, $candidate) = fixture('review-stop');
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status, $output) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 2, 'producer returns the dedicated review-stop status') or diag $output;
    my $document = load($final);
    diag join("\n", @{$document->{evaluation}{issues}})
        if $document->{decision} ne 'review-stop';
    is($document->{decision}, 'review-stop', 'review stop is explicit');
    ok(!$document->{verified}, 'review stop never auto-verifies');
    ok($document->{evaluation}{review_stops}[0]{explanation_sealed},
        'review explanation is hash-sealed');
    ($status) = run($^X, $checker, '--requirements', $requirements,
        '--evidence', $final, '--expected-candidate', $candidate, '--mode', 'strict');
    is($status, 2, 'strict checker preserves the non-passing review stop');
};

subtest 'unsupported NMT is explicit and never guessed' => sub {
    my ($dir, $draft) = fixture('unsupported-nmt');
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 1, 'unsupported NMT blocks acceptance');
    like(join("\n", @{load($final)->{evaluation}{issues}}),
        qr/report unsupported NMT/, 'unsupported input is named');
};

subtest 'GNU time raw output is parsed without a normalized summary' => sub {
    my ($dir, $draft) = fixture('gnu-time');
    my $final = File::Spec->catfile($dir, 'final.json');
    my ($status, $output) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 0, 'GNU verbose time evidence passes') or diag $output;
    is(load($final)->{decision}, 'passed', 'GNU RSS kilobytes are normalized by trusted checker code');
};

subtest 'atomic assembler publication never exposes a partial final file' => sub {
    my ($dir, $draft) = fixture();
    my $final = File::Spec->catfile($dir, 'final.json');
    write_raw($final, "pre-existing sentinel\n");
    my ($status) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    isnt($status, 0, 'exclusive publication rejects a final-path collision');
    is(read_raw($final), "pre-existing sentinel\n", 'existing final is unchanged');
    my @staging = glob(File::Spec->catfile($dir, '.phase36-final-*'));
    is(scalar(@staging), 0, 'failed publication cleans private staging');
    unlink $final or die $!;
    ($status) = run($^X, $assembler, '--requirements', $requirements,
        '--input', $draft, '--output', $final);
    is($status, 0, 'same final pathname is retryable after failed publication');
    is(load($final)->{decision}, 'passed', 'retry publishes one complete document');
};

done_testing;

sub fixture {
    my ($mode) = @_;
    $mode //= 'valid';
    my $dir = tempdir(CLEANUP => 1);
    my $baseline = $baseline_commit;
    my $candidate = $candidate_commit;
    my %identity = (
        baseline_source_commit => $baseline,
        candidate_source_commit => $mode eq 'candidate-git' ? ('8' x 40) : $candidate,
        candidate_parent_commit => $mode eq 'parent' ? ('9' x 40) : $baseline,
        perl5_commit => $mode eq 'perl5-git' ? ('7' x 40) : $perl5_commit,
    );
    for my $field (qw(benchmark jfc jdk_version_log jfr_tool time_executable
            ordered_test_source ordered_fixture_manifest dbix_archive
            baseline_jar candidate_jar baseline_launcher candidate_launcher
            interpreter_launcher)) {
        $identity{$field} = artifact($dir, "sealed/$field", "$field fixture\n");
    }
    my $helper_source = read_raw(File::Spec->catfile($root, 'dev', 'tools',
        'Phase36JfrMetrics.java'));
    $identity{benchmark} = artifact($dir, 'sealed/phase36_regex_benchmark.pl',
        read_raw(File::Spec->catfile($root, 'dev', 'tools',
            'phase36_regex_benchmark.pl')));
    $identity{ordinary_performance_producer} = artifact($dir,
        'sealed/run_phase36_regex_performance.pl',
        read_raw(File::Spec->catfile($root, 'dev', 'tools',
            'run_phase36_regex_performance.pl')));
    $identity{performance_evaluator} = artifact($dir,
        'sealed/Phase36PerformanceEvidence.pm',
        read_raw(File::Spec->catfile($root, 'dev', 'tools', 'lib',
            'PerlOnJava', 'Phase36PerformanceEvidence.pm')));
    $identity{perl_interpreter} = artifact($dir, 'sealed/perl-interpreter',
        read_raw($^X), 1);
    my @cleared = qw(JPERL_OPTS JPERL_UNIMPLEMENTED PERL_SKIP_PSYCHO_TEST
        PERL_SKIP_BIG_MEM_TESTS JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
        JAVA_HOME CLASSPATH PERL5OPT PERL5LIB PERLLIB PERL_LOCAL_LIB_ROOT PERL_MB_OPT
        PERL_MM_OPT GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE PHASE36_SOURCE_COMMIT
        PHASE36_JAR_SHA256 PHASE36_PERFORMANCE_SIDE);
    $identity{execution_environment} = artifact($dir,
        'sealed/execution-environment.json', $json->encode({
            schema_version => 1, complete => JSON::PP::true,
            inheritance_allowlist => ['PATH'],
            forbidden_ambient => \@cleared,
            base_effective_environment => {
                PATH => '/usr/bin:/bin', LANG => 'C', LC_ALL => 'C', TZ => 'UTC',
                HOME => '/private/phase36/home',
                PERLONJAVA_HOME => '/private/phase36/home',
                TMPDIR => '/private/phase36/tmp',
                PERLONJAVA_JAVA_BIN => $trusted_java,
            },
        }));
    $identity{ordered_fixture_tree_manifest} = artifact($dir,
        'sealed/ordered-fixture-tree.json', $json->encode({
            schema_version => 1, complete => JSON::PP::true,
            entries => [],
        }));
    $identity{jfr_metrics_producer} = artifact($dir,
        'sealed/Phase36JfrMetrics.java', $helper_source);
    $identity{jdk_executable} = artifact($dir, 'sealed/java-binary',
        $mode eq 'fake-java' ? "#!/bin/sh\nprintf fake-pass\n"
            : read_raw($trusted_java));
    $identity{jfr_replay_launcher} = artifact($dir, 'sealed/attacker-java',
        "#!/bin/sh\nprintf '%s\\n' '{\"verified\":true}'\n", 1)
            if $mode eq 'fake-launcher';
    my %ordinary_artifacts = (
        benchmark => $identity{benchmark},
        baseline_jar => $identity{baseline_jar},
        candidate_jar => $identity{candidate_jar},
        baseline_launcher => $identity{baseline_launcher},
        candidate_launcher => $identity{candidate_launcher},
        java => $identity{jdk_executable},
        raw_logs => {},
    );
    for my $side (qw(baseline candidate)) {
        my $commit = $identity{"${side}_source_commit"};
        my $jar = $identity{"${side}_jar"}{sha256};
        my @seconds = $side eq 'baseline'
            ? (10, 10, 10.2, 10.1, 10.3, 10)
            : (9, 9, 9.2, 9.1, 9.3, 9);
        my @logs = (artifact($dir, "ordinary/$side-identity.log",
            "PerlOnJava source $commit\n"));
        for my $index (0 .. $#seconds) {
            my $elapsed = $seconds[$index];
            push @logs, artifact($dir, "ordinary/$side-$index.log",
                "PHASE36_REGEX_PERFORMANCE elapsed_seconds=$elapsed throughput="
                . (72000 / $elapsed)
                . " checksum=135a355df10cd13cd6bb7eb074e4aaf326b61057ab83753423033a50da258458"
                . " jar_sha256=$jar source_commit=$commit\n");
        }
        $ordinary_artifacts{raw_logs}{$side} = \@logs;
    }
    my $ordinary = {
        schema_version => 1, kind => 'performance', verified => JSON::PP::true,
        alternating_order => JSON::PP::true,
        execution_order => [map { qw(baseline candidate) } 1 .. 5],
        baseline_seconds => [10, 10.2, 10.1, 10.3, 10],
        candidate_seconds => [9, 9.2, 9.1, 9.3, 9],
        baseline_throughput => [7200, 72000 / 10.2, 72000 / 10.1,
            72000 / 10.3, 7200],
        candidate_throughput => [8000, 72000 / 9.2, 72000 / 9.1,
            72000 / 9.3, 8000],
        semantic_checksum =>
            '135a355df10cd13cd6bb7eb074e4aaf326b61057ab83753423033a50da258458',
        source => {
            baseline => { commit => $baseline, parent_commit => '0' x 40 },
            candidate => { commit => $candidate, parent_commit => $baseline },
        },
        artifacts => \%ordinary_artifacts,
    };
    $ordinary->{candidate_seconds}[0] = 1 if $mode eq 'ordinary-summary';
    if ($mode eq 'huge-ordinary') {
        my $huge = '9' x 200;
        $ordinary->{candidate_seconds}[0] = $huge;
        my $log = $ordinary_artifacts{raw_logs}{candidate}[2];
        substitute_artifact($dir, $log,
            "PHASE36_REGEX_PERFORMANCE elapsed_seconds=$huge throughput=1"
            . " checksum=$ordinary->{semantic_checksum}"
            . " jar_sha256=$identity{candidate_jar}{sha256}"
            . " source_commit=$identity{candidate_source_commit}\n");
    }
    my $ordinary_artifact = artifact($dir, 'ordinary/performance.json',
        $json->encode($ordinary));

    my @rows;
    for my $backend (qw(jvm interpreter)) {
        for my $spec (
            ['re/pat_psycho.t', 17, 2], ['re/pat_psycho_thr.t', 17, 2],
            ['re/speed.t', 59, 0], ['re/speed_thr.t', 59, 0]) {
            my ($test, $plan, $skips) = @$spec;
            (my $slug = "$backend-$test") =~ s{[^A-Za-z0-9]+}{-}g;
            my $tap = "1..$plan\n";
            for my $number (1 .. $plan) {
                my $skip = $number > $plan - $skips ? ' # skip architecture' : '';
                $tap .= "ok $number - fixture$skip\n";
            }
            my $test_source = artifact($dir, "inputs/$slug.t", "test $test\n");
            my $tap_artifact = artifact($dir, "psycho-speed/$slug.tap", $tap);
            my $launcher_sha = $identity{$backend eq 'jvm'
                ? 'candidate_launcher' : 'interpreter_launcher'}{sha256};
            my $command = artifact($dir, "psycho-speed/$slug-command.json",
                $json->encode({
                    schema_version => 1, authority_selected => JSON::PP::true,
                    argv => ["sealed/$backend-launcher", "inputs/$slug.t"],
                    timeout_seconds => 600,
                    source_commit => $candidate,
                    jar_sha256 => $identity{candidate_jar}{sha256},
                    launcher_sha256 => $launcher_sha,
                    test_source_sha256 => $test_source->{sha256},
                    environment_contract_sha256 =>
                        $identity{execution_environment}{sha256},
                }));
            push @rows, {
                backend => $backend, test => $test,
                source_commit => $candidate,
                jar_sha256 => $identity{candidate_jar}{sha256},
                launcher_sha256 => $launcher_sha,
                exit_code => 0, timeout => JSON::PP::false,
                truncated => JSON::PP::false,
                test_source => $test_source, tap => $tap_artifact,
                command => $command,
            };
        }
    }

    my @ordered;
    my @order = qw(baseline candidate candidate baseline);
    for my $index (0 .. $#order) {
        my $side = $order[$index];
        my $candidate_side = $side eq 'candidate';
        my $wall = $candidate_side ? 90 + $index : 100 + $index;
        my $user = $candidate_side ? 110 + $index : 120 + $index;
        my $rss = $candidate_side ? 840_000_000 : 800_000_000;
        $rss = 900_000_000 if $mode eq 'review-stop' && $candidate_side;
        my $root_alloc = $candidate_side ? 70 : 100;
        my $other_alloc = $candidate_side ? 830 : 900;
        my $live = $candidate_side ? 205_000_000 : 200_000_000;
        my $committed = $candidate_side ? 420_000_000 : 400_000_000;
        my $gnu_elapsed = sprintf('%d:%02d', int($wall / 60), $wall % 60);
        my $tap = "1..1271\n" . join('', map { "ok $_ - fixture\n" } 1 .. 1271)
            . "Auto checked 5 references for leaks - none detected\n";
        my $prefix = "ordered/$index-$side";
        my $recorded_metrics = {
            final_live_heap_bytes => $live,
            peak_committed_heap_bytes => $committed,
            total_allocation_bytes => $root_alloc + $other_alloc,
            root_reflective_allocation_bytes => $root_alloc,
            nmt_committed_bytes => 600_000_000,
            nmt_reserved_bytes => 1_000_000_000,
            data_loss_events => ($mode eq 'dataloss' && $candidate_side
                && $index == 1) ? 1 : 0,
            young_gc_count => $candidate_side ? 250 : 300,
            old_gc_count => $candidate_side ? 10 : 50,
            total_gc_pause_nanos => $candidate_side ? 500_000_000 : 600_000_000,
            max_gc_pause_nanos => $candidate_side ? 50_000_000 : 60_000_000,
        };
        $recorded_metrics->{total_allocation_bytes} = '9' x 200
            if $mode eq 'huge-jfr' && $candidate_side && $index == 1;
        my $recording = artifact($dir, "$prefix.jfr",
            $json->encode({
                metrics => $recorded_metrics,
                post_old_gc_observed => JSON::PP::true,
                gc_pairing_complete => ($mode eq 'incomplete-gc'
                    && $candidate_side && $index == 1)
                    ? JSON::PP::false : JSON::PP::true,
                nmt_status => ($mode eq 'unsupported-nmt' && $candidate_side
                    && $index == 1) ? 'unsupported' : 'supported',
            }));
        my $run = {
            side => $side,
            source_commit => $identity{"${side}_source_commit"},
            jar_sha256 => $identity{"${side}_jar"}{sha256},
            launcher_sha256 => $identity{"${side}_launcher"}{sha256},
            jdk_executable_sha256 => $identity{jdk_executable}{sha256},
            jdk_version_log_sha256 => $identity{jdk_version_log}{sha256},
            jfc_sha256 => $identity{jfc}{sha256},
            exit_code => 0, timeout => JSON::PP::false, timeout_seconds => 900,
            tap => artifact($dir, "$prefix.tap", $tap),
            time_raw => artifact($dir, "$prefix.time",
                ($mode eq 'timing-summary' && $candidate_side && $index == 1)
                    ? "PHASE36_TIME wall_seconds=1 user_seconds=1 system_seconds=0 max_rss_bytes=1\n"
                    : $mode eq 'gnu-time'
                    ? "User time (seconds): $user\nSystem time (seconds): 2\nElapsed (wall clock) time (h:mm:ss or m:ss): $gnu_elapsed\nMaximum resident set size (kbytes): " . int($rss / 1024) . "\n"
                    : ($mode eq 'huge-time' && $candidate_side && $index == 1)
                    ? (('9' x 200) . " real\n$user user\n2 sys\n$rss maximum resident set size\n")
                    : ($mode eq 'huge-rss' && $candidate_side && $index == 1)
                    ? "$wall real\n$user user\n2 sys\n" . ('9' x 200)
                        . " maximum resident set size\n"
                    : "$wall real\n$user user\n2 sys\n$rss maximum resident set size\n"),
            jfr_recording => $recording,
            jfr_summary => artifact($dir, "$prefix-summary.txt",
                "jdk.DataLoss 0\njdk.NativeMemoryUsageTotal 1\n"),
        };
        for my $field (qw(process_inventory_before process_inventory_after
                load_before load_after)) {
            $run->{$field} = artifact($dir, "$prefix-$field.log", "$field fixture\n");
        }
        my @owners = ('phase36-performance');
        push @owners, 'unrelated-build'
            if $mode eq 'load-change' && $index == 1;
        my $admission = {
            schema_version => 1, complete => JSON::PP::true,
            process_inventory_before_sha256 => $run->{process_inventory_before}{sha256},
            process_inventory_after_sha256 => $run->{process_inventory_after}{sha256},
            load_before_sha256 => $run->{load_before}{sha256},
            load_after_sha256 => $run->{load_after}{sha256},
            load_average_before => 0.5, load_average_after => 0.6,
            started_at => '2026-08-23T00:00:00Z',
            finished_at => '2026-08-23T00:10:00Z',
            active_expensive_owners_before => \@owners,
            active_expensive_owners_after => \@owners,
            unexpected_perlonjava_jvms => [],
        };
        $run->{load_admission} = artifact($dir, "$prefix-admission.json",
            $json->encode($admission));
        my $environment = {
            schema_version => 1, complete => JSON::PP::true,
            unset => \@cleared,
            base_environment_sha256 => $identity{execution_environment}{sha256},
            private_roots => {
                HOME => "/private/phase36/$index/home",
                PERLONJAVA_HOME => "/private/phase36/$index/perlonjava",
                TMPDIR => "/private/phase36/$index/tmp",
            },
            effective_environment => {
                PATH => '/usr/bin:/bin', LANG => 'C', LC_ALL => 'C', TZ => 'UTC',
                HOME => "/private/phase36/$index/home",
                PERLONJAVA_HOME => "/private/phase36/$index/perlonjava",
                TMPDIR => "/private/phase36/$index/tmp",
                PERLONJAVA_JAR => '/private/phase36/sealed/candidate.jar',
                JPERL_OPTS => '-XX:StartFlightRecording=fixture',
                PERLONJAVA_JAVA_BIN => $trusted_java,
            },
        };
        $environment->{effective_environment}{JAVA_TOOL_OPTIONS} = '-Xmx1g'
            if $mode eq 'ambient-effective' && $index == 0;
        $environment->{effective_environment}{PERLONJAVA_JAVA_BIN} =
            '/attacker/java' if $mode eq 'java-env-tamper' && $index == 0;
        $run->{environment} = artifact($dir, "$prefix-environment.json",
            $json->encode($environment));
        my $command = {
            schema_version => 1, authority_selected => JSON::PP::true,
            argv => ['timeout', '900', 'sealed/jperl', 't/87ordered.t'],
            timeout_seconds => 900,
            source_commit => $run->{source_commit},
            jar_sha256 => $run->{jar_sha256},
            launcher_sha256 => $run->{launcher_sha256},
            jdk_executable_sha256 => $run->{jdk_executable_sha256},
            jdk_version_log_sha256 => $run->{jdk_version_log_sha256},
            jfc_sha256 => $run->{jfc_sha256},
            jfr_tool_sha256 => $identity{jfr_tool}{sha256},
            jfr_metrics_producer_sha256 => $identity{jfr_metrics_producer}{sha256},
            time_executable_sha256 => $identity{time_executable}{sha256},
            ordered_test_source_sha256 => $identity{ordered_test_source}{sha256},
            ordered_fixture_manifest_sha256 => $identity{ordered_fixture_manifest}{sha256},
            ordered_fixture_tree_manifest_sha256 =>
                $identity{ordered_fixture_tree_manifest}{sha256},
            dbix_archive_sha256 => $identity{dbix_archive}{sha256},
            environment_sha256 => $run->{environment}{sha256},
            perl5_commit => $identity{perl5_commit},
            jfr_max_bytes => 2 * 1024 * 1024 * 1024,
            unset_environment => \@cleared,
        };
        $run->{command} = artifact($dir, "$prefix-command.json", $json->encode($command));
        my $sealed = {
            schema_version => 1, complete => JSON::PP::true,
            truncated => JSON::PP::false,
            identity => {
                jfr_recording_sha256 => $recording->{sha256},
                command_sha256 => $run->{command}{sha256},
                jfr_tool_sha256 => $identity{jfr_tool}{sha256},
                producer_sha256 => $identity{jfr_metrics_producer}{sha256},
                jdk_executable_sha256 => $identity{jdk_executable}{sha256},
                jdk_version_log_sha256 => $identity{jdk_version_log}{sha256},
                jfc_sha256 => $identity{jfc}{sha256},
            },
            metrics => $recorded_metrics,
            post_old_gc_observed => JSON::PP::true,
            gc_pairing_complete => ($mode eq 'incomplete-gc'
                && $candidate_side && $index == 1)
                ? JSON::PP::false : JSON::PP::true,
            nmt_status => ($mode eq 'unsupported-nmt' && $candidate_side
                && $index == 1) ? 'unsupported' : 'supported',
        };
        $sealed->{identity}{jfr_recording_sha256} = 'a' x 64
            if $mode eq 'summary-mismatch' && $candidate_side && $index == 1;
        my $sealed_text = $json->encode($sealed);
        $sealed_text = $json->encode({ oversized => 'x' x (1024 * 1024) })
            if $mode eq 'oversized-summary' && $candidate_side && $index == 1;
        $run->{jfr_metrics} = artifact($dir, "$prefix-metrics.json", $sealed_text);
        push @ordered, $run;
    }
    my @explanations;
    push @explanations, {
        metric => 'max_rss_bytes',
        artifact => artifact($dir, 'review/rss.md', "sealed RSS explanation\n"),
    } if $mode eq 'review-stop';
    my $document = {
        schema_version => 1, kind => 'phase36-final-performance',
        identity => \%identity,
        ordinary => { artifact => $ordinary_artifact },
        psycho_speed => { rows => \@rows },
        ordered => { runs => \@ordered },
        review_explanations => \@explanations,
    };
    $document->{identity}{candidate_jar}{sha256} = 'f' x 64 if $mode eq 'hash';
    $document->{authority} = seal_authority($document, $rules, {
        authority_key => $authority_key,
        baseline_source => $baseline_source,
        candidate_source => $candidate_source,
        perl5_source => $perl5_source,
        orchestrator => $orchestrator,
        ordinary_performance_producer => File::Spec->catfile($root, 'dev',
            'tools', 'run_phase36_regex_performance.pl'),
        performance_evaluator => File::Spec->catfile($root, 'dev', 'tools',
            'lib', 'PerlOnJava', 'Phase36PerformanceEvidence.pm'),
        benchmark => File::Spec->catfile($root, 'dev', 'tools',
            'phase36_regex_benchmark.pl'),
        perl => $^X,
        jfr_metrics_producer => File::Spec->catfile($root, 'dev', 'tools',
            'Phase36JfrMetrics.java'),
        requirements => $requirements,
    });
    if ($mode =~ /\Aauthority-(launcher|benchmark|producer|perl|evaluator|workload|tap|time|command|admission)\z/) {
        my $kind = $1;
        if ($kind eq 'launcher') {
            $document->{identity}{candidate_launcher}{sha256} = '6' x 64;
        } elsif ($kind eq 'benchmark') {
            $document->{identity}{benchmark}{sha256} = '6' x 64;
        } elsif ($kind eq 'producer') {
            $document->{identity}{ordinary_performance_producer}{sha256} = '6' x 64;
        } elsif ($kind eq 'perl') {
            $document->{identity}{perl_interpreter}{sha256} = '6' x 64;
        } elsif ($kind eq 'evaluator') {
            $document->{identity}{performance_evaluator}{sha256} = '6' x 64;
        } elsif ($kind eq 'workload') {
            substitute_artifact($dir,
                $document->{psycho_speed}{rows}[0]{test_source},
                "attacker-selected workload\n");
        } elsif ($kind eq 'tap') {
            substitute_artifact($dir, $document->{psycho_speed}{rows}[0]{tap},
                "1..1\nok 1 - forged\n");
        } elsif ($kind eq 'time') {
            substitute_artifact($dir, $document->{ordered}{runs}[0]{time_raw},
                "1 real\n1 user\n0 sys\n1 maximum resident set size\n");
        } elsif ($kind eq 'command') {
            substitute_artifact($dir, $document->{ordered}{runs}[0]{command},
                $json->encode({ schema_version => 1,
                    authority_selected => JSON::PP::true,
                    argv => ['attacker', 't/87ordered.t'] }));
        } elsif ($kind eq 'admission') {
            substitute_artifact($dir,
                $document->{ordered}{runs}[0]{load_admission},
                $json->encode({ schema_version => 1, complete => JSON::PP::true }));
        }
    }
    my $draft = File::Spec->catfile($dir, 'draft.json');
    write_raw($draft, $json->encode($document));
    return ($dir, $draft, $candidate);
}

sub artifact {
    my ($root_dir, $relative, $contents, $executable) = @_;
    my $path = File::Spec->catfile($root_dir, File::Spec->splitdir($relative));
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory) unless -d $directory;
    write_raw($path, $contents);
    chmod 0700, $path or die $! if $executable;
    return { path => $relative, sha256 => sha256_hex($contents),
        size => length($contents) };
}

sub substitute_artifact {
    my ($root_dir, $descriptor, $contents) = @_;
    my $path = File::Spec->catfile($root_dir,
        File::Spec->splitdir($descriptor->{path}));
    write_raw($path, $contents);
    $descriptor->{sha256} = sha256_hex($contents);
    $descriptor->{size} = length($contents);
}

sub fake_java {
    return <<'FAKE_JAVA';
#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA;
use JSON::PP;
shift @ARGV;
my %arg;
while (@ARGV) {
    my $name = shift @ARGV;
    my $value = shift @ARGV;
    $name =~ s/^--//;
    $arg{$name} = $value;
}
sub hash_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    my $hash = Digest::SHA->new(256)->addfile($fh)->hexdigest;
    close $fh or die $!;
    return $hash;
}
sub load_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    my $text = do { local $/; <$fh> };
    close $fh or die $!;
    return JSON::PP->new->decode($text);
}
my $raw = load_file($arg{recording});
my $result = {
    schema_version => 1,
    complete => JSON::PP::true,
    truncated => JSON::PP::false,
    identity => {
        jfr_recording_sha256 => hash_file($arg{recording}),
        command_sha256 => hash_file($arg{command}),
        jfr_tool_sha256 => hash_file($arg{'jfr-tool'}),
        producer_sha256 => hash_file($arg{helper}),
        jdk_executable_sha256 => hash_file($arg{'jdk-executable'}),
        jdk_version_log_sha256 => hash_file($arg{'jdk-version-log'}),
        jfc_sha256 => hash_file($arg{jfc}),
    },
    metrics => $raw->{metrics},
    post_old_gc_observed => $raw->{post_old_gc_observed},
    gc_pairing_complete => $raw->{gc_pairing_complete},
    nmt_status => $raw->{nmt_status},
};
print JSON::PP->new->canonical->pretty->encode($result);
FAKE_JAVA
}

sub run {
    my (@command) = @_;
    if (@command >= 2 && $command[0] eq $^X
            && ($command[1] eq $assembler || $command[1] eq $checker)) {
        splice @command, 2, 0,
            '--java', $trusted_java,
            '--perl', $^X,
            '--authority-key', $authority_key,
            '--baseline-source', $baseline_source,
            '--candidate-source', $candidate_source,
            '--perl5-source', $perl5_source;
    }
    my $output = qx{@command 2>&1};
    return ($? >> 8, $output);
}

sub load {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    my $text = do { local $/; <$fh> };
    close $fh or die $!;
    return JSON::PP->new->decode($text);
}

sub read_raw {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    my $text = do { local $/; <$fh> };
    close $fh or die $!;
    return $text;
}

sub write_raw {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die $!;
    print {$fh} $contents;
    close $fh or die $!;
}

sub trusted_repositories {
    my $candidate = File::Spec->catdir($trusted_dir, 'candidate-source');
    make_path($candidate);
    git_ok($candidate, 'init', '-q');
    git_ok($candidate, 'config', 'user.email', 'phase36-test@example.invalid');
    git_ok($candidate, 'config', 'user.name', 'Phase36 Test');
    write_raw(File::Spec->catfile($candidate, 'base.txt'), "baseline\n");
    git_ok($candidate, 'add', 'base.txt');
    git_ok($candidate, 'commit', '-q', '-m', 'baseline');
    my $baseline_sha = git_output($candidate, 'rev-parse', 'HEAD');
    for my $name (qw(run_phase36_final_performance.pl
            run_phase36_regex_performance.pl phase36_regex_benchmark.pl
            Phase36JfrMetrics.java)) {
        my $target = File::Spec->catfile($candidate, 'dev', 'tools', $name);
        make_path(dirname($target));
        write_raw($target, read_raw(File::Spec->catfile($root, 'dev', 'tools', $name)));
    }
    my $evaluator = File::Spec->catfile($candidate, 'dev', 'tools', 'lib',
        'PerlOnJava', 'Phase36PerformanceEvidence.pm');
    make_path(dirname($evaluator));
    write_raw($evaluator, read_raw(File::Spec->catfile($root, 'dev', 'tools',
        'lib', 'PerlOnJava', 'Phase36PerformanceEvidence.pm')));
    for my $test (qw(pat_psycho.t pat_psycho_thr.t speed.t speed_thr.t)) {
        my $target = File::Spec->catfile($candidate, 'perl5_t', 't', 're', $test);
        make_path(dirname($target));
        write_raw($target, "test re/$test\n");
    }
    git_ok($candidate, 'add', 'dev', 'perl5_t');
    git_ok($candidate, 'commit', '-q', '-m', 'candidate');
    my $candidate_sha = git_output($candidate, 'rev-parse', 'HEAD');
    my $baseline = File::Spec->catdir($trusted_dir, 'baseline-source');
    git_ok($candidate, 'worktree', 'add', '-q', '--detach', $baseline, $baseline_sha);

    my $perl5 = File::Spec->catdir($trusted_dir, 'perl5-source');
    make_path($perl5);
    git_ok($perl5, 'init', '-q');
    git_ok($perl5, 'config', 'user.email', 'phase36-test@example.invalid');
    git_ok($perl5, 'config', 'user.name', 'Phase36 Test');
    write_raw(File::Spec->catfile($perl5, 'perl5.txt'), "latest perl\n");
    git_ok($perl5, 'add', 'perl5.txt');
    git_ok($perl5, 'commit', '-q', '-m', 'latest perl');
    my $perl5_sha = git_output($perl5, 'rev-parse', 'HEAD');
    return ($baseline, $candidate, $perl5,
        $baseline_sha, $candidate_sha, $perl5_sha);
}

sub git_ok {
    my ($directory, @args) = @_;
    system('git', '-C', $directory, @args) == 0
        or die "git @args failed in $directory";
}

sub git_output {
    my ($directory, @args) = @_;
    open my $fh, '-|', 'git', '-C', $directory, @args or die $!;
    my $output = do { local $/; <$fh> };
    close $fh or die "git @args failed";
    $output =~ s/\s+\z//;
    return $output;
}
