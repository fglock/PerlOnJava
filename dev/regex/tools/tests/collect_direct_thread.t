use strict;
use warnings;

use Digest::SHA;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'collect_direct_thread.pl');
my $temporary = tempdir(CLEANUP => 1);
my @pairs = map { {direct => "d$_.t", thread => "t$_.t"} } 1 .. 10;
my $thread_only = 'only_thr.t';

subtest 'complete observed four-way matrix passes' => sub {
    my $fixture = fixture('green');
    my ($status, $text) = run_tool($fixture);
    is($status, 0, 'valid matrix passes');
    is($text, "$fixture->{output}\n", 'successful output path is reported');
    my $document = load_json($fixture->{output});
    ok($document->{verified}, 'evidence is verified');
    is($document->{kind}, 'direct-thread', 'evidence kind is canonical');
    is($document->{details}{expected_pairs}, 10, 'ten pairs expected');
    is($document->{details}{actual_pairs}, 10, 'ten pairs observed');
    is($document->{details}{actual_modes}, 4, 'all four pair modes observed');
    is($document->{details}{expected_thread_only}, 1, 'one thread-only test expected');
    is($document->{details}{actual_thread_only}, 1, 'one thread-only test observed');
    is($document->{details}{actual_thread_only_modes}, 2,
        'both thread-only backends observed');
    is(scalar @{$document->{details}{rows}}, 42,
        'forty pair rows and two thread-only rows retained');
    for my $field (qw(mismatches missing zero_tap timeouts truncated
            execution_issues)) {
        is($document->{details}{$field}, 0, "$field is zero");
    }
};

subtest 'identity and artifact hashes fail closed' => sub {
    my $bad_identity = fixture('bad-identity');
    $bad_identity->{manifest}{identity}{runner_commit} = '9' x 40;
    refresh_manifest($bad_identity);
    rejected($bad_identity, qr/runner commit differs from source/,
        'identity mismatch', 0);

    my $bad_hash = fixture('bad-runner-hash');
    $bad_hash->{manifest}{artifacts}{'jvm-results.json'}{sha256} = '0' x 64;
    refresh_manifest($bad_hash);
    rejected($bad_hash, qr/runner artifact hash mismatch/,
        'runner hash mismatch', 0);
};

subtest 'missing rows produce observed incomplete counts' => sub {
    my $fixture = fixture('missing');
    delete $fixture->{jvm}{results}{'d1.t'};
    refresh_runner($fixture, 'jvm');
    my $document = rejected($fixture, qr/direct\/thread collection failed/,
        'missing row');
    is($document->{details}{actual_pairs}, 9, 'actual pair count is observed, not copied');
    is($document->{details}{missing}, 1, 'missing row is counted');
    is_deeply($document->{failures}{missing}, ['jvm:d1.t'],
        'missing row identity is retained');
};

subtest 'timeout, incomplete, zero-TAP, malformed, and mismatch fail closed' => sub {
    my @cases = (
        ['timeout', sub { result(status => 'timeout') }, 'timeouts'],
        ['incomplete', sub { result(status => 'incomplete', ok_count => 1,
            actual_tests_run => 1, planned_tests => 2, incomplete_tests => 1) },
            'truncated'],
        ['zero-tap', sub { result(ok_count => 0, actual_tests_run => 0,
            planned_tests => 0) }, 'zero_tap'],
        ['malformed', sub { return {status => 'pass'} }, 'execution_issues'],
    );
    for my $case (@cases) {
        my ($name, $replacement, $field) = @$case;
        my $fixture = fixture($name);
        replace_result($fixture, 'jvm', 't1.t', $replacement->());
        refresh_runner($fixture, 'jvm');
        my $document = rejected($fixture, qr/direct\/thread collection failed/,
            $name);
        is($document->{details}{$field}, 1, "$name has one canonical counter");
        is(scalar @{$document->{failures}{$field}}, 1,
            "$name retains one failure label");
    }

    my $mismatch = fixture('mismatch');
    replace_result($mismatch, 'jvm', 'd1.t', result(ok_count => 2,
        actual_tests_run => 2, planned_tests => 2));
    refresh_runner($mismatch, 'jvm');
    my $document = rejected($mismatch, qr/direct\/thread collection failed/,
        'thread carrier regression');
    is($document->{details}{mismatches}, 1, 'carrier mismatch is counted');
};

subtest 'typed status counts and description-only differences are nonsemantic' => sub {
    my $fixture = fixture('typed-status-description');
    replace_result($fixture, 'jvm', 'd1.t', result(),
        "ok 1 - direct wording\n1..1\n");
    replace_result($fixture, 'jvm', 't1.t', result(),
        "ok 1 - threaded wording\n1..1\n");
    refresh_runner($fixture, 'jvm');
    my ($status) = run_tool($fixture);
    is($status, 0, 'description-only difference passes');
    my $document = load_json($fixture->{output});
    is($document->{details}{description_differences}, 1,
        'description difference is retained separately');
    is(scalar @{$document->{observations}{description_differences}}, 1,
        'description difference is outside the failure ledger');
    ok(!exists $document->{failures}{description_differences},
        'description wording does not flood semantic failures');
    is($document->{details}{assertion_status_mismatches}, 0,
        'description difference is not a semantic mismatch');
    is($document->{details}{status_counts}{jvm}{direct}{pass}, 10,
        'pass status is counted by string key');
    is($document->{details}{rows}[0]{status}, 'pass',
        'row status remains a string');
};

subtest 'shared failures require an exact machine-readable allowlist' => sub {
    my $fixture = fixture('allowlisted-shared-failure');
    for my $backend (qw(jvm interpreter)) {
        replace_result($fixture, $backend, 'd1.t', result(
            status => 'fail', ok_count => 0, not_ok_count => 1),
            "not ok 1 - direct performance boundary\n1..1\n");
        replace_result($fixture, $backend, 't1.t', result(
            status => 'fail', ok_count => 0, not_ok_count => 1),
            "not ok 1 - threaded performance boundary\n1..1\n");
        refresh_runner($fixture, $backend);
    }
    $fixture->{allowlist} = write_json(File::Spec->catfile(
        $fixture->{directory}, 'allowlist.json'), {
        schema_version => 1,
        entries => [map { +{
            backend => $_, direct => 'd1.t', thread => 't1.t', assertion => 1,
            classification => 'nonsemantic-performance-boundary',
        }} qw(jvm interpreter)],
    });
    my ($status) = run_tool($fixture);
    is($status, 0, 'exact allowlist admits shared failures');
    my $document = load_json($fixture->{output});
    is($document->{details}{classified_shared_failures}, 2,
        'both backend failures are classified');
    is($document->{details}{unclassified_shared_failures}, 0,
        'no shared failure remains unclassified');
    is($document->{details}{status_counts}{jvm}{direct}{fail}, 1,
        'fail status is counted without numeric coercion');
    is($document->{details}{description_differences}, 2,
        'different failure descriptions remain diagnostic only');

    my $unlisted = fixture('unlisted-shared-failure');
    for my $role (qw(d1.t t1.t)) {
        replace_result($unlisted, 'jvm', $role, result(
            status => 'fail', ok_count => 0, not_ok_count => 1));
    }
    refresh_runner($unlisted, 'jvm');
    my $rejected = rejected($unlisted,
        qr/direct\/thread collection failed/, 'unlisted shared failure');
    is($rejected->{details}{unclassified_shared_failures}, 1,
        'unlisted shared failure fails closed');

    my $stale = fixture('stale-allowlist');
    $stale->{allowlist} = write_json(File::Spec->catfile(
        $stale->{directory}, 'allowlist.json'), {
        schema_version => 1,
        entries => [{backend => 'jvm', direct => 'd1.t', thread => 't1.t',
            assertion => 1, classification => 'stale'}],
    });
    my $stale_document = rejected($stale,
        qr/direct\/thread collection failed/, 'unused allowlist entry');
    is($stale_document->{details}{unused_allowlist}, 1,
        'unused allowlist entries fail closed');

    my $malformed = fixture('malformed-allowlist');
    $malformed->{allowlist} = write_json(File::Spec->catfile(
        $malformed->{directory}, 'allowlist.json'), {
        schema_version => 1, entries => [{backend => 'jvm'}],
    });
    rejected($malformed, qr/malformed direct\/thread allowlist entry/,
        'malformed allowlist', 0);
};

subtest 'raw TAP is immutable and malformed shapes fail closed' => sub {
    for my $case (
        ['pass-with-failure', result(status => 'pass', ok_count => 0,
            not_ok_count => 1)],
        ['fail-without-failure', result(status => 'fail')],
        ['incoherent-counts', result(ok_count => 2)],
    ) {
        my ($name, $replacement) = @$case;
        my $fixture = fixture($name);
        replace_result($fixture, 'jvm', 't1.t', $replacement);
        refresh_runner($fixture, 'jvm');
        my $document = rejected($fixture,
            qr/direct\/thread collection failed/, "$name result shape");
        is($document->{details}{execution_issues}, 1,
            "$name fails closed as an execution issue");
    }

    my $duplicate = fixture('duplicate-tap');
    replace_result($duplicate, 'jvm', 't1.t', result(
        ok_count => 2, actual_tests_run => 2, planned_tests => 2),
        "ok 1 - first\nok 1 - duplicate\n1..2\n");
    refresh_runner($duplicate, 'jvm');
    my $duplicate_document = rejected($duplicate,
        qr/direct\/thread collection failed/, 'duplicate TAP assertion');
    is_deeply($duplicate_document->{failures}{execution_issues},
        ['jvm:t1.t:duplicate-tap-assertion'],
        'duplicate assertion has a precise failure label');

    my $count = fixture('tap-count-mismatch');
    replace_result($count, 'jvm', 't1.t', result(),
        "ok 1 - expected\nok 2 - extra\n1..2\n");
    refresh_runner($count, 'jvm');
    my $count_document = rejected($count,
        qr/direct\/thread collection failed/, 'raw TAP count mismatch');
    is_deeply($count_document->{failures}{execution_issues},
        ['jvm:t1.t:tap-count-mismatch'],
        'raw TAP count mismatch fails closed');
};

subtest 'supplemental artifact is retained but not projected as a row' => sub {
    my $fixture = fixture('supplemental');
    my $payload = write_file(File::Spec->catfile($fixture->{directory},
        'stclass.json'), "retained supplemental evidence\n");
    my $descriptor = write_json(File::Spec->catfile($fixture->{directory},
        'supplemental.json'), {path => $payload, sha256 => sha256_file($payload)});
    push @{$fixture->{supplemental}}, $descriptor;
    my ($status) = run_tool($fixture);
    is($status, 0, 'valid supplemental artifact passes');
    my $document = load_json($fixture->{output});
    is(scalar @{$document->{details}{rows}}, 42,
        'supplemental artifact does not inflate projected rows');
    is(scalar @{$document->{details}{supplemental_core_artifacts}}, 1,
        'supplemental descriptor is retained separately');

    my $bad = fixture('bad-supplemental');
    my $bad_payload = write_file(File::Spec->catfile($bad->{directory},
        'payload'), 'payload');
    my $bad_descriptor = write_json(File::Spec->catfile($bad->{directory},
        'descriptor.json'), {path => $bad_payload, sha256 => '0' x 64});
    push @{$bad->{supplemental}}, $bad_descriptor;
    rejected($bad, qr/supplemental core artifact hash mismatch/,
        'supplemental hash mismatch', 0);
};

subtest 'exclusive output collision fails before overwrite' => sub {
    my $fixture = fixture('collision');
    write_file($fixture->{output}, 'occupied');
    rejected($fixture, qr/Cannot create exclusive output/, 'output collision', 0);
    is(read_file($fixture->{output}), 'occupied', 'existing output is unchanged');
};

done_testing;

sub fixture {
    my ($name) = @_;
    my $directory = File::Spec->catdir($temporary, $name);
    make_path($directory);
    my $ledger_path = write_json(File::Spec->catfile($directory, 'ledger.json'), {
        direct_thread_pairs => \@pairs,
        thread_only_tests => [$thread_only],
    });
    my $jvm_path = File::Spec->catfile($directory, 'jvm.json');
    my $interpreter_path = File::Spec->catfile($directory, 'interpreter.json');
    my @tests = ((map { ($_->{direct}, $_->{thread}) } @pairs), $thread_only);
    my $jvm = {results => {map { $_ => result() } @tests}};
    my $interpreter = {results => {map { $_ => result() } @tests}};
    for my $backend ([jvm => $jvm], [interpreter => $interpreter]) {
        my ($name, $runner) = @$backend;
        for my $test (@tests) {
            (my $slug = $test) =~ s/[^A-Za-z0-9_.-]+/_/g;
            my $raw = File::Spec->catfile($directory, "$name-$slug.tap");
            $runner->{results}{$test}{raw_output_path} = $raw;
            write_file($raw, render_tap($test, $runner->{results}{$test}));
        }
    }
    write_json($jvm_path, $jvm);
    write_json($interpreter_path, $interpreter);
    my $manifest = {
        identity => {
            source_commit => 'a' x 40,
            runner_commit => 'a' x 40,
            perl5_commit => 'b' x 40,
            map { $_ => {sha256 => 'c' x 64} }
                qw(launcher jar sbom baseline),
        },
        artifacts => {
            'jvm-results.json' => {
                path => $jvm_path, sha256 => sha256_file($jvm_path)},
            'interpreter-results.json' => {
                path => $interpreter_path, sha256 => sha256_file($interpreter_path)},
        },
    };
    my $manifest_path = write_json(File::Spec->catfile($directory,
        'manifest.json'), $manifest);
    return {
        directory => $directory,
        ledger_path => $ledger_path,
        manifest_path => $manifest_path,
        manifest => $manifest,
        jvm_path => $jvm_path,
        interpreter_path => $interpreter_path,
        jvm => $jvm,
        interpreter => $interpreter,
        output => File::Spec->catfile($directory, 'output.json'),
        supplemental => [],
        tap_override => {jvm => {}, interpreter => {}},
        allowlist => undef,
    };
}

sub result {
    my (%override) = @_;
    return {
        status => 'pass', ok_count => 1, not_ok_count => 0,
        actual_tests_run => 1, planned_tests => 1, incomplete_tests => 0,
        %override,
    };
}

sub refresh_runner {
    my ($fixture, $backend) = @_;
    my $path = $fixture->{"${backend}_path"};
    for my $test (keys %{$fixture->{$backend}{results}}) {
        my $result = $fixture->{$backend}{results}{$test};
        my $raw = $result->{raw_output_path};
        next unless defined $raw;
        my $override = $fixture->{tap_override}{$backend}{$test};
        if (defined $override) {
            write_file($raw, $override);
        } elsif (defined($result->{actual_tests_run})
                && $result->{actual_tests_run} =~ /\A\d+\z/
                && defined($result->{ok_count}) && $result->{ok_count} =~ /\A\d+\z/
                && defined($result->{not_ok_count})
                && $result->{not_ok_count} =~ /\A\d+\z/) {
            write_file($raw, render_tap($test, $result));
        }
    }
    write_json($path, $fixture->{$backend});
    $fixture->{manifest}{artifacts}{"$backend-results.json"}{sha256}
        = sha256_file($path);
    refresh_manifest($fixture);
}

sub replace_result {
    my ($fixture, $backend, $test, $result, $tap) = @_;
    $result->{raw_output_path}
        = $fixture->{$backend}{results}{$test}{raw_output_path};
    $fixture->{$backend}{results}{$test} = $result;
    if (defined $tap) {
        $fixture->{tap_override}{$backend}{$test} = $tap;
    } else {
        delete $fixture->{tap_override}{$backend}{$test};
    }
}

sub render_tap {
    my ($test, $result) = @_;
    my $actual = $result->{actual_tests_run} // 0;
    my $not_ok = $result->{not_ok_count} // 0;
    my @lines;
    for my $number (1 .. $actual) {
        my $status = $number <= $not_ok ? 'not ok' : 'ok';
        push @lines, "$status $number - assertion $number";
    }
    push @lines, "1..$actual";
    return join("\n", @lines) . "\n";
}

sub refresh_manifest {
    my ($fixture) = @_;
    write_json($fixture->{manifest_path}, $fixture->{manifest});
}

sub run_tool {
    my ($fixture) = @_;
    my @command = ($^X, $tool,
        '--ledger', $fixture->{ledger_path},
        '--acceptance-manifest', $fixture->{manifest_path},
        '--output', $fixture->{output});
    push @command, map { ('--supplemental-core', $_) }
        @{$fixture->{supplemental}};
    push @command, ('--allowlist', $fixture->{allowlist})
        if defined $fixture->{allowlist};
    return capture(@command);
}

sub rejected {
    my ($fixture, $pattern, $name, $expect_evidence) = @_;
    $expect_evidence = 1 unless defined $expect_evidence;
    my ($status, $text) = run_tool($fixture);
    isnt($status, 0, "$name is rejected");
    like($text, $pattern, "$name has a specific diagnostic");
    return $expect_evidence ? load_json($fixture->{output}) : undef;
}

sub capture {
    my (@command) = @_;
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec {$command[0]} @command;
        die "exec: $!";
    }
    close $write;
    my $text = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $text);
}

sub write_json {
    my ($path, $value) = @_;
    return write_file($path, JSON::PP->new->canonical->encode($value));
}

sub load_json {
    my ($path) = @_;
    return JSON::PP->new->decode(read_file($path));
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
    return $path;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    return <$fh>;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    return $sha->hexdigest;
}
