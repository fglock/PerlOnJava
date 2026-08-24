use strict;
use warnings;

use Cwd qw(abs_path);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;
use Time::HiRes qw(sleep);

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'run_phase36_regex_performance.pl');
my $temporary = tempdir('phase36 performance XXXX', TMPDIR => 1, CLEANUP => 1);
my ($baseline, $candidate, $baseline_sha, $candidate_sha) = source_pair();
my $baseline_jar = write_file(File::Spec->catfile($temporary, 'baseline jar.bin'),
    "$baseline_sha\n");
my $candidate_jar = write_file(File::Spec->catfile($temporary, 'candidate jar.bin'),
    "$candidate_sha\n");
my $benchmark = write_file(File::Spec->catfile($temporary, 'regex benchmark.pl'), "benchmark\n");
my $launcher = fake_launcher(File::Spec->catfile($temporary, 'fake launcher'));
my $record = File::Spec->catfile($temporary, 'execution order.jsonl');

subtest 'success uses exact identities, private evidence, spaces, and alternation' => sub {
    my $evidence = evidence_directory('success evidence');
    local $ENV{PERF_MODE} = 'success';
    local $ENV{PERF_RECORD} = $record;
    unlink $record;
    my ($status, $output) = run_tool($evidence);
    is($status, 0, 'five-pair performance run succeeds');
    my $json_path = File::Spec->catfile(abs_path($evidence), 'performance.json');
    is($output, "$json_path\n", 'tool reports canonical output path');
    my $result = load_json($json_path);
    is_deeply($result->{baseline_seconds}, [(2) x 5], 'baseline metrics retained');
    is_deeply($result->{candidate_seconds}, [(1) x 5], 'candidate metrics retained');
    ok($result->{alternating_order}, 'acceptance alternation flag is true');
    is($result->{source}{baseline}{commit}, $baseline_sha, 'baseline full SHA retained');
    is($result->{source}{candidate}{commit}, $candidate_sha, 'candidate full SHA retained');
    is($result->{source}{candidate}{parent_commit}, $baseline_sha,
        'exact parent relation retained');
    is($result->{semantic_checksum}, 'semantic-42', 'semantic checksum retained');
    is_deeply([map { decode_line($_)->{side} } lines($record)],
        [qw(baseline candidate), (qw(baseline candidate)) x 5],
        'warmups and samples execute in strict side alternation');
    is(scalar @{$result->{artifacts}{raw_logs}{baseline}}, 7,
        'complete baseline identity/warmup/sample logs retained');
    is(scalar @{$result->{artifacts}{raw_logs}{candidate}}, 7,
        'complete candidate identity/warmup/sample logs retained');
};

subtest 'median regression is rejected' => sub {
    rejected('regression', 'regression evidence', qr/Candidate median regressed/);
};

subtest 'execution and parse failures are fail-closed' => sub {
    my @cases = (
        [exit => qr/exited with status 7/],
        [signal => qr/terminated by signal/],
        [timeout => qr/timed out after 1s/],
        [descendant_timeout => qr/timed out after 1s/],
        [missing => qr/missing or duplicate performance metrics/],
        [duplicate => qr/missing or duplicate performance metrics/],
        [malformed_elapsed => qr/elapsed metric is malformed/],
        [malformed_throughput => qr/throughput metric is malformed/],
        [extra_metric => qr/metric token is malformed/],
        [missing_checksum => qr/performance metric checksum is missing/],
        [checksum => qr/semantic checksum mismatch/],
        [wrong_executable => qr/reported wrong executable identity/],
        [wrong_jar => qr/wrong executable JAR/],
        [wrong_source => qr/wrong source commit/],
        [mutation => qr/Performance input mutated during execution/],
    );
    for my $case (@cases) {
        rejected($case->[0], "$case->[0] evidence", $case->[1],
            $case->[0] =~ /timeout\z/ ? 1 : 3);
    }
};

subtest 'artifact collision and evidence privacy are rejected before execution' => sub {
    my $nonempty = evidence_directory('nonempty evidence');
    write_file(File::Spec->catfile($nonempty, 'occupied'), 'x');
    my ($status, $output) = run_tool($nonempty);
    isnt($status, 0, 'nonempty evidence directory is rejected');
    like($output, qr/Evidence directory is not empty/, 'collision diagnostic is specific');

    my $public = evidence_directory('public evidence');
    chmod 0755, $public or die $!;
    ($status, $output) = run_tool($public);
    isnt($status, 0, 'non-private evidence directory is rejected');
    like($output, qr/must be private/, 'privacy diagnostic is specific');
};

done_testing;

sub run_tool {
    my ($evidence, $timeout) = @_;
    $timeout //= 3;
    my @command = ($^X, $tool,
        '--baseline-source', $baseline, '--candidate-source', $candidate,
        '--baseline-jar', $baseline_jar, '--candidate-jar', $candidate_jar,
        '--baseline-launcher', $launcher, '--candidate-launcher', $launcher,
        '--benchmark', $benchmark, '--evidence-dir', $evidence,
        '--samples', 5, '--timeout', $timeout);
    return capture(@command);
}

sub rejected {
    my ($mode, $name, $pattern, $timeout) = @_;
    my $evidence = evidence_directory($name);
    local $ENV{PERF_MODE} = $mode;
    local $ENV{PERF_RECORD} = $record;
    local $ENV{PERF_MUTATE_FILE} = File::Spec->catfile($candidate, 'tracked.txt');
    local $ENV{PERF_DESCENDANT_PID} = File::Spec->catfile($temporary,
        'descendant.pid');
    unlink $ENV{PERF_DESCENDANT_PID};
    my ($status, $output) = run_tool($evidence, $timeout);
    isnt($status, 0, "$mode is rejected");
    like($output, $pattern, "$mode has a specific diagnostic");
    if ($mode eq 'mutation') {
        write_file(File::Spec->catfile($candidate, 'tracked.txt'), "candidate\n");
    }
    if ($mode eq 'descendant_timeout') {
        ok(-s $ENV{PERF_DESCENDANT_PID}, 'timeout fixture launched a descendant');
        my $pid = 0 + read_file($ENV{PERF_DESCENDANT_PID});
        my $alive = 1;
        for (1 .. 20) {
            $alive = kill 0, $pid;
            last unless $alive;
            sleep 0.05;
        }
        ok(!$alive, 'timeout kills the exact child process group descendant');
    }
}

sub fake_launcher {
    my ($path) = @_;
    write_file($path, <<'LAUNCHER');
#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use JSON::PP;
my $side = $ENV{PHASE36_PERFORMANCE_SIDE};
if (@ARGV && $ARGV[0] eq '-v') {
    open my $fh, '<:raw', $ENV{PERLONJAVA_JAR} or die $!;
    my $sha = <$fh>;
    close $fh;
    chomp $sha;
    $sha = substr($sha, 0, 12);
    $sha = '0' x 12 if ($ENV{PERF_MODE} // '') eq 'wrong_executable'
        && $ENV{PERLONJAVA_JAR} =~ /candidate/;
    print "PerlOnJava fixture $sha\n";
    exit 0;
}
if ($ENV{PERF_RECORD}) {
    open my $fh, '>>:raw', $ENV{PERF_RECORD} or die $!;
    print {$fh} JSON::PP->new->canonical->encode({side => $side}), "\n";
    close $fh;
}
my $mode = $ENV{PERF_MODE} // 'success';
exit 7 if $mode eq 'exit' && $side eq 'candidate';
kill 'TERM', $$ if $mode eq 'signal' && $side eq 'candidate';
sleep 5 if $mode eq 'timeout' && $side eq 'candidate';
if ($mode eq 'descendant_timeout' && $side eq 'candidate') {
    my $child = fork();
    die $! unless defined $child;
    if ($child == 0) { sleep 5; exit 0 }
    open my $fh, '>:raw', $ENV{PERF_DESCENDANT_PID} or die $!;
    print {$fh} $child;
    close $fh;
    sleep 5;
}
exit 0 if $mode eq 'missing' && $side eq 'candidate';
my $elapsed = $side eq 'baseline' ? 2 : 1;
$elapsed = $side eq 'baseline' ? 1 : 2 if $mode eq 'regression';
my $throughput = $side eq 'baseline' ? 100 : 200;
my $checksum = 'semantic-42';
$checksum = "semantic-$side" if $mode eq 'checksum';
my $jar = $ENV{PHASE36_JAR_SHA256};
my $source = $ENV{PHASE36_SOURCE_COMMIT};
$elapsed = 'bad' if $mode eq 'malformed_elapsed' && $side eq 'candidate';
$throughput = 'bad' if $mode eq 'malformed_throughput' && $side eq 'candidate';
$jar = '0' x 64 if $mode eq 'wrong_jar' && $side eq 'candidate';
$source = '0' x 40 if $mode eq 'wrong_source' && $side eq 'candidate';
if ($mode eq 'mutation' && $side eq 'candidate') {
    open my $fh, '>>:raw', $ENV{PERF_MUTATE_FILE} or die $!;
    print {$fh} "mutated\n";
    close $fh;
}
my $line = "PHASE36_REGEX_PERFORMANCE elapsed_seconds=$elapsed throughput=$throughput";
$line .= " checksum=$checksum" unless $mode eq 'missing_checksum' && $side eq 'candidate';
$line .= " jar_sha256=$jar source_commit=$source";
$line .= " unexpected=value" if $mode eq 'extra_metric' && $side eq 'candidate';
print "$line\n";
print "$line\n" if $mode eq 'duplicate' && $side eq 'candidate';
LAUNCHER
    chmod 0755, $path or die "Cannot chmod $path: $!";
    return $path;
}

sub source_pair {
    my $repository = File::Spec->catdir($temporary, 'source repository');
    make_path($repository);
    system('git', 'init', '-q', $repository) == 0 or die 'git init failed';
    system('git', '-C', $repository, 'config', 'user.email', 'fixture@example.test') == 0
        or die 'git config failed';
    system('git', '-C', $repository, 'config', 'user.name', 'Fixture') == 0
        or die 'git config failed';
    write_file(File::Spec->catfile($repository, 'root.txt'), "root\n");
    system('git', '-C', $repository, 'add', 'root.txt') == 0 or die 'git add failed';
    system('git', '-C', $repository, 'commit', '-qm', 'root') == 0
        or die 'git commit failed';
    write_file(File::Spec->catfile($repository, 'tracked.txt'), "baseline\n");
    system('git', '-C', $repository, 'add', 'tracked.txt') == 0 or die 'git add failed';
    system('git', '-C', $repository, 'commit', '-qm', 'baseline') == 0
        or die 'git commit failed';
    my $base = git_sha($repository);
    write_file(File::Spec->catfile($repository, 'tracked.txt'), "candidate\n");
    system('git', '-C', $repository, 'commit', '-qam', 'candidate') == 0
        or die 'git commit failed';
    my $candidate_sha = git_sha($repository);
    my $base_checkout = File::Spec->catdir($temporary, 'baseline checkout');
    my $candidate_checkout = File::Spec->catdir($temporary, 'candidate checkout');
    system('git', 'clone', '-q', $repository, $base_checkout) == 0 or die 'clone failed';
    system('git', '-C', $base_checkout, 'checkout', '-q', $base) == 0 or die 'checkout failed';
    system('git', 'clone', '-q', $repository, $candidate_checkout) == 0 or die 'clone failed';
    return ($base_checkout, $candidate_checkout, $base, $candidate_sha);
}

sub git_sha {
    my ($directory) = @_;
    open my $fh, '-|', 'git', '-C', $directory, 'rev-parse', 'HEAD' or die $!;
    my $sha = <$fh>;
    close $fh or die 'git rev-parse failed';
    chomp $sha;
    return $sha;
}

sub evidence_directory {
    my ($name) = @_;
    my $directory = File::Spec->catdir($temporary, $name);
    make_path($directory, { mode => 0700 });
    chmod 0700, $directory or die $!;
    return $directory;
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
        exec { $command[0] } @command;
        die "exec: $!";
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub lines {
    my ($file) = @_;
    return grep { length } split /\n/, read_file($file);
}

sub decode_line { JSON::PP->new->decode($_[0]) }
sub load_json { JSON::PP->new->decode(read_file($_[0])) }

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
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return $contents;
}
