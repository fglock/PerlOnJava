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
print {$ofh} JSON::PP->new->encode({summary => {unresolved_references => 0, runner_files => 1}});
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
    execution_issues => [], zero_tap => [], truncated => []});
close $ofh;
COMPARATOR
my $packaging = fake_tool('packaging.pl', <<'PACKAGING');
use JSON::PP;
open my $rfh, '>>:raw', $ENV{ACCEPT_RECORD} or die $!;
print {$rfh} JSON::PP->new->canonical->encode({kind => 'packaging', argv => \@ARGV}), "\n";
close $rfh;
print "packaging fake passed\n";
PACKAGING

local $ENV{ACCEPT_TEST_FILE} = $test_file;
local $ENV{ACCEPT_RECORD} = $record;
my @command = ($^X, $tool, '--prepare-only',
    '--baseline', $baseline, '--artifact-dir', $artifacts,
    '--jar', $jar, '--sbom', $sbom,
    '--perl5-dir', $root,
    '--jperl', File::Spec->catfile($temporary, 'not-used-jperl'),
    '--ledger-tool', $ledger, '--runner-tool', $runner,
    '--comparator-tool', $comparator, '--packaging-tool', $packaging,
    '--timeout', 17, '--jobs', 3);
system @command;
is($? >> 8, 0, 'prepare-only composition with fake tools succeeds');

my $manifest = load_json(File::Spec->catfile($artifacts, 'manifest.json'));
is($manifest->{mode}, 'prepare-only', 'manifest records non-production mode');
is($manifest->{expected_files}, 1, 'manifest records generated exact file count');
is($manifest->{source}{starting_sha}, $manifest->{source}{final_sha},
    'manifest records unchanged checkout HEAD');
ok($manifest->{source}{perl5_sha_as_provenance} =~ /^[0-9a-f]{40}$/,
    'current perl5 revision is provenance');
for my $name (qw(regex-ledger.json regex-files.txt jvm-results.json
    interpreter-results.json jvm-comparison.json interpreter-comparison.json
    ledger.log jvm-runner.log interpreter-runner.log jvm-comparison.log
    interpreter-comparison.log packaging.log)) {
    ok($manifest->{artifacts}{$name}{sha256} =~ /^[0-9a-f]{64}$/,
        "$name has a retained SHA-256");
}

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
is(scalar @comparison_calls, 2, 'both result legs are compared');
for my $call (@comparison_calls) {
    ok(grep($_ eq '--normalize-pr958-artifacts', @{$call->{argv}}),
        'comparison enables PR-958 normalization');
    ok(grep($_ eq '--fail-on-invalid', @{$call->{argv}}),
        'comparison is fail-closed for invalid records');
}
is(scalar(grep { $_->{kind} eq 'packaging' } @calls), 1,
    'exact artifact packaging verification executes once');

done_testing;

sub fake_tool {
    my ($name, $contents) = @_;
    my $path = File::Spec->catfile($temporary, $name);
    write_file($path, $contents);
    return $path;
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
