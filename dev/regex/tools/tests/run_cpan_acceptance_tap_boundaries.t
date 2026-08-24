use strict;
use warnings;

use Digest::SHA;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir(
    $FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile(
    $root, 'dev', 'regex', 'tools', 'run_cpan_acceptance.pl');
my $temporary = tempdir(CLEANUP => 1);
my $source = File::Spec->catdir($temporary, 'source');
make_path($source);
system('git', 'init', '-q', $source) == 0 or die 'git init';
system('git', '-C', $source, 'config', 'user.email',
    'fixture@example.test') == 0 or die 'git config';
system('git', '-C', $source, 'config', 'user.name',
    'Fixture') == 0 or die 'git config';
write_file(File::Spec->catfile($source, 'tracked'), "fixture\n");
system('git', '-C', $source, 'add', 'tracked') == 0 or die 'git add';
system('git', '-C', $source, 'commit', '-qm', 'fixture') == 0
    or die 'git commit';
my $commit = capture('git', '-C', $source, 'rev-parse', 'HEAD');
$commit =~ s/\s+\z//;

my $jperl = File::Spec->catfile($temporary, 'jperl');
write_file($jperl, <<'JPERL');
#!/usr/bin/env perl
print "PerlOnJava fixture $ENV{A225_SOURCE_COMMIT}\n";
JPERL
my $jcpan = File::Spec->catfile($temporary, 'jcpan');
write_file($jcpan, <<'JCPAN');
#!/usr/bin/env perl
use strict;
use warnings;
my $scenario = $ENV{A225_SCENARIO} // '';
if ($scenario eq 'portable-harness') {
    print "C:\\work tree\\Dist Name\\t\\one.t .... ok\n";
    print "/tmp/Dist Name/t/two.t .. skipped: optional dependency\n";
    success(2, 1);
} elsif ($scenario eq 'setup-plus-real') {
    print "Installing dependencies .... ok\n";
    print "t/real.t .. ok\n";
    success(1, 1);
} elsif ($scenario eq 'verbose-nested-todo') {
    print "C:\\work tree\\Dist Name\\t\\nested.t .. \n";
    print "# Subtest: nested\n    ok 1 - inner\n    1..1\n";
    print "ok 1 - nested\n";
    print "not ok 2 - expected TODO failure # TODO known gap\n";
    print "1..2\nok\n";
    success(1, 2);
} elsif ($scenario eq 'raw-nested-todo') {
    print "# Subtest: nested\n    nested/fake.t .. ok\n";
    print "    ok 1 - inner\n    1..1\n";
    print "ok 1 - nested\n";
    print "not ok 2 - expected TODO failure # TODO known gap\n";
    print "1..2\nAll tests successful.\n";
    print "Files=1, Tests=2, 0 wallclock secs\n";
} elsif ($scenario eq 'multiple-clean') {
    print "dep/one.t .. ok\n";
    success(1, 1);
    print "target/two.t .. ok\n";
    success(1, 1);
} elsif ($scenario eq 'setup-only') {
    print "Installing dependencies .... ok\n";
    success(1, 1);
} elsif ($scenario eq 'setup-prose-dot-t') {
    print "Checking setup.t .... ok\n";
    success(1, 1);
} elsif ($scenario eq 'duplicate') {
    print "t/one.t .. ok\n./t/one.t .. ok\n";
    success(2, 2);
} elsif ($scenario eq 'late-result') {
    print "t/one.t .. ok\nAll tests successful.\n";
    print "Files=1, Tests=1, 0 wallclock secs\n";
    print "CPAN epilogue before result\nResult: PASS\n";
} elsif ($scenario eq 'verbose-incomplete') {
    print "t/one.t .. \nok 1 - child output\n1..1\n";
    print "All tests successful.\nFiles=1, Tests=1, 0 wallclock secs\n";
    print "Result: PASS\n";
} elsif ($scenario eq 'genuine-not-ok') {
    print "not ok 1 - real failure\n1..1\nAll tests successful.\n";
    print "Files=1, Tests=1, 0 wallclock secs\n";
} elsif ($scenario eq 'non-test-path') {
    print "scripts/setup.pl .... ok\n";
    success(1, 1);
} elsif ($scenario eq 'retained-failure') {
    print "Test Summary Report\nResult: FAIL\n";
    print "t/one.t .. ok\n";
    success(1, 1);
} elsif ($scenario eq 'sample') {
    open my $fh, '<:raw', $ENV{A225_SAMPLE_LOG} or die $!;
    print while <$fh>;
    close $fh or die $!;
} else {
    die "unknown A225 scenario: $scenario\n";
}
exit 0;

sub success {
    my ($files, $tests) = @_;
    print "All tests successful.\n";
    print "Files=$files, Tests=$tests, 0 wallclock secs\n";
    print "Result: PASS\n";
}
JCPAN
chmod 0755, $jperl, $jcpan or die 'chmod fixture launchers';

my $jar = File::Spec->catfile($temporary, 'artifact.jar');
my $sbom = File::Spec->catfile($temporary, 'sbom.json');
write_file($jar, "jar\n");
write_file($sbom, "{}\n");
my $policy = File::Spec->catfile($temporary, 'policy.json');
write_json($policy, {
    schema_version => 1,
    expected_targets => ['Fixture::Target'],
    targets => [{
        name => 'Fixture::Target',
        rationale => 'A225 TAP boundary fixture',
        timeout_seconds => 10,
        required_modes => ['jvm', 'interpreter'],
        focused_selector_permitted => JSON::PP::false,
        approved_warning_patterns => [],
    }],
});
my $manifest = File::Spec->catfile($temporary, 'manifest.json');
my $jperl_sha = sha256_file($jperl);
write_json($manifest, {
    schema_version => 1,
    mode => 'acceptance',
    identity => {
        source_commit => $commit,
        runner_commit => $commit,
        perl5_commit => $commit,
        jperl_sha256 => $jperl_sha,
        jar_sha256 => sha256_file($jar),
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
});

local $ENV{A225_SOURCE_COMMIT} = $commit;
for my $scenario (qw(portable-harness setup-plus-real verbose-nested-todo
        raw-nested-todo multiple-clean)) {
    subtest "$scenario is accepted" => sub {
        my ($status, $result) = run_scenario($scenario);
        is($status, 0, 'producer exits successfully');
        is($result->{status}, 'pass', 'aggregate evidence passes');
        for my $mode (qw(jvm interpreter)) {
            is($result->{results}{'Fixture::Target'}{modes}{$mode}{failures},
                0, "$mode retains no hard failure");
            ok(!$result->{results}{'Fixture::Target'}{modes}{$mode}{malformed},
                "$mode is structurally complete");
        }
    };
}

for my $scenario (qw(setup-only setup-prose-dot-t duplicate late-result
        verbose-incomplete genuine-not-ok non-test-path retained-failure)) {
    subtest "$scenario fails closed" => sub {
        my ($status, $result) = run_scenario($scenario);
        isnt($status, 0, 'producer rejects the fixture');
        is($result->{status}, 'fail', 'aggregate evidence records failure');
    };
}

my @samples = grep { length } split /\n/,
    ($ENV{REGEX_IMPLEMENTATION_A225_IMMUTABLE_LOGS} // '');
SKIP: {
    skip 'no immutable Regex implementation log samples supplied', 2 unless @samples;
    for my $path (@samples) {
        subtest "immutable log shape: $path" => sub {
            ok(-f $path, 'sample exists');
            local $ENV{A225_SAMPLE_LOG} = $path;
            my ($status, $result) = run_scenario('sample');
            my $modes = $result->{results}{'Fixture::Target'}{modes};
            for my $mode (qw(jvm interpreter)) {
                ok(!$modes->{$mode}{malformed},
                    "$mode sample TAP structure is accepted");
                ok(!$modes->{$mode}{truncated},
                    "$mode sample TAP structure is complete");
                cmp_ok($modes->{$mode}{total_tests}, '>', 0,
                    "$mode sample retains a nonzero TAP total");
            }
            diag('sample remains fail-closed for its retained warnings')
                if $status && grep { @{$modes->{$_}{unapproved_warnings}} }
                    qw(jvm interpreter);
        };
    }
}

done_testing;

my $sequence = 0;
sub run_scenario {
    my ($scenario) = @_;
    local $ENV{A225_SCENARIO} = $scenario;
    my $evidence = File::Spec->catdir($temporary,
        sprintf('evidence-%03d-%s', ++$sequence, $scenario));
    my @command = ($^X, $tool, '--prepare-only', '--manifest', $manifest,
        '--policy', $policy, '--evidence-dir', $evidence,
        '--jperl', $jperl, '--jcpan', $jcpan);
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
    my $status = $? >> 8;
    my $result = load_json(File::Spec->catfile(
        $evidence, 'cpan-acceptance.json'));
    return ($status, $result, $output);
}

sub write_file {
    my ($path, $content) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $content;
    close $fh or die "Cannot close $path: $!";
}

sub write_json {
    write_file($_[0], JSON::PP->new->canonical->pretty->encode($_[1]));
}

sub load_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    return JSON::PP->new->decode(<$fh>);
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh;
    return $sha->hexdigest;
}

sub capture {
    open my $fh, '-|', @_ or die $!;
    local $/;
    my $output = <$fh>;
    close $fh or die $?;
    return $output;
}
