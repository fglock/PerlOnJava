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
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'run_cpan_acceptance.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fixture = File::Spec->catdir($temporary, 'fixture with spaces');
make_path($fixture);
my $source = File::Spec->catdir($fixture, 'source checkout');
make_path($source);
system('git', 'init', '-q', $source) == 0 or die 'git init';
system('git', '-C', $source, 'config', 'user.email', 'fixture@example.test') == 0 or die 'git config';
system('git', '-C', $source, 'config', 'user.name', 'Fixture') == 0 or die 'git config';
write_file(File::Spec->catfile($source, 'tracked'), "fixture\n");
system('git', '-C', $source, 'add', 'tracked') == 0 or die 'git add';
system('git', '-C', $source, 'commit', '-qm', 'fixture') == 0 or die 'git commit';
my $commit = capture('git', '-C', $source, 'rev-parse', 'HEAD');
$commit =~ s/\s+\z//;

my $bin = File::Spec->catdir($fixture, 'fake bin');
make_path($bin);
my $jperl = File::Spec->catfile($bin, 'jperl fake');
write_file($jperl, <<'JPERL');
#!/usr/bin/env perl
if (($ENV{FAKE_SCENARIO} // '') eq 'identity') {
    print "PerlOnJava fake deadbee\n";
} else {
    print "PerlOnJava fake $ENV{FAKE_SOURCE_COMMIT}\n";
}
JPERL
my $jcpan = File::Spec->catfile($bin, 'jcpan fake');
write_file($jcpan, <<'JCPAN');
#!/usr/bin/env perl
use strict;
use warnings;
my $scenario = $ENV{FAKE_SCENARIO} // 'success';
my $selected_backend = !exists($ENV{JPERL_INTERPRETER}) ? 'jvm'
    : ($ENV{JPERL_INTERPRETER} // '') eq '1' ? 'interpreter' : 'invalid';
print "REGEX_IMPLEMENTATION_BACKEND target=$ENV{REGEX_IMPLEMENTATION_CPAN_TARGET} "
    . "requested=$ENV{REGEX_IMPLEMENTATION_CPAN_MODE} selected=$selected_backend\n";
if ($scenario eq 'timeout') { sleep 20; exit 0 }
if ($scenario eq 'timeout-descendant') {
    my $child = fork();
    die "fork: $!" unless defined $child;
    if (!$child) { sleep 20; exit 0 }
    open my $fh, '>:raw', $ENV{FAKE_DESCENDANT_PID} or die $!;
    print {$fh} "$child\n";
    close $fh;
    sleep 20;
    exit 0;
}
if ($scenario eq 'signal') { kill 'TERM', $$; sleep 1; exit 0 }
if ($scenario eq 'execution') { print STDERR "Cannot execute nested test program\n"; exit 255 }
if ($scenario eq 'mutation') {
    open my $fh, '>>:raw', $ENV{FAKE_MUTATE_PATH} or die $!;
    print {$fh} " \n";
    close $fh;
}
if ($scenario eq 'zero') { print "No tests found\n"; exit 0 }
if ($scenario eq 'truncated') { print "ok 1 - partial\n1..2\n"; exit 0 }
if ($scenario eq 'malformed') {
    print "ok 1 - malformed\n1..bogus\nFiles=1, Tests=1, 0 wallclock secs\nParse errors: Bad plan\n";
    exit 0;
}
if ($scenario eq 'failure') {
    print "not ok 1 - failed\n1..1\nFiles=1, Tests=1, 0 wallclock secs\n";
    exit 1;
}
print "ok 1 - $ENV{REGEX_IMPLEMENTATION_CPAN_TARGET} $ENV{REGEX_IMPLEMENTATION_CPAN_MODE}\n";
print "1..1\nAll tests successful.\nFiles=1, Tests=1, 0 wallclock secs\n";
print STDERR "Use of uninitialized value in warning fixture\n" if $scenario eq 'warning';
print STDERR "Use of uninitialized value in substitution (s///) at t/dbic.t line 42.\n"
    if $scenario eq 'dbix-warning';
print STDERR "Subroutine Moo::Role::apply redefined at lib/Moo/Role.pm line 77.\n"
    if $scenario eq 'moo-warning';
exit 0;
JCPAN
chmod 0755, $jperl, $jcpan or die 'chmod fake launchers';
my $jar = File::Spec->catfile($fixture, 'artifact exact.jar');
my $sbom = File::Spec->catfile($fixture, 'combined exact.json');
write_file($jar, "jar\n");
write_file($sbom, "{}\n");
my $policy = File::Spec->catfile($fixture, 'target policy.json');
write_json($policy, policy_document());
my $manifest = File::Spec->catfile($fixture, 'acceptance manifest.json');
write_json($manifest, manifest_document());

local $ENV{FAKE_SOURCE_COMMIT} = $commit;

my ($compile_status, $compile_output) = run_command($^X, '-c', $tool);
is($compile_status, 0, 'runner compiles with system Perl');
is($compile_output, "$tool syntax OK\n", 'runner compilation is warning-free');

subtest 'checked-in policy contains the complete affected release set' => sub {
    my $checked = load_json(File::Spec->catfile($root, 'dev', 'regex', 'tools',
        'cpan_targets.json'));
    is_deeply($checked->{expected_targets}, [qw(DBIx::Class DateTime Moo
        Regexp::Common String::Random Template Type::Tiny WWW::Mechanize)],
        'eight exact targets including Moo and Template are immutable policy');
    is(scalar @{$checked->{targets}}, 8, 'one policy entry exists per target');
    ok(!(grep { !length($_->{rationale} // '') || ($_->{timeout_seconds} // 0) <= 0
        || !@{$_->{required_modes} // []} } @{$checked->{targets}}),
        'every target has rationale, bound, and required modes');
    ok(!(grep { join(',', sort @{$_->{required_modes} // []}) ne 'interpreter,jvm' }
        @{$checked->{targets}}),
        'every release target requires both JVM and interpreter');
};

subtest 'successful control plane is immutable and canonical' => sub {
    local $ENV{FAKE_SCENARIO} = 'success';
    my $evidence = evidence('successful run');
    my ($status, $output) = run_runner($evidence);
    is($status, 0, 'fake matrix succeeds');
    like($output, qr/Regex implementation CPAN acceptance/, 'runner reports retained manifest');
    my $result = load_json(File::Spec->catfile($evidence, 'cpan-acceptance.json'));
    is($result->{status}, 'pass', 'aggregate status passes');
    is($result->{total_tests}, 4, 'all required target/mode tests are totaled');
    is_deeply($result->{expected_targets}, ['Target::One', 'Target::Two'],
        'exact target policy is retained');
    is_deeply([sort keys %{$result->{results}}], ['Target::One', 'Target::Two'],
        'one result exists per target');
    is($result->{results}{'Target::One'}{modes}{interpreter}
        {environment}{JPERL_INTERPRETER}, 1, 'interpreter mode is explicit');
    ok(!defined($result->{results}{'Target::One'}{modes}{jvm}
        {environment}{JPERL_INTERPRETER}), 'JVM mode removes inherited interpreter selection');
    is($result->{results}{'Target::One'}{modes}{jvm}
        {environment}{PERLONJAVA_JAR}, $jar, 'each child is bound to supplied JAR');
    ok(-d $result->{results}{'Target::Two'}{modes}{jvm}
        {environment}{PERLONJAVA_HOME}, 'private writable CPAN home exists');
    for my $target ('Target::One', 'Target::Two') {
        for my $mode (qw(jvm interpreter)) {
            my $raw_path = File::Spec->catfile($evidence,
                $result->{results}{$target}{modes}{$mode}{raw_log}{path});
            like(read_file($raw_path),
                qr/^REGEX_IMPLEMENTATION_BACKEND target=\Q$target\E requested=\Q$mode\E selected=\Q$mode\E$/m,
                "$target $mode reaches the fake jcpan command with the intended backend");
        }
    }

    my ($collision_status, $collision_output) = run_runner($evidence);
    is($collision_status, 255, 'nonempty output is rejected without resume');
    like($collision_output, qr/Refusing nonempty evidence directory/, 'collision diagnostic is exact');
    my ($resume_status, $resume_output) = run_runner($evidence, '--resume');
    is($resume_status, 0, 'safe resume verifies and accepts retained evidence');
    like($resume_output, qr/Safe resume verified/, 'resume reports verification');

    my $result_path = File::Spec->catfile($evidence, 'cpan-acceptance.json');
    my $incomplete = clone($result);
    pop @{$incomplete->{artifacts}};
    write_json($result_path, $incomplete);
    seal_result($result_path);
    my ($missing_status, $missing_output) = run_runner($evidence, '--resume');
    is($missing_status, 255, 'resume rejects an omitted expected descriptor');
    like($missing_output, qr/Retained artifact set is incomplete/,
        'resume computes the complete expected artifact set');

    my $unsafe = clone($result);
    $unsafe->{artifacts}[0]{path} = File::Spec->catfile(File::Spec->rootdir, 'outside');
    write_json($result_path, $unsafe);
    seal_result($result_path);
    my ($absolute_status, $absolute_output) = run_runner($evidence, '--resume');
    is($absolute_status, 255, 'resume rejects absolute retained paths');
    like($absolute_output, qr/Retained artifact path is unsafe/,
        'absolute path has a confinement diagnostic');

    $unsafe = clone($result);
    $unsafe->{artifacts}[0]{path} = File::Spec->catfile('..', 'outside');
    write_json($result_path, $unsafe);
    seal_result($result_path);
    my ($parent_status, $parent_output) = run_runner($evidence, '--resume');
    is($parent_status, 255, 'resume rejects parent traversal paths');
    like($parent_output, qr/Retained artifact path is unsafe/,
        'parent traversal has a confinement diagnostic');

    write_json($result_path, $result);
    seal_result($result_path);

    my $raw = File::Spec->catfile($evidence,
        $result->{results}{'Target::One'}{modes}{jvm}{raw_log}{path});
    SKIP: {
        my $raw_contents = read_file($raw);
        my $outside = File::Spec->catfile($fixture, 'outside retained raw.log');
        write_file($outside, $raw_contents);
        unlink $raw or die "Cannot replace fixture raw log: $!";
        if (!symlink($outside, $raw)) {
            write_file($raw, $raw_contents);
            skip 'symlinks unavailable on this platform', 2;
        }
        my ($symlink_status, $symlink_output) = run_runner($evidence, '--resume');
        is($symlink_status, 255, 'resume rejects retained symlink escaping evidence root');
        like($symlink_output, qr/resolves outside evidence root/,
            'symlink escape has a confinement diagnostic');
        unlink $raw or die "Cannot remove fixture symlink: $!";
        write_file($raw, $raw_contents);
    }
    open my $fh, '>>:raw', $raw or die $!;
    print {$fh} "tamper\n";
    close $fh;
    my ($tamper_status, $tamper_output) = run_runner($evidence, '--resume');
    is($tamper_status, 255, 'resume rejects mutated retained artifact');
    like($tamper_output, qr/Retained artifact hash mismatch/, 'resume names hash mismatch');
};

subtest 'every target policy is obligatorily dual-backend' => sub {
    my $single_mode_policy = File::Spec->catfile($fixture, 'single mode policy.json');
    my $single = policy_document();
    $single->{targets}[0]{required_modes} = ['jvm'];
    write_json($single_mode_policy, $single);
    my ($status, $output) = run_runner(evidence('single mode policy'),
        '--policy', $single_mode_policy);
    is($status, 255, 'a JVM-only target is rejected before execution');
    like($output, qr/must require JVM and interpreter/,
        'single-mode policy has an exact backend diagnostic');
};

subtest 'sealed resume rejects missing and duplicate backend results' => sub {
    local $ENV{FAKE_SCENARIO} = 'success';
    my $evidence = evidence('resume backend integrity');
    my ($status) = run_runner($evidence);
    is($status, 0, 'backend-integrity fixture starts sealed and green');
    my $result_path = File::Spec->catfile($evidence, 'cpan-acceptance.json');
    my $original = load_json($result_path);

    my $missing = clone($original);
    delete $missing->{results}{'Target::One'}{modes}{interpreter};
    write_json($result_path, $missing);
    seal_result($result_path);
    my ($missing_status, $missing_output) = run_runner($evidence, '--resume');
    is($missing_status, 255, 'resume rejects a missing aggregate interpreter result');
    like($missing_output, qr/Retained mode set drift/,
        'missing mode has an exact semantic-integrity diagnostic');

    write_json($result_path, $original);
    seal_result($result_path);
    my $interpreter_meta_relative = $original->{results}{'Target::One'}
        {modes}{interpreter}{raw_log}{path};
    $interpreter_meta_relative =~ s/raw\.log\z/result.json/;
    my $interpreter_meta = File::Spec->catfile($evidence,
        File::Spec->splitdir($interpreter_meta_relative));
    my $jvm_copy = clone($original->{results}{'Target::One'}{modes}{jvm});
    write_json($interpreter_meta, $jvm_copy);
    my $duplicate = clone($original);
    $duplicate->{results}{'Target::One'}{modes}{interpreter} = $jvm_copy;
    for my $artifact (@{$duplicate->{artifacts}}) {
        next unless $artifact->{path} eq $interpreter_meta_relative;
        $artifact->{sha256} = sha256_file($interpreter_meta);
    }
    write_json($result_path, $duplicate);
    seal_result($result_path);
    my ($duplicate_status, $duplicate_output) = run_runner($evidence, '--resume');
    is($duplicate_status, 255, 'resume rejects a JVM result copied into interpreter evidence');
    like($duplicate_output, qr/Retained mode result identity mismatch/,
        'duplicate JVM result has an exact backend-identity diagnostic');
};

subtest 'failed evidence cannot be resealed as passing' => sub {
    for my $case (
        ['zero', 'zero TAP', []],
        ['warning', 'unapproved warning', []],
    ) {
        my ($scenario, $label, $extra) = @$case;
        local $ENV{FAKE_SCENARIO} = $scenario;
        my $evidence = evidence("launder $scenario");
        my ($run_status) = run_runner($evidence, @$extra);
        is($run_status, 1, "$label fixture initially fails");
        reseal_as_pass($evidence);
        my ($resume_status, $resume_output) = run_runner($evidence,
            @$extra, '--resume');
        is($resume_status, 255, "$label cannot be resealed as passing");
        like($resume_output, qr/Retained mode analysis mismatch/,
            "$label laundering is detected from retained raw TAP");
    }

    my $fast_policy = File::Spec->catfile($fixture, 'launder timeout policy.json');
    my $doc = policy_document();
    $_->{timeout_seconds} = 1 for @{$doc->{targets}};
    write_json($fast_policy, $doc);
    local $ENV{FAKE_SCENARIO} = 'timeout';
    my $timeout_evidence = evidence('launder timeout');
    my ($timeout_status) = run_runner($timeout_evidence,
        '--policy', $fast_policy);
    is($timeout_status, 1, 'timeout fixture initially fails');
    reseal_as_pass($timeout_evidence);
    my ($resume_status, $resume_output) = run_runner($timeout_evidence,
        '--policy', $fast_policy, '--resume');
    is($resume_status, 255, 'timeout cannot be resealed as passing');
    like($resume_output, qr/Retained mode analysis mismatch/,
        'timeout laundering is detected from retained execution metadata');
};

for my $case (
    ['failure', 'failure', qr/status.*fail/s, 'nonzero/TAP failure'],
    ['zero', 'zero', qr/zero_tap/s, 'zero TAP'],
    ['truncated', 'truncated', qr/truncated/s, 'truncated TAP'],
    ['malformed', 'malformed', qr/"malformed"\s*:\s*true/s, 'malformed TAP'],
    ['warning', 'warning', qr/unapproved_warnings/s, 'unapproved warning'],
    ['dbix-warning', 'dbix-warning', qr/Use of uninitialized value in substitution/s,
        'DBIx uninitialized warning'],
    ['moo-warning', 'moo-warning', qr/Subroutine Moo::Role::apply redefined/s,
        'Moo redefinition warning'],
    ['execution', 'execution', qr/execution_error/s, 'execution error'],
    ['signal', 'signal', qr/"signal"\s*:\s*15/s, 'child signal'],
) {
    my ($name, $scenario, $pattern, $label) = @$case;
    subtest "$label is fail-closed" => sub {
        local $ENV{FAKE_SCENARIO} = $scenario;
        my $evidence = evidence($name);
        my ($status) = run_runner($evidence);
        is($status, 1, "$label fails aggregate acceptance");
        my $text = read_file(File::Spec->catfile($evidence, 'cpan-acceptance.json'));
        like($text, $pattern, "$label is retained in canonical evidence");
    };
}

subtest 'hard timeout is recorded' => sub {
    local $ENV{FAKE_SCENARIO} = 'timeout';
    my $fast_policy = File::Spec->catfile($fixture, 'timeout policy.json');
    my $doc = policy_document();
    $_->{timeout_seconds} = 1 for @{$doc->{targets}};
    write_json($fast_policy, $doc);
    my ($status) = run_runner(evidence('timeout'), '--policy', $fast_policy);
    is($status, 1, 'timeout fails aggregate acceptance');
    my $result = load_json(File::Spec->catfile(evidence('timeout'), 'cpan-acceptance.json'));
    ok($result->{results}{'Target::One'}{timeout}, 'target timeout is retained');
    ok($result->{results}{'Target::One'}{modes}{jvm}{timeout}, 'mode timeout is retained');

    my $pid_file = File::Spec->catfile($fixture, 'timeout descendant.pid');
    local $ENV{FAKE_SCENARIO} = 'timeout-descendant';
    local $ENV{FAKE_DESCENDANT_PID} = $pid_file;
    my ($descendant_status) = run_runner(evidence('timeout descendant'),
        '--policy', $fast_policy);
    is($descendant_status, 1, 'descendant launcher times out fail-closed');
    my $descendant = 0 + read_file($pid_file);
    for (1 .. 20) {
        last unless kill 0, $descendant;
        select undef, undef, undef, 0.05;
    }
    my $alive = kill 0, $descendant;
    ok(!$alive, 'timeout leaves no spawned descendant alive');
    kill 'KILL', $descendant if $alive;
};

subtest 'identity and input hashes fail before execution' => sub {
    local $ENV{FAKE_SCENARIO} = 'identity';
    my ($identity_status, $identity_output) = run_runner(evidence('identity'));
    is($identity_status, 255, 'runner identity mismatch is fatal');
    like($identity_output, qr/does not report runner\/source commit/, 'identity diagnostic is exact');

    local $ENV{FAKE_SCENARIO} = 'success';
    my $bad_manifest = File::Spec->catfile($fixture, 'bad hash manifest.json');
    my $bad = manifest_document();
    $bad->{identity}{jar_sha256} = '0' x 64;
    write_json($bad_manifest, $bad);
    my ($hash_status, $hash_output) = run_runner(evidence('hash'), '--manifest', $bad_manifest);
    is($hash_status, 255, 'manifest/input hash mismatch is fatal');
    like($hash_output, qr/Manifest JAR hash differs/, 'hash diagnostic is exact');
};

subtest 'target-set drift and input mutation are fatal' => sub {
    my $drift_policy = File::Spec->catfile($fixture, 'drift policy.json');
    my $drift = policy_document();
    pop @{$drift->{expected_targets}};
    write_json($drift_policy, $drift);
    my ($drift_status, $drift_output) = run_runner(evidence('drift'), '--policy', $drift_policy);
    is($drift_status, 255, 'policy target-set drift is fatal');
    like($drift_output, qr/expected\/result set drift/, 'target drift diagnostic is exact');

    my $moo_policy = File::Spec->catfile($fixture, 'Moo omission policy.json');
    my $moo_omission = load_json(File::Spec->catfile($root, 'dev', 'regex', 'tools',
        'cpan_targets.json'));
    @{$moo_omission->{expected_targets}} = grep { $_ ne 'Moo' }
        @{$moo_omission->{expected_targets}};
    write_json($moo_policy, $moo_omission);
    my ($moo_status, $moo_output) = run_runner(evidence('Moo omission'),
        '--policy', $moo_policy);
    is($moo_status, 255, 'omitting Moo from expected targets is fatal');
    like($moo_output, qr/expected\/result set drift/,
        'Moo omission has the exact target drift diagnostic');

    my $template_policy = File::Spec->catfile($fixture,
        'Template omission policy.json');
    my $template_omission = load_json(File::Spec->catfile($root, 'dev', 'regex', 'tools',
        'cpan_targets.json'));
    @{$template_omission->{expected_targets}} = grep { $_ ne 'Template' }
        @{$template_omission->{expected_targets}};
    write_json($template_policy, $template_omission);
    my ($template_status, $template_output) = run_runner(
        evidence('Template omission'), '--policy', $template_policy);
    is($template_status, 255, 'omitting Template from expected targets is fatal');
    like($template_output, qr/expected\/result set drift/,
        'Template omission has the exact target drift diagnostic');

    local $ENV{FAKE_SCENARIO} = 'mutation';
    local $ENV{FAKE_MUTATE_PATH} = $policy;
    my ($mutation_status, $mutation_output) = run_runner(evidence('mutation'));
    is($mutation_status, 255, 'protected input mutation is fatal');
    like($mutation_output, qr/Protected input mutated during execution: policy/,
        'mutation diagnostic names protected policy');
};

done_testing;

sub run_runner {
    my ($evidence, @extra) = @_;
    my @command = ($^X, $tool, '--prepare-only', '--manifest', $manifest,
        '--policy', $policy, '--evidence-dir', $evidence,
        '--jperl', $jperl, '--jcpan', $jcpan, @extra);
    for (my $i = 0; $i < @command; $i++) {
        if ($command[$i] eq '--manifest' && $extra[0] && $extra[0] eq '--manifest') {
            splice @command, $i, 2;
            last;
        }
        if ($command[$i] eq '--policy' && $extra[0] && $extra[0] eq '--policy') {
            splice @command, $i, 2;
            last;
        }
    }
    pipe my $read, my $write or die $!;
    my $pid = fork();
    die $! unless defined $pid;
    if (!$pid) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $command[0] } @command;
        die $!;
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub policy_document {
    return {
        schema_version => 1,
        expected_targets => ['Target::One', 'Target::Two'],
        targets => [
            { name => 'Target::One', rationale => 'regex fixture', timeout_seconds => 10,
              required_modes => ['jvm', 'interpreter'],
              focused_selector_permitted => JSON::PP::false,
              approved_warning_patterns => [] },
            { name => 'Target::Two', rationale => 'warning fixture', timeout_seconds => 10,
              required_modes => ['jvm', 'interpreter'], focused_selector_permitted => JSON::PP::true,
              approved_warning_patterns => [] },
        ],
    };
}

sub manifest_document {
    my $jperl_sha = sha256_file($jperl);
    return {
        schema_version => 1, mode => 'acceptance',
        identity => {
            source_commit => $commit, runner_commit => $commit, perl5_commit => $commit,
            jperl_sha256 => $jperl_sha, jar_sha256 => sha256_file($jar),
            sbom_sha256 => sha256_file($sbom),
        },
        inputs => {
            source => { path => $source, commit => $commit },
            perl5 => { path => $source, commit => $commit },
            jperl => { path => $jperl, sha256 => $jperl_sha },
            jcpan => { path => $jcpan, sha256 => sha256_file($jcpan) },
            jar => { path => $jar, sha256 => sha256_file($jar) },
            sbom => { path => $sbom, sha256 => sha256_file($sbom) },
        },
    };
}

sub evidence { File::Spec->catdir($fixture, "evidence $_[0]") }
sub write_file { my ($p,$c)=@_; open my $f,'>:raw',$p or die $!; print {$f} $c; close $f or die $! }
sub read_file { my ($p)=@_; open my $f,'<:raw',$p or die $!; local $/; my $c=<$f>; close $f; return $c }
sub write_json { write_file($_[0], JSON::PP->new->canonical->pretty->encode($_[1])) }
sub load_json { JSON::PP->new->decode(read_file($_[0])) }
sub clone { JSON::PP->new->decode(JSON::PP->new->encode($_[0])) }
sub seal_result {
    my ($path) = @_;
    write_file("$path.sha256", sha256_file($path) . "  cpan-acceptance.json\n");
}
sub reseal_as_pass {
    my ($evidence) = @_;
    my $path = File::Spec->catfile($evidence, 'cpan-acceptance.json');
    my $document = load_json($path);
    $document->{status} = 'pass';
    for my $target (values %{$document->{results}}) {
        $target->{status} = 'pass';
        for my $mode (values %{$target->{modes}}) {
            my $meta_relative = $mode->{raw_log}{path};
            $meta_relative =~ s/raw\.log\z/result.json/;
            my $meta_path = File::Spec->catfile($evidence,
                File::Spec->splitdir($meta_relative));
            my $meta = load_json($meta_path);
            $meta->{status} = 'pass';
            write_json($meta_path, $meta);
            $mode = clone($meta);
            for my $artifact (@{$document->{artifacts}}) {
                next unless $artifact->{path} eq $meta_relative;
                $artifact->{sha256} = sha256_file($meta_path);
            }
        }
    }
    write_json($path, $document);
    seal_result($path);
}
sub sha256_file { my ($p)=@_; open my $f,'<:raw',$p or die $!; my $s=Digest::SHA->new(256); $s->addfile($f); close $f; return $s->hexdigest }
sub capture { open my $f,'-|',@_ or die $!; local $/; my $r=<$f>; close $f or die $?; return $r }
sub run_command {
    pipe my $read, my $write or die $!;
    my $pid = fork();
    die $! unless defined $pid;
    if (!$pid) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $_[0] } @_;
        die $!;
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $output);
}
