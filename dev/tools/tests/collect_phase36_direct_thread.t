use strict;
use warnings;

use Digest::SHA;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'collect_phase36_direct_thread.pl');
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
        $fixture->{jvm}{results}{'t1.t'} = $replacement->();
        refresh_runner($fixture, 'jvm');
        my $document = rejected($fixture, qr/direct\/thread collection failed/,
            $name);
        is($document->{details}{$field}, 1, "$name has one canonical counter");
        is(scalar @{$document->{failures}{$field}}, 1,
            "$name retains one failure label");
    }

    my $mismatch = fixture('mismatch');
    $mismatch->{jvm}{results}{'d1.t'} = result(ok_count => 2,
        actual_tests_run => 2, planned_tests => 2);
    refresh_runner($mismatch, 'jvm');
    my $document = rejected($mismatch, qr/direct\/thread collection failed/,
        'thread carrier regression');
    is($document->{details}{mismatches}, 1, 'carrier mismatch is counted');
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
    my %runner_results = map {
        ($_->{direct} => result(), $_->{thread} => result())
    } @pairs;
    $runner_results{$thread_only} = result();
    my $jvm_path = File::Spec->catfile($directory, 'jvm.json');
    my $interpreter_path = File::Spec->catfile($directory, 'interpreter.json');
    my $jvm = {results => {%runner_results}};
    my $interpreter = {results => {%runner_results}};
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
    write_json($path, $fixture->{$backend});
    $fixture->{manifest}{artifacts}{"$backend-results.json"}{sha256}
        = sha256_file($path);
    refresh_manifest($fixture);
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
