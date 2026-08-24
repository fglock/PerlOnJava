use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'run_regex_acceptance.pl');
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
my $test_file = "dev/regex/tools/tests/.regex_implementation-cpu-$$-focused.t";
my $baseline = File::Spec->catfile($temporary, 'baseline.log');
my $jar = File::Spec->catfile($temporary, 'release.jar');
my $sbom = File::Spec->catfile($temporary, 'release-sbom.json');
my $record = File::Spec->catfile($temporary, 'runner-calls.jsonl');
write_file($test_file, "1..1\nok 1\n");
END { unlink $test_file if defined($test_file) && -f $test_file }
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
use File::Spec;
my @arguments = @ARGV;
my ($output, @tests);
while (@ARGV) {
    my $arg = shift @ARGV;
    if ($arg eq '--output') { $output = shift @ARGV; next }
    if ($arg =~ /\A--(?:jperl|timeout|jobs|cpu-heavy-jobs)\z/) { shift @ARGV; next }
    next if $arg =~ /\A--/;
    push @tests, $arg;
}
open my $record_fh, '>>:raw', $ENV{ACCEPT_RECORD} or die $!;
print {$record_fh} JSON::PP->new->canonical->encode(\@arguments), "\n";
close $record_fh;
my $file = $tests[0];
my $backend = $ENV{JPERL_INTERPRETER} ? 'interpreter' : 'jvm';
my $raw = File::Spec->catfile(File::Spec->tmpdir,
    "regex_implementation-cpu-raw-$$-$backend.tap");
open my $raw_fh, '>:raw', $raw or die $!;
print {$raw_fh} "1..1\nok 1 - fixture\n";
close $raw_fh;
open my $output_fh, '>:raw', $output or die $!;
print {$output_fh} JSON::PP->new->encode({results => {$file => {
    file => $file, status => 'pass', ok_count => 1, not_ok_count => 0,
    total_tests => 1, planned_tests => 1, actual_tests_run => 1,
    incomplete_tests => 0, skip_count => 0, todo_count => 0,
    errors => [], missing_features => [], exit_code => 0,
    raw_output_path => $raw,
}}});
close $output_fh;
RUNNER
my $comparator = fake_tool('comparator.pl', <<'COMPARATOR');
use JSON::PP;
use Digest::SHA qw(sha256_hex);
my ($output, $file_list);
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
    $file_list = shift @ARGV if $arg eq '--file-list';
}
open my $list_fh, '<:raw', $file_list or die $!;
my @files = sort grep { length } map { chomp; $_ } <$list_fh>;
close $list_fh;
my $digest = sha256_hex(join('', map { "$_\n" } @files));
open my $output_fh, '>:raw', $output or die $!;
print {$output_fh} JSON::PP->new->encode({
    summary => {candidate_files => 1}, regressions => [], missing_files => [],
    added_files => [], execution_issues => [], zero_tap => [], truncated => [],
    new_invalid => [], inherited_invalid => [],
    compared_files => \@files, compared_files_sha256 => $digest,
});
close $output_fh;
print "Compared file identity: files=", scalar(@files), " sha256=$digest\n";
print "  $_\n" for @files;
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
local $ENV{REGEX_IMPLEMENTATION_CPU_HEAVY_JOBS} = 3;
local $ENV{CPU_HEAVY_JOBS} = 3;
is(run(@common, '--artifact-dir', $default_artifacts), 0,
    'omitting CPU-heavy budget preserves the compatible default');
my $default_manifest = load_json(
    File::Spec->catfile($default_artifacts, 'manifest.json'));
is($default_manifest->{identity}{runner_policy}{cpu_heavy_jobs}, 2,
    'manifest identity records default 2 despite adversarial environment');
is($default_manifest->{expected_files}, 1,
    'manifest records the corpus count observed from the generated ledger');
is($default_manifest->{identity}{baseline}{path}, abs_path($baseline),
    'manifest binds the absolute authority-selected baseline path');
is($default_manifest->{identity}{baseline}{sha256}, hash_file($baseline),
    'manifest binds the authority-selected baseline bytes');
is($default_manifest->{identity}{jar}{path}, abs_path($jar),
    'manifest binds the absolute sealed JAR path');
is($default_manifest->{identity}{jar}{sha256}, hash_file($jar),
    'manifest binds the sealed JAR bytes');

my @runner_calls = map { JSON::PP->new->decode($_) }
    grep { length } split /\n/, read_file($record);
is(scalar @runner_calls, 4, 'two legs execute for each bounded fixture run');
is_deeply([map { option_value($_, '--cpu-heavy-jobs') } @runner_calls],
    [3, 3, 2, 2], 'both perl_test_runner invocations receive each selected budget');

for my $case (
    ['zero', 0],
    ['negative', -1],
    ['above final policy maximum', 4],
    ['fractional', '1.5'],
    ['non-numeric', 'many'],
    ['empty', ''],
) {
    my ($label, $value) = @$case;
    my ($status, $output) = capture(@common,
        '--artifact-dir', $temporary, '--cpu-heavy-jobs', $value);
    isnt($status, 0, "$label CPU-heavy budget is rejected");
    like($output,
        qr/(?:cpu-heavy-jobs must be between 1 and 3|invalid for option cpu-heavy-jobs)/i,
        "$label rejection reports invalid bounded input");
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

my ($case_status, $case_output) = capture($^X, $tool,
    '--CPU-heavy-jobs', 2);
isnt($case_status, 0, 'case-varied producer option is rejected');
like($case_output, qr/Unknown option: CPU-heavy-jobs/,
    'producer preserves one canonical option spelling');

for my $runner_case (
    ['runner upper bound', '--cpu-heavy-jobs', 4,
        qr/cpu-heavy-jobs must be between 1 and 3/],
    ['runner abbreviation', '--cpu-heavy-job', 2,
        qr/Unknown option: cpu-heavy-job/],
    ['runner case variation', '--CPU-heavy-jobs', 2,
        qr/Unknown option: CPU-heavy-jobs/],
) {
    my ($label, $option, $value, $diagnostic) = @$runner_case;
    my ($status, $output) = capture($^X,
        File::Spec->catfile($root, 'dev', 'tools', 'perl_test_runner.pl'),
        $option, $value, $test_file);
    isnt($status, 0, "$label is rejected");
    like($output, $diagnostic, "$label has a specific diagnostic");
}
my ($runner_duplicate_status, $runner_duplicate_output) = capture($^X,
    File::Spec->catfile($root, 'dev', 'tools', 'perl_test_runner.pl'),
    '--cpu-heavy-jobs', 1, '--cpu-heavy-jobs=2', $test_file);
is($runner_duplicate_status, 255, 'runner duplicate lane option is rejected');
like($runner_duplicate_output, qr/Duplicate option --cpu-heavy-jobs/,
    'runner duplicate rejection identifies the repeated option');

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

sub hash_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh or die "Cannot close $path: $!";
    return $sha->hexdigest;
}
