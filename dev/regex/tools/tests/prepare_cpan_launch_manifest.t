use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'prepare_cpan_launch_manifest.pl');
my $cpan_runner = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'run_cpan_acceptance.pl');
my $temporary = abs_path(tempdir(CLEANUP => 1));
my $command_id = 0;
my $source = repository(File::Spec->catdir($temporary, 'source'));
my $perl5 = repository(File::Spec->catdir($temporary, 'perl5'));
my $source_commit = git_commit($source);
my $perl5_commit = git_commit($perl5);

my $jperl = File::Spec->catfile($source, 'jperl');
my $jcpan = File::Spec->catfile($source, 'jcpan');
write_file($jperl, "#!/usr/bin/env perl\nprint qq{fake $source_commit\\n};\n");
write_file($jcpan, "#!/usr/bin/env perl\ndie qq{must not execute fake jcpan\\n};\n");
chmod 0755, $jperl, $jcpan or die 'chmod launchers';
git_add_commit($source, 'launchers', 'jperl', 'jcpan');
$source_commit = git_commit($source);
# The fixture jperl must report the final commit, but the launch-manifest bridge
# hashes it and never executes it. Keep the tracked bytes stable after commit.

my $generated = File::Spec->catdir($temporary, 'generated');
my $artifacts = File::Spec->catdir($temporary, 'regex artifacts');
my $output_dir = File::Spec->catdir($temporary, 'launch output');
make_path($generated, $artifacts, $output_dir);
my $jar = File::Spec->catfile($generated, 'perlonjava.jar');
my $sbom = File::Spec->catfile($generated, 'sbom.json');
my $baseline = File::Spec->catfile($generated, 'baseline.log');
write_file($jar, "jar bytes\n");
write_file($sbom, "{\"bomFormat\":\"CycloneDX\"}\n");
write_file($baseline, "[ 1/1 ] fixture.t ... 1/1 ok\n");
my @artifact_names = qw(regex-ledger.json regex-files.txt
    strict-regex-ledger.json regex-scope-files.txt strict-regex-files.txt
    jvm-results.json interpreter-results.json jvm-comparison.json
    interpreter-comparison.json jvm-strict-regex-comparison.json
    interpreter-strict-regex-comparison.json ledger.log
    strict-regex-ledger.log jvm-runner.log interpreter-runner.log
    jvm-comparison.log interpreter-comparison.log
    jvm-strict-regex-comparison.log interpreter-strict-regex-comparison.log
    packaging.log jperl-version.log);
my %artifact_path = map { $_ => File::Spec->catfile($artifacts, $_) }
    @artifact_names;
for my $name (@artifact_names) {
    write_file($artifact_path{$name}, "$name fixture\n");
}
my $ledger = $artifact_path{'regex-ledger.json'};
write_file($artifact_path{'jperl-version.log'}, "PerlOnJava $source_commit\n");
my $corpus_path = File::Spec->catfile($artifacts, 'manifest.json');
my $output = File::Spec->catfile($output_dir, 'cpan-launch.json');
my $corpus = corpus_document();
write_json($corpus_path, $corpus);

my ($compile_status, $compile_text) = run_command($^X, '-c', $tool);
is($compile_status, 0, 'launch-manifest producer compiles with system Perl');
like($compile_text, qr/syntax OK/, 'compile check is warning-free');

my ($status, $text) = run_tool();
is($status, 0, 'canonical launch manifest is produced');
is($text, "$output\n", 'producer prints only the canonical output path');
ok(-f $output && !-l $output, 'output is a nonsymlink regular file');
my $launch = load_json($output);
is_deeply([sort keys %$launch], [qw(identity inputs mode schema_version)],
    'output has exactly the CPAN runner top-level schema');
is_deeply([sort keys %{$launch->{identity}}], [qw(jar_sha256 jperl_sha256
    perl5_commit runner_commit sbom_sha256 source_commit)],
    'output has exactly the flat CPAN identity schema');
is_deeply([sort keys %{$launch->{inputs}}], [qw(jar jcpan jperl perl5 sbom source)],
    'output has exactly the six CPAN input descriptors');
is($launch->{identity}{source_commit}, $source_commit,
    'source identity comes from the selected checkout');
is($launch->{identity}{perl5_commit}, $perl5_commit,
    'perl5 identity comes from the selected checkout');
is($launch->{inputs}{jcpan}{sha256}, hash_file($jcpan),
    'jcpan bytes are independently hashed');
is($launch->{inputs}{jar}{sha256}, hash_file($jar),
    'JAR bytes are independently hashed');
ok(!scalar(grep { /\.tmp\.\d+\z/ } directory_entries($output_dir)),
    'atomic publication leaves no temporary output');

my $policy = File::Spec->catfile($temporary, 'policy.json');
write_json($policy, {
    schema_version => 1,
    expected_targets => ['Never::Run'],
    targets => [{ name => 'Never::Run', rationale => 'schema probe',
        timeout_seconds => 1, required_modes => [qw(jvm interpreter)],
        focused_selector_permitted => JSON::PP::false,
        approved_warning_patterns => [] }],
});
my ($accept_status, $accept_text) = run_command($^X, $cpan_runner,
    '--manifest', $output, '--policy', $policy, '--evidence-dir',
    File::Spec->rootdir, '--prepare-only', '--jperl', $jperl, '--jcpan', $jcpan);
is($accept_status, 255,
    'existing CPAN runner validates the generated manifest before its safety stop');
like($accept_text, qr/unsafe evidence directory/,
    'CPAN runner reaches the post-schema evidence-directory boundary');
unlike($accept_text, qr/Acceptance manifest|differs from manifest|commit mismatch/,
    'CPAN runner reports no launch-manifest schema or identity error');

my ($overwrite_status, $overwrite_text) = run_tool();
is($overwrite_status, 255, 'existing output is never overwritten');
like($overwrite_text, qr/Refusing to overwrite launch manifest/,
    'overwrite refusal is explicit');

unlink $output or die "unlink fixture output: $!";
my $stale = clone($corpus);
$stale->{identity}{baseline}{sha256} = '0' x 64;
write_json($corpus_path, $stale);
assert_rejected(qr/Corpus baseline hash differs/, 'stale baseline binding is rejected');

my $cross = clone($corpus);
$cross->{identity}{source_commit} = 'a' x 40;
write_json($corpus_path, $cross);
assert_rejected(qr/Corpus source identity differs/, 'cross-source identity is rejected');

my $extra = clone($corpus);
$extra->{identity}{flattened_source_commit} = $source_commit;
write_json($corpus_path, $extra);
assert_rejected(qr/corpus identity has missing, extra, or duplicate fields/,
    'flattened self-declaration is rejected as an extra field');

write_json($corpus_path, $corpus);
my $duplicate = read_file($corpus_path);
$duplicate =~ s/"mode"\s*:\s*"acceptance"/"mode":"acceptance","mode":"acceptance"/
    or die 'duplicate fixture substitution';
write_file($corpus_path, $duplicate);
assert_rejected(qr/duplicate JSON key: mode/,
    'duplicate JSON keys are rejected before decoding');

write_json($corpus_path, $corpus);
write_file($ledger, "mutated\n");
assert_rejected(qr/Corpus artifact hash mismatch: regex-ledger\.json/,
    'mutated retained corpus artifact is rejected');
write_file($ledger, "regex-ledger.json fixture\n");

SKIP: {
    my $outside = File::Spec->catfile($temporary, 'outside-jcpan');
    write_file($outside, read_file($jcpan));
    chmod 0755, $outside or die 'chmod outside launcher';
    my $link = File::Spec->catfile($source, 'jcpan-link');
    skip 'symlinks unavailable on this platform', 2 unless symlink($outside, $link);
    my ($link_status, $link_text) = run_tool('--jcpan', $link);
    is($link_status, 255, 'symlink launcher is rejected');
    like($link_text, qr/must be a nonsymlink regular file|must be canonical/,
        'symlink rejection is explicit');
}

my ($relative_status, $relative_text) = run_tool('--baseline', 'relative.log');
is($relative_status, 255, 'relative authority input is rejected');
like($relative_text, qr/baseline path must be absolute/,
    'relative input has a canonical-path diagnostic');

my ($abbrev_status, $abbrev_text) = run_command($^X, $tool, '--source', $source);
is($abbrev_status, 2, 'abbreviated option is rejected');
like($abbrev_text, qr/Unknown option: source/,
    'strict option parser does not infer authority options');
ok(!-e $output, 'no output is published by any rejected case');

done_testing();

sub corpus_document {
    return {
        schema_version => 1,
        mode => 'acceptance',
        source => {
            starting_sha => $source_commit,
            final_sha => $source_commit,
            perl5_sha_as_provenance => $perl5_commit,
            tracked_state_signature => sha256_hex(''),
        },
        identity => {
            source_commit => $source_commit,
            runner_commit => $source_commit,
            perl5_commit => $perl5_commit,
            launcher => { path => $jperl, sha256 => hash_file($jperl) },
            jar => { path => $jar, sha256 => hash_file($jar) },
            sbom => { path => $sbom, sha256 => hash_file($sbom) },
            baseline => { path => $baseline, sha256 => hash_file($baseline) },
        },
        baseline => $baseline,
        artifact_directory => $artifacts,
        expected_files => 623,
        strict_regex_expected_files => 84,
        verified_runner_sha => $source_commit,
        ledger_summary => {},
        strict_regex_ledger_summary => {},
        commands => [map {
            my $name = $_;
            $name eq 'jperl-version'
                ? { name => $name, argv => [$jperl, '-v'], environment => {
                    JPERL_UNIMPLEMENTED => undef, PERLONJAVA_JAR => $jar } }
                : { name => $name, argv => [$^X], environment => {} }
        } qw(jperl-version ledger strict-regex-ledger jvm-runner
            interpreter-runner jvm-comparison interpreter-comparison
            jvm-strict-regex-comparison interpreter-strict-regex-comparison
            packaging)],
        exit_statuses => { map { $_ => 0 } qw(jperl-version ledger
            strict-regex-ledger jvm-runner interpreter-runner jvm-comparison
            interpreter-comparison jvm-strict-regex-comparison
            interpreter-strict-regex-comparison packaging) },
        artifacts => { map { $_ => { path => $artifact_path{$_},
            sha256 => hash_file($artifact_path{$_}) } } @artifact_names },
    };
}

sub run_tool {
    my @override = @_;
    my %argument = (
        '--source-dir' => $source,
        '--perl5-dir' => $perl5,
        '--jperl' => $jperl,
        '--jcpan' => $jcpan,
        '--jar' => $jar,
        '--sbom' => $sbom,
        '--baseline' => $baseline,
        '--corpus-manifest' => $corpus_path,
        '--output' => $output,
    );
    while (@override) {
        my ($name, $value) = splice @override, 0, 2;
        $argument{$name} = $value;
    }
    my @argv;
    for my $name (qw(--source-dir --perl5-dir --jperl --jcpan --jar --sbom
            --baseline --corpus-manifest --output)) {
        push @argv, $name, $argument{$name};
    }
    return run_command($^X, $tool, @argv);
}

sub assert_rejected {
    my ($pattern, $name) = @_;
    my ($case_status, $case_text) = run_tool();
    is($case_status, 255, $name);
    like($case_text, $pattern, "$name has a specific diagnostic");
    ok(!-e $output, "$name publishes no output");
}

sub repository {
    my ($path) = @_;
    make_path($path);
    system('git', 'init', '-q', $path) == 0 or die 'git init';
    system('git', '-C', $path, 'config', 'user.email', 'fixture@example.test') == 0
        or die 'git config email';
    system('git', '-C', $path, 'config', 'user.name', 'Fixture') == 0
        or die 'git config name';
    write_file(File::Spec->catfile($path, 'tracked'), "fixture\n");
    git_add_commit($path, 'initial', 'tracked');
    return abs_path($path);
}

sub git_add_commit {
    my ($repository, $message, @files) = @_;
    system('git', '-C', $repository, 'add', '--', @files) == 0 or die 'git add';
    system('git', '-C', $repository, 'commit', '-qm', $message) == 0
        or die 'git commit';
}

sub git_commit {
    my ($repository) = @_;
    open my $fh, '-|', 'git', '-C', $repository, 'rev-parse', 'HEAD'
        or die 'git rev-parse';
    my $commit = <$fh>;
    close $fh or die 'git rev-parse';
    $commit =~ s/\s+\z//;
    return $commit;
}

sub run_command {
    my @argv = @_;
    my $log = File::Spec->catfile($temporary, 'command-' . ++$command_id . '.log');
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if (!$pid) {
        open STDOUT, '>:raw', $log or die "open command log: $!";
        open STDERR, '>&', \*STDOUT or die "redirect stderr: $!";
        exec { $argv[0] } @argv or die "exec $argv[0]: $!";
    }
    waitpid($pid, 0);
    my $raw = $?;
    return (($raw & 127) ? 128 + ($raw & 127) : $raw >> 8, read_file($log));
}

sub clone { JSON::PP->new->decode(JSON::PP->new->encode($_[0])) }
sub write_json { write_file($_[0], JSON::PP->new->canonical->pretty->encode($_[1])) }
sub load_json { JSON::PP->new->decode(read_file($_[0])) }
sub hash_file { sha256_hex(read_file($_[0])) }
sub directory_entries {
    opendir my $dh, $_[0] or die "opendir $_[0]: $!";
    my @entries = grep { $_ ne '.' && $_ ne '..' } readdir $dh;
    closedir $dh;
    return @entries;
}
sub write_file {
    my ($path, $bytes) = @_;
    open my $fh, '>:raw', $path or die "write $path: $!";
    print {$fh} $bytes;
    close $fh or die "close $path: $!";
}
sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "read $path: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh;
    return $bytes;
}
