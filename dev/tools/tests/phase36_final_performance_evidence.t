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
my $assembler = File::Spec->catfile($root, 'dev', 'tools',
    'assemble_phase36_final_performance.pl');
my $checker = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_final_performance.pl');
my $requirements = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_acceptance_requirements.json');
my $json = JSON::PP->new->canonical->pretty;
my $trusted_dir = tempdir(CLEANUP => 1);
my $trusted_java = File::Spec->catfile($trusted_dir, 'java');
write_raw($trusted_java, fake_java());
chmod 0700, $trusted_java or die $!;

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
    my $baseline = '1' x 40;
    my $candidate = '2' x 40;
    my %identity = (
        baseline_source_commit => $baseline,
        candidate_source_commit => $candidate,
        candidate_parent_commit => $mode eq 'parent' ? ('9' x 40) : $baseline,
        perl5_commit => '3' x 40,
    );
    for my $field (qw(benchmark jfc jdk_version_log jfr_tool time_executable
            ordered_test_source ordered_fixture_manifest dbix_archive
            baseline_jar candidate_jar baseline_launcher candidate_launcher
            interpreter_launcher)) {
        $identity{$field} = artifact($dir, "sealed/$field", "$field fixture\n");
    }
    my $helper_source = read_raw(File::Spec->catfile($root, 'dev', 'tools',
        'Phase36JfrMetrics.java'));
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
            push @rows, {
                backend => $backend, test => $test,
                source_commit => $candidate,
                jar_sha256 => $identity{candidate_jar}{sha256},
                launcher_sha256 => $identity{$backend eq 'jvm'
                    ? 'candidate_launcher' : 'interpreter_launcher'}{sha256},
                exit_code => 0, timeout => JSON::PP::false,
                truncated => JSON::PP::false,
                test_source => artifact($dir, "inputs/$slug.t", "test $test\n"),
                tap => artifact($dir, "psycho-speed/$slug.tap", $tap),
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
        my $recording = artifact($dir, "$prefix.jfr",
            $json->encode({
                metrics => $recorded_metrics,
                post_old_gc_observed => JSON::PP::true,
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
            unset => ['JPERL_UNIMPLEMENTED', 'PERL_SKIP_PSYCHO_TEST'],
            private_roots => {
                HOME => "/private/phase36/$index/home",
                PERLONJAVA_HOME => "/private/phase36/$index/perlonjava",
                TMPDIR => "/private/phase36/$index/tmp",
            },
        };
        $run->{environment} = artifact($dir, "$prefix-environment.json",
            $json->encode($environment));
        my $command = {
            schema_version => 1,
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
            dbix_archive_sha256 => $identity{dbix_archive}{sha256},
            environment_sha256 => $run->{environment}{sha256},
            perl5_commit => $identity{perl5_commit},
            jfr_max_bytes => 2 * 1024 * 1024 * 1024,
            unset_environment => ['JPERL_UNIMPLEMENTED'],
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
    nmt_status => $raw->{nmt_status},
};
print JSON::PP->new->canonical->pretty->encode($result);
FAKE_JAVA
}

sub run {
    my (@command) = @_;
    if (@command >= 2 && $command[0] eq $^X
            && ($command[1] eq $assembler || $command[1] eq $checker)) {
        splice @command, 2, 0, '--java', $trusted_java;
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
