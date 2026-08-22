use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'run_phase36_regex_acceptance.pl');
my $temporary = tempdir(CLEANUP => 1);
my $source = File::Spec->catdir($temporary, 'source');
make_path($source);
system('git', 'init', '-q', $source) == 0 or die 'Cannot initialize source fixture';
system('git', '-C', $source, 'config', 'user.email', 'fixture@example.test') == 0 or die 'Cannot configure fixture';
system('git', '-C', $source, 'config', 'user.name', 'Fixture') == 0 or die 'Cannot configure fixture';
write_file(File::Spec->catfile($source, 'tracked.txt'), "fixture\n");
system('git', '-C', $source, 'add', 'tracked.txt') == 0 or die 'Cannot stage fixture';
system('git', '-C', $source, 'commit', '-qm', 'fixture') == 0 or die 'Cannot commit fixture';
my $artifacts = File::Spec->catdir($temporary, 'artifacts');
make_path($artifacts);
my $test_file = File::Spec->catfile($temporary, 'focused.t');
write_file($test_file, "1..1\nok 1\n");
my $baseline = File::Spec->catfile($temporary, 'pr958.log');
my $jar = File::Spec->catfile($temporary, 'perlonjava.jar');
my $sbom = File::Spec->catfile($temporary, 'sbom.json');
write_file($baseline, "[  1/1] $test_file ... . 1/1 ok\n");
write_file($jar, "fake jar\n");
write_file($sbom, "{}\n");

my $record = File::Spec->catfile($temporary, 'calls.jsonl');
my $ledger = fake_tool('ledger.pl', <<'LEDGER');
use JSON::PP;
my ($list, $output);
while (@ARGV) {
    my $arg = shift @ARGV;
    $list = shift @ARGV if $arg eq '--runner-list';
    $output = shift @ARGV if $arg eq '--output';
}
open my $lfh, '>:raw', $list or die $!;
print {$lfh} "$ENV{ACCEPT_TEST_FILE}\n";
close $lfh;
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->encode({
    summary => {unresolved_references => 0, runner_files => 1},
    core_re_files => [$ENV{ACCEPT_TEST_FILE}],
    documented_unit_gates => [],
    direct_thread_pairs => [],
    thread_only_tests => [],
});
close $ofh;
LEDGER
my $runner = fake_tool('runner.pl', <<'RUNNER');
use JSON::PP;
my @args = @ARGV;
my $output;
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
}
open my $rfh, '>>:raw', $ENV{ACCEPT_RECORD} or die $!;
print {$rfh} JSON::PP->new->canonical->encode({kind => 'runner', argv => \@args,
    interpreter => $ENV{JPERL_INTERPRETER}}), "\n";
close $rfh;
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->encode({results => {}});
close $ofh;
RUNNER
my $comparator = fake_tool('comparator.pl', <<'COMPARATOR');
use JSON::PP;
my @args = @ARGV;
my $output;
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
}
open my $rfh, '>>:raw', $ENV{ACCEPT_RECORD} or die $!;
print {$rfh} JSON::PP->new->canonical->encode({kind => 'comparator', argv => \@args}), "\n";
close $rfh;
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->encode({summary => {candidate_files => 1},
    regressions => [], missing_files => [], added_files => [],
    execution_issues => [], zero_tap => [], truncated => [],
    new_invalid => [], inherited_invalid => []});
close $ofh;
COMPARATOR
my $packaging = fake_tool('packaging.pl', <<'PACKAGING');
use JSON::PP;
open my $rfh, '>>:raw', $ENV{ACCEPT_RECORD} or die $!;
print {$rfh} JSON::PP->new->canonical->encode({kind => 'packaging', argv => \@ARGV}), "\n";
close $rfh;
print "packaging fake passed\n";
PACKAGING
my $jperl = fake_tool('jperl', <<'JPERL');
#!/usr/bin/env perl
print "PerlOnJava fake $ENV{ACCEPT_SOURCE_SHA}\n";
JPERL
chmod 0755, $jperl or die "Cannot chmod fake jperl: $!";
open my $git, '-|', 'git', '-C', $source, 'rev-parse', 'HEAD' or die "Cannot run git: $!";
my $source_sha = do { local $/; <$git> };
close $git or die "Cannot close git: $?";
chomp $source_sha;

local $ENV{ACCEPT_TEST_FILE} = $test_file;
local $ENV{ACCEPT_RECORD} = $record;
local $ENV{ACCEPT_SOURCE_SHA} = $source_sha;
my $unsafe_prepare = capture_tool($^X, $tool, '--prepare-only');
is($? >> 8, 255,
    'prepare-only refuses production tools instead of starting the corpus');
like($unsafe_prepare, qr/requires injected non-production tools/,
    'prepare-only refusal explains the required isolation');
my @command = ($^X, $tool, '--prepare-only',
    '--baseline', $baseline, '--artifact-dir', $artifacts,
    '--jar', $jar, '--sbom', $sbom,
    '--source-dir', $source, '--perl5-dir', $source,
    '--jperl', $jperl,
    '--ledger-tool', $ledger, '--runner-tool', $runner,
    '--comparator-tool', $comparator, '--packaging-tool', $packaging,
    '--timeout', 17, '--jobs', 3);
system @command;
is($? >> 8, 0, 'prepare-only composition with fake tools succeeds');

my $manifest = load_json(File::Spec->catfile($artifacts, 'manifest.json'));
is($manifest->{mode}, 'prepare-only', 'manifest records non-production mode');
is($manifest->{expected_files}, 1, 'manifest records generated exact file count');
is($manifest->{strict_regex_expected_files}, 1,
    'manifest records the strict regex subset count');
is($manifest->{source}{starting_sha}, $manifest->{source}{final_sha},
    'manifest records unchanged checkout HEAD');
ok($manifest->{source}{perl5_sha_as_provenance} =~ /^[0-9a-f]{40}$/,
    'current perl5 revision is provenance');
is($manifest->{verified_runner_sha}, $source_sha, 'manifest records verified runner SHA');
for my $name (qw(regex-ledger.json regex-files.txt strict-regex-ledger.json
    regex-scope-files.txt strict-regex-files.txt jvm-results.json
    interpreter-results.json jvm-comparison.json interpreter-comparison.json
    jvm-strict-regex-comparison.json interpreter-strict-regex-comparison.json
    ledger.log strict-regex-ledger.log jvm-runner.log interpreter-runner.log
    jvm-comparison.log interpreter-comparison.log
    jvm-strict-regex-comparison.log interpreter-strict-regex-comparison.log
    packaging.log)) {
    ok($manifest->{artifacts}{$name}{sha256} =~ /^[0-9a-f]{64}$/,
        "$name has a retained SHA-256");
}
ok($manifest->{artifacts}{'jperl-version.log'}{sha256} =~ /^[0-9a-f]{64}$/,
    'retained jperl version log has a SHA-256');

my @calls = map { JSON::PP->new->decode($_) }
    grep { length } split /\n/, read_file($record);
my @runner_calls = grep { $_->{kind} eq 'runner' } @calls;
is(scalar @runner_calls, 2, 'exactly JVM and interpreter runner legs execute');
is($runner_calls[0]{interpreter} // '', '', 'JVM leg clears interpreter environment');
is($runner_calls[1]{interpreter}, 1, 'interpreter leg sets interpreter environment');
for my $call (@runner_calls) {
    is_deeply([grep { /focused\.t\z/ } @{$call->{argv}}], [$test_file],
        'each runner receives the same exact generated file list');
    ok(grep($_ eq '--timeout', @{$call->{argv}}), 'runner receives existing timeout option');
}
my @comparison_calls = grep { $_->{kind} eq 'comparator' } @calls;
is(scalar @comparison_calls, 4,
    'both result legs receive broad and strict regex comparisons');
for my $call (@comparison_calls) {
    ok(grep($_ eq '--normalize-pr958-artifacts', @{$call->{argv}}),
        'comparison enables PR-958 normalization');
}
my @broad_calls = grep { has_arg($_, '--fail-on-new-invalid') } @comparison_calls;
my @strict_calls = grep { has_arg($_, '--fail-on-invalid') } @comparison_calls;
is(scalar @broad_calls, 2,
    'complete-map comparisons reject newly invalid rows');
is(scalar @strict_calls, 2,
    'regex-subset comparisons reject every invalid row');
is(scalar(grep { $_->{kind} eq 'packaging' } @calls), 1,
    'exact artifact packaging verification executes once');

my $sleeping_jperl = fake_tool('sleeping-jperl', <<'SLEEPING_JPERL');
#!/usr/bin/env perl
sleep 30;
SLEEPING_JPERL
chmod 0755, $sleeping_jperl or die "Cannot chmod sleeping fake jperl: $!";
my $timeout_artifacts = File::Spec->catdir($temporary, 'timeout-artifacts');
make_path($timeout_artifacts);
my @timeout_command = @command;
for (my $i = 0; $i < @timeout_command; $i++) {
    $timeout_command[$i + 1] = $sleeping_jperl
        if $timeout_command[$i] eq '--jperl';
    $timeout_command[$i + 1] = $timeout_artifacts
        if $timeout_command[$i] eq '--artifact-dir';
}
push @timeout_command, '--version-timeout', 1;
my $timeout_output = capture_tool(@timeout_command);
is($? >> 8, 124, 'hung jperl identity probe exits with timeout status');
like($timeout_output, qr/jperl-version timed out after 1s/,
    'hung identity probe has a specific timeout diagnostic');

done_testing;

sub fake_tool {
    my ($name, $contents) = @_;
    my $path = File::Spec->catfile($temporary, $name);
    write_file($path, $contents);
    return $path;
}

sub capture_tool {
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
    return $output;
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return $contents;
}

sub load_json {
    return JSON::PP->new->decode(read_file($_[0]));
}

sub has_arg {
    my ($call, $wanted) = @_;
    return scalar grep { $_ eq $wanted } @{$call->{argv}};
}
