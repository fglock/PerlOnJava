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
print "PerlOnJava fixture $ENV{A217_SOURCE_COMMIT}\n";
JPERL
my $jcpan = File::Spec->catfile($temporary, 'jcpan');
write_file($jcpan, <<'JCPAN');
#!/usr/bin/env perl
use strict;
use warnings;
my $scenario = $ENV{A217_SCENARIO} // '';
if ($scenario eq 'missing') {
    print "All tests successful.\nFiles=1, Tests=1, 0 wallclock secs\nResult: PASS\n";
} elsif ($scenario eq 'bad-plan') {
    print "ok 1 - only test\n1..2\nAll tests successful.\n";
    print "Files=1, Tests=2, 0 wallclock secs\nResult: PASS\n";
} elsif ($scenario eq 'bad-files') {
    print "t/only.t .. ok\nAll tests successful.\n";
    print "Files=2, Tests=1, 0 wallclock secs\nResult: PASS\n";
} elsif ($scenario eq 'raw-control') {
    print "ok 1 - complete\n1..1\nAll tests successful.\n";
    print "Files=1, Tests=1, 0 wallclock secs\n";
} elsif ($scenario eq 'harness-control') {
    print "t/only.t .. ok\nAll tests successful.\n";
    print "Files=1, Tests=1, 0 wallclock secs\nResult: PASS\n";
} else {
    die "unknown A217 scenario: $scenario\n";
}
exit 0;
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
        rationale => 'TAP integrity fixture',
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

local $ENV{A217_SOURCE_COMMIT} = $commit;
for my $case (
    ['missing', 'missing TAP/file evidence'],
    ['bad-plan', 'raw TAP plan mismatch'],
    ['bad-files', 'harness file-count mismatch'],
) {
    my ($scenario, $label) = @$case;
    local $ENV{A217_SCENARIO} = $scenario;
    my ($status, $output) = run_acceptance($scenario);
    isnt($status, 0, "$label fails closed despite a PASS summary");
    my $result = load_json(File::Spec->catfile(
        $temporary, "evidence-$scenario", 'cpan-acceptance.json'));
    is($result->{status}, 'fail', "$label is retained as failed evidence");
    ok($result->{results}{'Fixture::Target'}{modes}{jvm}{malformed},
        "$label marks the mode malformed");
}

for my $scenario (qw(raw-control harness-control)) {
    local $ENV{A217_SCENARIO} = $scenario;
    my ($status) = run_acceptance($scenario);
    is($status, 0, "$scenario remains accepted");
}

done_testing;

sub run_acceptance {
    my ($scenario) = @_;
    my $evidence = File::Spec->catdir($temporary, "evidence-$scenario");
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
    return ($? >> 8, $output);
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
