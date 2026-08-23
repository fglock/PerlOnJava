use strict;
use warnings;

use Cwd qw(abs_path);
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
system('git', 'init', '-q', $source) == 0 or die 'Cannot initialize fixture';
system('git', '-C', $source, 'config', 'user.email', 'fixture@example.test') == 0
    or die 'Cannot configure fixture';
system('git', '-C', $source, 'config', 'user.name', 'Fixture') == 0
    or die 'Cannot configure fixture';
write_file(File::Spec->catfile($source, 'tracked.txt'), "fixture\n");
system('git', '-C', $source, 'add', 'tracked.txt') == 0
    or die 'Cannot stage fixture';
system('git', '-C', $source, 'commit', '-qm', 'fixture') == 0
    or die 'Cannot commit fixture';

my $source_sha = capture_success('git', '-C', $source, 'rev-parse', 'HEAD');
$source_sha =~ s/\s+\z//;
my $test_file = File::Spec->catfile($temporary, 'focused.t');
my $baseline = File::Spec->catfile($temporary, 'baseline.log');
my $jar = File::Spec->catfile($temporary, 'release.jar');
my $sbom = File::Spec->catfile($temporary, 'release-sbom.json');
my $record = File::Spec->catfile($temporary, 'runner-calls.jsonl');
write_file($test_file, "1..1\nok 1\n");
write_file($baseline, "[  1/1] $test_file ... . 1/1 ok\n");
write_file($jar, "sealed fixture jar\n");
write_file($sbom, "{}\n");

my $ledger = fake_tool('ledger.pl', <<'LEDGER');
use JSON::PP;
my ($list, $output);
while (@ARGV) {
    my $arg = shift @ARGV;
    $list = shift @ARGV if $arg eq '--runner-list';
    $output = shift @ARGV if $arg eq '--output';
}
open my $list_fh, '>:raw', $list or die $!;
print {$list_fh} "$ENV{ACCEPT_TEST_FILE}\n";
close $list_fh;
open my $output_fh, '>:raw', $output or die $!;
print {$output_fh} JSON::PP->new->encode({
    summary => {unresolved_references => 0, runner_files => 1},
    core_re_files => [$ENV{ACCEPT_TEST_FILE}], documented_unit_gates => [],
    direct_thread_pairs => [], thread_only_tests => [],
});
close $output_fh;
LEDGER
my $runner = fake_tool('runner.pl', <<'RUNNER');
use JSON::PP;
my @arguments = @ARGV;
my $output;
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
}
open my $record_fh, '>>:raw', $ENV{ACCEPT_RECORD} or die $!;
print {$record_fh} JSON::PP->new->canonical->encode(\@arguments), "\n";
close $record_fh;
open my $output_fh, '>:raw', $output or die $!;
print {$output_fh} JSON::PP->new->encode({results => {}});
close $output_fh;
RUNNER
my $comparator = fake_tool('comparator.pl', <<'COMPARATOR');
use JSON::PP;
my $output;
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
}
open my $output_fh, '>:raw', $output or die $!;
print {$output_fh} JSON::PP->new->encode({
    summary => {candidate_files => 1}, regressions => [], missing_files => [],
    added_files => [], execution_issues => [], zero_tap => [], truncated => [],
    new_invalid => [], inherited_invalid => [],
});
close $output_fh;
COMPARATOR
my $packaging = fake_tool('packaging.pl', "print qq{packaging fixture passed\\n};\n");
my $jperl = fake_tool('fixture-jperl', <<'JPERL');
#!/usr/bin/env perl
print "PerlOnJava fixture $ENV{ACCEPT_SOURCE_SHA}\n";
JPERL
chmod 0755, $jperl or die "Cannot chmod fixture launcher: $!";

local $ENV{ACCEPT_TEST_FILE} = $test_file;
local $ENV{ACCEPT_RECORD} = $record;
local $ENV{ACCEPT_SOURCE_SHA} = $source_sha;

my @common = ($^X, $tool, '--prepare-only',
    '--baseline', $baseline, '--jar', $jar, '--sbom', $sbom,
    '--source-dir', $source, '--perl5-dir', $source, '--jperl', $jperl,
    '--ledger-tool', $ledger, '--runner-tool', $runner,
    '--comparator-tool', $comparator, '--packaging-tool', $packaging);

my $explicit_artifacts = File::Spec->catdir($temporary, 'explicit-artifacts');
make_path($explicit_artifacts);
is(run(@common, '--artifact-dir', $explicit_artifacts,
        '--jobs', 7, '--cpu-heavy-jobs', 3), 0,
    'explicit bounded CPU-heavy budget composes successfully');
my $explicit_manifest = load_json(
    File::Spec->catfile($explicit_artifacts, 'manifest.json'));
is_deeply($explicit_manifest->{identity}{runner_policy}, {
        timeout => 300, jobs => 7, cpu_heavy_jobs => 3,
    }, 'manifest identity seals the explicit runner policy');
my @explicit_commands = grep { $_->{name} =~ /\A(?:jvm|interpreter)-runner\z/ }
    @{$explicit_manifest->{commands}};
is(scalar @explicit_commands, 2, 'manifest retains both runner commands');
for my $command (@explicit_commands) {
    is(option_value($command->{argv}, '--cpu-heavy-jobs'), 3,
        "$command->{name} command retains the explicit CPU-heavy budget");
}

my $default_artifacts = File::Spec->catdir($temporary, 'default-artifacts');
make_path($default_artifacts);
is(run(@common, '--artifact-dir', $default_artifacts), 0,
    'omitting CPU-heavy budget preserves the compatible default');
my $default_manifest = load_json(
    File::Spec->catfile($default_artifacts, 'manifest.json'));
is($default_manifest->{identity}{runner_policy}{cpu_heavy_jobs}, 2,
    'manifest identity records default CPU-heavy budget 2');

my @runner_calls = map { JSON::PP->new->decode($_) }
    grep { length } split /\n/, read_file($record);
is(scalar @runner_calls, 4, 'two legs execute for each bounded fixture run');
is_deeply([map { option_value($_, '--cpu-heavy-jobs') } @runner_calls],
    [3, 3, 2, 2], 'both perl_test_runner invocations receive each selected budget');

for my $case (
    ['zero', 0],
    ['above final policy maximum', 4],
) {
    my ($label, $value) = @$case;
    my ($status, $output) = capture(@common,
        '--artifact-dir', $temporary, '--cpu-heavy-jobs', $value);
    is($status, 255, "$label CPU-heavy budget is rejected");
    like($output, qr/--cpu-heavy-jobs must be between 1 and 3/,
        "$label rejection reports the final policy bound");
}

my ($abbrev_status, $abbrev_output) = capture($^X, $tool,
    '--cpu-heavy-job', 2);
isnt($abbrev_status, 0, 'abbreviated option is rejected');
like($abbrev_output, qr/Unknown option: cpu-heavy-job/,
    'abbreviation rejection identifies the unknown option');

my ($duplicate_status, $duplicate_output) = capture($^X, $tool,
    '--cpu-heavy-jobs', 2, '--cpu-heavy-jobs=3');
is($duplicate_status, 255, 'duplicate option is rejected');
like($duplicate_output, qr/Duplicate option --cpu-heavy-jobs/,
    'duplicate rejection identifies the repeated option');

done_testing;

sub fake_tool {
    my ($name, $contents) = @_;
    my $path = File::Spec->catfile($temporary, $name);
    write_file($path, $contents);
    return $path;
}

sub run {
    system @_;
    return $? >> 8;
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

sub capture_success {
    my ($status, $output) = capture(@_);
    die "Command failed with status $status: $output" if $status != 0;
    return $output;
}

sub option_value {
    my ($arguments, $name) = @_;
    for (my $index = 0; $index < @$arguments; $index++) {
        return $arguments->[$index + 1] if $arguments->[$index] eq $name;
    }
    return undef;
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
