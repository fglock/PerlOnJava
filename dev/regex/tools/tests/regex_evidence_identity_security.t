use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $comparator = File::Spec->catfile(
    $root, 'dev', 'tools', 'compare_test_results.pl');
my $producer = File::Spec->catfile(
    $root, 'dev', 'regex', 'tools', 'run_regex_acceptance.pl');
my $temporary = tempdir(CLEANUP => 1);

my @files = qw(
    perl5_t/t/re/alpha/shared.t
    perl5_t/t/re/beta/shared.t
);
my $canonical_bytes = join('', map { "$_\n" } @files);
my $canonical_sha = sha256_hex($canonical_bytes);
my $raw_index_supported = 0;

subtest 'file-list comparison publishes canonical exact identity' => sub {
    my $baseline = write_runner_json('compare-baseline.json', \@files);
    my $candidate = write_runner_json('compare-candidate.json', \@files);
    my $list = write_file(File::Spec->catfile($temporary, 'compare-files.txt'),
        "./$files[0]\n./$files[1]\n");
    my $report = File::Spec->catfile($temporary, 'compare-report.json');

    my ($status, $output) = capture(
        $^X, $comparator, '--fail-on-invalid', '--expected-files', 2,
        '--file-list', $list, '--output', $report, $baseline, $candidate);
    is($status, 0, 'canonical exact comparison succeeds');
    my $document = load_json($report);
    is_deeply($document->{compared_files}, \@files,
        'report retains the normalized sorted exact compared-file list');
    is($document->{compared_files_sha256}, $canonical_sha,
        'report binds canonical newline-delimited list bytes with SHA-256');
    like($output, qr/\Q$canonical_sha\E/,
        'retained human log binds the same compared-file digest');
    like($output, qr/\Q$files[0]\E.*\Q$files[1]\E/s,
        'retained human log binds the complete ordered compared-file list');
};

subtest 'file-list identity input fails closed' => sub {
    my $baseline = write_runner_json('invalid-baseline.json', \@files);
    my $candidate = write_runner_json('invalid-candidate.json', \@files);
    my @cases = (
        ['missing selected file', [@files, 'perl5_t/t/re/missing.t']],
        ['duplicate selected file', [$files[0], $files[0], $files[1]]],
        ['unsorted selected files', [reverse @files]],
        ['parent traversal', ['../escape.t', @files]],
        ['absolute path', ['/tmp/escape.t', @files]],
    );
    for my $case (@cases) {
        my ($name, $members) = @$case;
        my $list = write_file(File::Spec->catfile($temporary,
            "invalid-$name.txt"),
            join('', map { "$_\n" } @$members));
        my $report = File::Spec->catfile($temporary, "invalid-$name.json");
        my ($status) = capture(
            $^X, $comparator, '--fail-on-invalid',
            '--expected-files', scalar(@$members), '--file-list', $list,
            '--output', $report, $baseline, $candidate);
        isnt($status, 0, "$name is rejected");
        ok(!-e $report, "$name publishes no comparison authority");
    }
};

my $fixture = make_producer_fixture();

subtest 'regex producer propagates exact comparator identity' => sub {
    for my $case (
        ['missing identity', 'missing'],
        ['same-count wrong set', 'wrong-set'],
        ['tampered identity digest', 'tampered'],
        ['strict comparison log without identity', 'strict-log-missing'],
    ) {
        my ($name, $mode) = @$case;
        my ($status, $artifacts) = run_producer(
            $fixture, "comparison-$mode", $mode, 'valid');
        isnt($status, 0, "$name is rejected by the producer");
        ok(!-e File::Spec->catfile($artifacts, 'manifest.json'),
            "$name publishes no regex manifest authority");
    }
};

subtest 'raw TAP index is stable, complete, collision-free, and retained' => sub {
    my ($status, $artifacts, $output) = run_producer(
        $fixture, 'raw-positive', 'valid', 'valid');
    is($status, 0, 'two-backend same-basename raw TAP fixture succeeds')
        or diag($output);
    my @entries = discover_raw_tap_entries($artifacts);
    return unless @entries;
    $raw_index_supported = 1;
    is(scalar(@entries), 4,
        'index has exactly one member for each backend and runner row');
    my ($repeat_status, $repeat_artifacts, $repeat_output) = run_producer(
        $fixture, 'raw-positive-repeat', 'valid', 'valid');
    is($repeat_status, 0, 'identical repeated fixture succeeds')
        or diag($repeat_output);
    my @repeat_entries = discover_raw_tap_entries($repeat_artifacts);
    is_deeply(
        [map { stable_entry($_, $artifacts) } @entries],
        [map { stable_entry($_, $repeat_artifacts) } @repeat_entries],
        'repeated production yields the same canonical raw TAP index identities');

    my %identity;
    my %path;
    for my $entry (@entries) {
        ok($entry->{backend} =~ /\A(?:jvm|interpreter)\z/,
            'entry has a canonical backend identity');
        ok(grep($_ eq $entry->{file}, @files),
            'entry has an exact runner-row identity');
        ok(!$identity{"$entry->{backend}\0$entry->{file}"}++,
            'backend/file identity occurs exactly once');
        ok(!$path{$entry->{path}}++,
            'retained path is collision-free despite equal basenames');
        my $absolute = retained_path($artifacts, $entry->{path});
        ok(path_is_beneath($absolute, $artifacts),
            'retained path remains beneath the evidence root');
        ok(-f $absolute && !-l $absolute,
            'retained raw TAP is a regular nonsymlink file');
        is(-s $absolute, $entry->{size}, 'retained byte size is authenticated');
        is(hash_file($absolute), $entry->{sha256},
            'retained raw TAP SHA-256 authenticates its bytes');
    }
};

subtest 'raw TAP authority rejects hostile or incomplete runner evidence' => sub {
    plan skip_all => 'producer has not implemented the named raw TAP index yet'
        unless $raw_index_supported;
    my @cases = (
        ['missing raw TAP', 'missing'],
        ['symlinked raw TAP', 'symlink'],
        ['raw TAP path escape', 'escape'],
        ['duplicate row binding', 'duplicate'],
        ['unindexed raw TAP', 'unindexed'],
        ['post-index byte mutation', 'mutate'],
        ['post-index path replacement', 'replace'],
        ['wrong indexed byte size', 'wrong-size'],
        ['wrong indexed byte digest', 'wrong-hash'],
        ['wrong indexed runner-row binding', 'wrong-row'],
    );
    for my $case (@cases) {
        my ($name, $mode) = @$case;
        my ($status, $artifacts) = run_producer(
            $fixture, "raw-$mode", 'valid', $mode);
        isnt($status, 0, "$name is rejected");
        ok(!-e File::Spec->catfile($artifacts, 'manifest.json'),
            "$name publishes no regex manifest authority");
    }
};

done_testing;

sub make_producer_fixture {
    my $directory = File::Spec->catdir($temporary, 'producer-fixture');
    my $source = File::Spec->catdir($directory, 'source');
    make_path($source);
    system('git', 'init', '-q', $source) == 0
        or die "cannot initialize source fixture";
    system('git', '-C', $source, 'config', 'user.email',
        'fixture\@example.test') == 0 or die "cannot configure fixture";
    system('git', '-C', $source, 'config', 'user.name', 'Fixture') == 0
        or die "cannot configure fixture";
    write_file(File::Spec->catfile($source, 'tracked.txt'), "fixture\n");
    for my $file (@files) {
        write_file(File::Spec->catfile($source, split m{/}, $file),
            "1..1\nok 1 - fixture\n");
    }
    system('git', '-C', $source, 'add', 'tracked.txt', 'perl5_t') == 0
        or die "cannot stage fixture";
    system('git', '-C', $source, 'commit', '-qm', 'fixture') == 0
        or die "cannot commit fixture";
    my $sha = capture_stdout('git', '-C', $source, 'rev-parse', 'HEAD');
    chomp $sha;

    my $baseline = write_file(File::Spec->catfile($directory, 'baseline.log'),
        join('', map { "[  1/2] $_ ... . 1/1 ok\n" } @files));
    my $jar = write_file(File::Spec->catfile($directory, 'candidate.jar'),
        "fixture jar\n");
    my $sbom = write_file(File::Spec->catfile($directory, 'sbom.json'), "{}\n");
    my $ledger = executable(File::Spec->catfile($directory, 'ledger.pl'), <<'LEDGER');
use strict;
use warnings;
use JSON::PP;
my ($list, $output);
while (@ARGV) {
    my $arg = shift @ARGV;
    $list = shift @ARGV if $arg eq '--runner-list';
    $output = shift @ARGV if $arg eq '--output';
}
my @files = split /\n/, $ENV{SECURITY_FILES};
open my $lfh, '>:raw', $list or die $!;
print {$lfh} join('', map { "$_\n" } @files);
close $lfh or die $!;
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->canonical->encode({
    schema_version => 1,
    summary => { unresolved_references => 0, runner_files => scalar @files },
    runner_files => \@files,
    core_re_files => \@files,
    documented_unit_gates => [], direct_thread_pairs => [], thread_only_tests => [],
});
close $ofh or die $!;
LEDGER
    my $runner = executable(File::Spec->catfile($directory, 'runner.pl'), <<'RUNNER');
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use JSON::PP;
my ($output, $raw_dir);
my @tests;
while (@ARGV) {
    my $arg = shift @ARGV;
    if ($arg eq '--output') { $output = shift @ARGV; next }
    if ($arg eq '--raw-output-dir') { $raw_dir = shift @ARGV; next }
    if ($arg =~ /\A--(?:jperl|timeout|jobs|cpu-heavy-jobs)\z/) { shift @ARGV; next }
    next if $arg =~ /\A--/;
    push @tests, $arg;
}
my $backend = $ENV{JPERL_INTERPRETER} ? 'interpreter' : 'jvm';
$raw_dir //= File::Spec->catdir($ENV{SECURITY_TEMP}, 'external-raw',
    $ENV{SECURITY_CASE}, $backend);
make_path($raw_dir);
my %results;
my $shared;
for my $index (0 .. $#tests) {
    my $file = $tests[$index];
    my $path = File::Spec->catfile($raw_dir, "row-$index.tap");
    $path = $shared if $ENV{SECURITY_RAW_MODE} eq 'duplicate' && defined $shared;
    if ($ENV{SECURITY_RAW_MODE} eq 'escape' && $index == 0) {
        $path = File::Spec->catfile(dirname($raw_dir), 'escaped.tap');
    }
    my $bytes = "1..1\nok 1 - $backend $file\n";
    if ($ENV{SECURITY_RAW_MODE} eq 'symlink' && $index == 0) {
        my $target = "$path.target";
        open my $tfh, '>:raw', $target or die $!;
        print {$tfh} $bytes;
        close $tfh or die $!;
        symlink $target, $path or die $!;
    } elsif (!($ENV{SECURITY_RAW_MODE} eq 'missing' && $index == 0)
            && !-e $path) {
        open my $fh, '>:raw', $path or die $!;
        print {$fh} $bytes;
        close $fh or die $!;
    }
    $shared //= $path;
    $results{$file} = {
        file => $file, status => 'pass', ok_count => 1, not_ok_count => 0,
        total_tests => 1, planned_tests => 1, actual_tests_run => 1,
        incomplete_tests => 0, skip_count => 0, todo_count => 0,
        errors => [], missing_features => [], exit_code => 0,
        raw_output_path => $path,
    };
}
if ($ENV{SECURITY_RAW_MODE} eq 'unindexed') {
    open my $fh, '>:raw', File::Spec->catfile($raw_dir, 'extra.tap') or die $!;
    print {$fh} "1..1\nok 1 - unindexed\n";
    close $fh or die $!;
}
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->canonical->encode({
    summary => { pass => scalar(@tests), fail => 0, error => 0, timeout => 0,
        incomplete => 0, total_ok => scalar(@tests), total_not_ok => 0,
        total_tests => scalar(@tests), total_skipped => 0, total_todo => 0 },
    feature_impact => {}, results => \%results,
});
close $ofh or die $!;
RUNNER
    my $comparison = executable(
        File::Spec->catfile($directory, 'comparison.pl'), <<'COMPARISON');
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use JSON::PP;
my ($output, $list);
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
    $list = shift @ARGV if $arg eq '--file-list';
}
open my $lfh, '<:raw', $list or die $!;
my @files = grep { length } map { chomp; $_ } <$lfh>;
close $lfh or die $!;
@files = sort @files;
my @identity = @files;
@identity = ($files[0], 'perl5_t/t/re/wrong/shared.t')
    if $ENV{SECURITY_COMPARE_MODE} eq 'wrong-set';
my $sha = sha256_hex(join('', map { "$_\n" } @identity));
$sha = '0' x 64 if $ENV{SECURITY_COMPARE_MODE} eq 'tampered';
my $document = {
    summary => { candidate_files => scalar @files }, expected_files => scalar @files,
    regressions => [], improvements => [], plan_changes => [], missing_files => [],
    added_files => [], execution_issues => [], zero_tap => [], truncated => [],
    new_invalid => [], inherited_invalid => [],
};
if ($ENV{SECURITY_COMPARE_MODE} ne 'missing') {
    $document->{compared_files} = \@identity;
    $document->{compared_files_sha256} = $sha;
}
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->canonical->encode($document);
close $ofh or die $!;
unless ($ENV{SECURITY_COMPARE_MODE} eq 'strict-log-missing'
        && $output =~ /strict-regex/) {
    print "Compared files: ", join(' ', @identity),
        "\nCompared files SHA-256: $sha\n";
}
if ($ENV{SECURITY_RAW_MODE} =~ /\A(?:mutate|replace|wrong-size|wrong-hash|wrong-row)\z/
        && $output =~ /jvm-comparison\.json\z/) {
    my $index_path = "$ENV{SECURITY_ARTIFACTS}/raw-tap-index.json";
    open my $ifh, '<:raw', $index_path or die "missing named raw TAP index: $!";
    my $index = JSON::PP->new->decode(do { local $/; <$ifh> });
    close $ifh or die $!;
    my @selected = grep {
        ($_->{backend} // '') eq 'jvm'
            && ($_->{file} // '') eq 'perl5_t/t/re/alpha/shared.t'
    } @{$index->{entries} // []};
    die "expected exactly one index-bound mutation target\n"
        unless @selected == 1;
    my $entry = $selected[0];
    my $target = "$ENV{SECURITY_ARTIFACTS}/$entry->{path}";
    die "indexed target size drifted before mutation\n"
        unless -f $target && -s $target == $entry->{size};
    open my $tfh, '<:raw', $target or die $!;
    my $bytes = do { local $/; <$tfh> };
    close $tfh or die $!;
    die "indexed target hash drifted before mutation\n"
        unless sha256_hex($bytes) eq $entry->{sha256};
    if ($ENV{SECURITY_RAW_MODE} eq 'mutate') {
        open my $mfh, '>>:raw', $target or die $!;
        print {$mfh} "# mutated\n";
        close $mfh or die $!;
    } elsif ($ENV{SECURITY_RAW_MODE} eq 'replace') {
        unlink $target or die $!;
        symlink $ENV{SECURITY_REPLACEMENT}, $target or die $!;
    } elsif ($ENV{SECURITY_RAW_MODE} eq 'wrong-size') {
        ++$entry->{size};
    } elsif ($ENV{SECURITY_RAW_MODE} eq 'wrong-hash') {
        $entry->{sha256} = '0' x 64;
    } else {
        $entry->{file} = 'perl5_t/t/re/beta/shared.t';
    }
    if ($ENV{SECURITY_RAW_MODE} =~ /\Awrong-/) {
        open my $ofh, '>:raw', $index_path or die $!;
        print {$ofh} JSON::PP->new->canonical->encode($index);
        close $ofh or die $!;
    }
}
COMPARISON
    my $packaging = executable(
        File::Spec->catfile($directory, 'packaging.pl'),
        "use strict; use warnings; print qq{packaging passed\\n};\n");
    my $jperl = executable(File::Spec->catfile($directory, 'jperl'),
        "#!/usr/bin/env perl\nprint qq{PerlOnJava fixture $sha\\n};\n");
    my $replacement = write_file(
        File::Spec->catfile($directory, 'replacement.tap'), "1..1\nnot ok 1\n");
    return {
        directory => $directory, source => $source, baseline => $baseline,
        jar => $jar, sbom => $sbom, ledger => $ledger, runner => $runner,
        comparison => $comparison, packaging => $packaging, jperl => $jperl,
        replacement => $replacement,
    };
}

sub run_producer {
    my ($fixture, $name, $compare_mode, $raw_mode) = @_;
    my $artifacts = File::Spec->catdir($temporary, $name);
    make_path($artifacts);
    local $ENV{SECURITY_FILES} = join("\n", @files);
    local $ENV{SECURITY_TEMP} = $temporary;
    local $ENV{SECURITY_CASE} = $name;
    local $ENV{SECURITY_ARTIFACTS} = $artifacts;
    local $ENV{SECURITY_REPLACEMENT} = $fixture->{replacement};
    local $ENV{SECURITY_COMPARE_MODE} = $compare_mode;
    local $ENV{SECURITY_RAW_MODE} = $raw_mode;
    my ($status, $output) = capture_in($fixture->{source},
        $^X, $producer, '--prepare-only',
        '--baseline', $fixture->{baseline}, '--artifact-dir', $artifacts,
        '--jar', $fixture->{jar}, '--sbom', $fixture->{sbom},
        '--source-dir', $fixture->{source}, '--perl5-dir', $fixture->{source},
        '--jperl', $fixture->{jperl}, '--ledger-tool', $fixture->{ledger},
        '--runner-tool', $fixture->{runner},
        '--comparator-tool', $fixture->{comparison},
        '--packaging-tool', $fixture->{packaging}, '--timeout', 5, '--jobs', 2);
    return ($status, $artifacts, $output);
}

sub discover_raw_tap_entries {
    my ($artifacts) = @_;
    my $manifest = load_json(File::Spec->catfile($artifacts, 'manifest.json'));
    my $descriptor = $manifest->{artifacts}{'raw-tap-index.json'};
    ok(ref($descriptor) eq 'HASH',
        'manifest names exactly one raw-tap-index.json descriptor');
    return unless ref($descriptor) eq 'HASH';
    my $index_path = File::Spec->file_name_is_absolute($descriptor->{path})
        ? $descriptor->{path} : retained_path($artifacts, $descriptor->{path});
    is(hash_file($index_path), $descriptor->{sha256},
        'manifest authenticates the named raw TAP index bytes');
    my $index = load_json($index_path);
    is($index->{kind}, 'regex-raw-tap-index',
        'named raw TAP index has the current schema kind');
    my @entries = @{$index->{entries} // []};
    my (%identity, %path);
    for my $entry (@entries) {
        ok(!$identity{"$entry->{backend}\0$entry->{file}"}++,
            'raw TAP index has no duplicate backend/file record');
        ok(!$path{$entry->{path}}++,
            'raw TAP index has no duplicate retained path');
    }
    return sort {
        $a->{backend} cmp $b->{backend} || $a->{file} cmp $b->{file}
    } @entries;
}

sub retained_path {
    my ($root, $path) = @_;
    die "retained path is not normalized relative: $path"
        if File::Spec->file_name_is_absolute($path)
            || $path =~ m{(?:\A|/)\.\.(?:/|\z)};
    return File::Spec->catfile($root, split m{/}, $path);
}

sub stable_entry {
    my ($entry, $root) = @_;
    my $path = retained_path($root, $entry->{path});
    my $relative = path_is_beneath($path, $root)
        ? File::Spec->abs2rel($path, $root) : $entry->{path};
    $relative =~ s{\\}{/}g;
    return [@{$entry}{qw(backend file size sha256)}, $relative];
}

sub path_is_beneath {
    my ($path, $root) = @_;
    my $absolute_path = abs_path($path);
    my $absolute_root = abs_path($root);
    return 0 unless defined $absolute_path && defined $absolute_root;
    return index($absolute_path, "$absolute_root/") == 0;
}

sub write_runner_json {
    my ($name, $files) = @_;
    my %results = map { $_ => {
        file => $_, status => 'pass', ok_count => 1, not_ok_count => 0,
        total_tests => 1, planned_tests => 1, actual_tests_run => 1,
        incomplete_tests => 0, skip_count => 0, todo_count => 0,
        errors => [], missing_features => [], exit_code => 0,
    } } @$files;
    return write_file(File::Spec->catfile($temporary, $name),
        JSON::PP->new->canonical->encode({ results => \%results }));
}

sub executable {
    my ($path, $contents) = @_;
    write_file($path, $contents);
    chmod 0755, $path or die "cannot chmod $path: $!";
    return $path;
}

sub write_file {
    my ($path, $contents) = @_;
    make_path(dirname($path));
    open my $fh, '>:raw', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
    return $path;
}

sub load_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "cannot read $path: $!";
    my $bytes = do { local $/; <$fh> };
    close $fh or die "cannot close $path: $!";
    return JSON::PP->new->decode($bytes);
}

sub hash_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "cannot hash $path: $!";
    my $digest = Digest::SHA->new(256);
    $digest->addfile($fh);
    close $fh or die "cannot close $path: $!";
    return $digest->hexdigest;
}

sub capture_stdout {
    my (@command) = @_;
    open my $fh, '-|', @command or die "cannot execute @command: $!";
    my $output = do { local $/; <$fh> };
    close $fh or die "command failed (@command): $?";
    return $output;
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
        die "exec @command: $!";
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    my $status = $?;
    return ($status == -1 ? 255 : $status >> 8, $output);
}

sub capture_in {
    my ($directory, @command) = @_;
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
        close $read;
        chdir $directory or die "cannot chdir $directory: $!";
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $command[0] } @command;
        die "exec @command: $!";
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    my $status = $?;
    return ($status == -1 ? 255 : $status >> 8, $output);
}
