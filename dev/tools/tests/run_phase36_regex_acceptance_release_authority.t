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
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $producer = File::Spec->catfile($root, 'dev', 'tools',
    'run_phase36_regex_acceptance.pl');
my $temporary = abs_path(tempdir(CLEANUP => 1));
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
my $source_commit = capture_success('git', '-C', $source, 'rev-parse', 'HEAD');
$source_commit =~ s/\s+\z//;

my $test_file = File::Spec->catfile($temporary, 'focused.t');
my $baseline = File::Spec->catfile($temporary, 'baseline.log');
my $jar = File::Spec->catfile($temporary, 'release.jar');
my $sbom = File::Spec->catfile($temporary, 'release-sbom.json');
my $backend_marker = File::Spec->catfile($temporary, 'backend-started');
write_file($test_file, "1..1\nok 1\n");
write_file($baseline, "[  1/1] $test_file ... . 1/1 ok\n");
write_file($jar, "sealed release jar\n");
write_file($sbom, "{}\n");

my $ledger = fake_tool('ledger.pl', <<'LEDGER');
use JSON::PP;
my ($list, $output);
while (@ARGV) {
    my $arg = shift @ARGV;
    $list = shift @ARGV if $arg eq '--runner-list';
    $output = shift @ARGV if $arg eq '--output';
}
open my $lfh, '>:raw', $list or die $!;
print {$lfh} "$ENV{AUTH_TEST_FILE}\n";
close $lfh;
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->encode({
    summary => {unresolved_references => 0, runner_files => 1},
    core_re_files => [$ENV{AUTH_TEST_FILE}], documented_unit_gates => [],
    direct_thread_pairs => [], thread_only_tests => [],
});
close $ofh;
LEDGER
my $runner = fake_tool('runner.pl', <<'RUNNER');
use JSON::PP;
open my $marker, '>>:raw', $ENV{AUTH_BACKEND_MARKER} or die $!;
print {$marker} ($ENV{JPERL_INTERPRETER} ? "interpreter\n" : "jvm\n");
close $marker;
my $output;
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
}
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->encode({results => {}});
close $ofh;
RUNNER
my $comparator = fake_tool('comparator.pl', <<'COMPARATOR');
use JSON::PP;
my $output;
while (@ARGV) {
    my $arg = shift @ARGV;
    $output = shift @ARGV if $arg eq '--output';
}
open my $ofh, '>:raw', $output or die $!;
print {$ofh} JSON::PP->new->encode({
    summary => {candidate_files => 1}, regressions => [], missing_files => [],
    added_files => [], execution_issues => [], zero_tap => [], truncated => [],
    new_invalid => [], inherited_invalid => [],
});
close $ofh;
COMPARATOR
my $packaging = fake_tool('packaging.pl', "print qq{packaging passed\\n};\n");
my $jperl = fake_tool('fixture-jperl', <<'JPERL');
#!/usr/bin/env perl
print "PerlOnJava fixture $ENV{AUTH_SOURCE_COMMIT}\n";
JPERL
chmod 0755, $jperl or die "Cannot chmod fixture launcher: $!";

local $ENV{AUTH_TEST_FILE} = $test_file;
local $ENV{AUTH_BACKEND_MARKER} = $backend_marker;
local $ENV{AUTH_SOURCE_COMMIT} = $source_commit;

my $valid = evidence_fixture('valid');
my ($valid_status, $valid_output) = invoke('valid', $valid);
is($valid_status, 0, 'accepted package and make evidence launch both fake backends')
    or diag $valid_output;
is((-e $backend_marker ? read_file($backend_marker) : ''), "jvm\ninterpreter\n",
    'authority validation precedes exactly two backend launches');
my $manifest = read_json(File::Spec->catfile(
    $temporary, 'artifacts-valid', 'manifest.json'));
is_deeply([sort keys %{$manifest->{release_authority}}],
    [sort qw(authoritative kind make_evidence mode package_evidence
        schema_version selected)], 'release_authority has the exact top-level schema');
ok($manifest->{release_authority}{authoritative},
    'acceptance manifest marks validated release authority authoritative');
is($manifest->{release_authority}{package_evidence}{path}, $valid->{package},
    'release authority binds exact package evidence path');
is($manifest->{release_authority}{package_evidence}{sha256},
    hash_file($valid->{package}), 'release authority binds package evidence bytes');
is($manifest->{release_authority}{make_evidence}{path}, $valid->{make},
    'release authority binds exact make evidence path');
is($manifest->{release_authority}{make_evidence}{sha256},
    hash_file($valid->{make}), 'release authority binds make evidence bytes');
is($manifest->{release_authority}{selected}{source_commit}, $source_commit,
    'release authority binds selected source identity');
is($manifest->{release_authority}{selected}{jar}{sha256}, hash_file($jar),
    'release authority binds selected JAR identity');
is($manifest->{release_authority}{selected}{sbom}{sha256}, hash_file($sbom),
    'release authority binds selected SBOM identity');

unlink $backend_marker or die "Cannot clear backend marker: $!";
my ($missing_status, $missing_output) = invoke('missing', undef,
    omit_evidence => 1);
is($missing_status, 255, 'final acceptance rejects missing evidence');
like($missing_output, qr/--package-evidence is required/,
    'missing evidence rejection is explicit');
ok(!-e $backend_marker, 'missing evidence launches no backend');

for my $missing (
    ['package', {omit_package => 1}, qr/--package-evidence is required/],
    ['make', {omit_make => 1}, qr/--make-evidence is required/],
) {
    my ($label, $arguments, $diagnostic) = @$missing;
    my ($status, $output) = invoke("missing-$label", $valid, %$arguments);
    is($status, 255, "missing $label evidence is rejected independently");
    like($output, $diagnostic, "missing $label evidence is diagnosed");
    ok(!-e $backend_marker, "missing $label evidence launches no backend");
}

for my $relative (
    ['package', {package => File::Spec->abs2rel($valid->{package})}],
    ['make', {make => File::Spec->abs2rel($valid->{make})}],
) {
    my ($label, $arguments) = @$relative;
    my ($status, $output) = invoke("relative-$label", $valid, %$arguments);
    is($status, 255, "relative $label evidence path is rejected");
    like($output, qr/$label evidence path must be absolute and canonical/,
        "relative $label path has an exact diagnostic");
    ok(!-e $backend_marker, "relative $label evidence launches no backend");
}

for my $case (
    ['package-report-mode', {package_report => 1},
        qr/package evidence has an extra or missing field|Package evidence is not the accepted authoritative bridge/],
    ['make-non-authoritative', {make_authoritative => 0},
        qr/Make evidence is not authoritative acceptance evidence/],
    ['make-report-mode', {make_mode => 'report'},
        qr/Make evidence is not authoritative acceptance evidence/],
    ['stale-package', {package_source => '1' x 40},
        qr/Package evidence source commit differs/],
    ['stale-make', {make_source => '2' x 40},
        qr/Make source .*identity|Make evidence source commit differs/],
    ['cross-identity', {package_jar_sha => '3' x 64},
        qr/Retained package JAR identity mismatch|Package evidence JAR differs/],
    ['tampered-package', {tamper_package_artifact => 1},
        qr/package deliverables jar (?:size|SHA-256) mismatch/i],
    ['tampered-make', {tamper_make_json => 1},
        qr/Make evidence (?:internal|external) seal mismatch/],
) {
    my ($name, $change, $diagnostic) = @$case;
    my $evidence = evidence_fixture($name, %$change);
    my ($status, $output) = invoke($name, $evidence);
    isnt($status, 0, "$name evidence is rejected");
    like($output, $diagnostic, "$name has a specific fail-closed diagnostic");
    ok(!-e $backend_marker, "$name launches no backend");
}

my $raw_mismatch = evidence_fixture('raw-jar-mismatch');
my $other_jar = File::Spec->catfile($temporary, 'other-release.jar');
write_file($other_jar, "substituted raw jar\n");
my ($raw_status, $raw_output) = invoke('raw-jar-mismatch', $raw_mismatch,
    jar => $other_jar);
isnt($raw_status, 0, 'raw JAR substitution is rejected');
like($raw_output, qr/Package evidence JAR differs from raw --jar input/,
    'raw JAR mismatch identifies the authority disagreement');
ok(!-e $backend_marker, 'raw JAR mismatch launches no backend');

done_testing;

sub evidence_fixture {
    my ($name, %change) = @_;
    my $directory = File::Spec->catdir($temporary, "evidence-$name");
    my $retained = File::Spec->catdir($directory, 'package');
    make_path($retained);
    my %package_file = (
        report => 'package-evidence-report.json', jar => 'release.jar',
        sbom => 'release-sbom.json', deb => 'release.deb',
        java_bom => 'bom.json', perl_bom => 'perl-bom.json',
        notice => 'notice-license.json',
    );
    write_file(File::Spec->catfile($retained, $package_file{report}), "{}\n");
    write_file(File::Spec->catfile($retained, $package_file{jar}), read_file($jar));
    write_file(File::Spec->catfile($retained, $package_file{sbom}), read_file($sbom));
    write_file(File::Spec->catfile($retained, $package_file{deb}), "deb\n");
    write_file(File::Spec->catfile($retained, $package_file{java_bom}), "{}\n");
    write_file(File::Spec->catfile($retained, $package_file{perl_bom}), "{}\n");
    write_file(File::Spec->catfile($retained, $package_file{notice}), "{}\n");
    my $package_source = $change{package_source} // $source_commit;
    my $package_jar_sha = $change{package_jar_sha} // hash_file($jar);
    my $package_document = {
        schema_version => 1, kind => 'packaging',
        producer => 'run_phase36_package_evidence.pl', verified => JSON::PP::true,
        identity => { source_commit => $package_source,
            jar_sha256 => $package_jar_sha, sbom_sha256 => hash_file($sbom) },
        completion => green_completion(),
        artifacts => {
            report => relative_descriptor($retained, $package_file{report}),
            deliverables => {
                jar => relative_descriptor($retained, $package_file{jar}),
                sbom => relative_descriptor($retained, $package_file{sbom}),
                deb => relative_descriptor($retained, $package_file{deb}),
            },
            sbom_inputs => {
                java_bom => relative_descriptor($retained, $package_file{java_bom}),
                perl_bom => relative_descriptor($retained, $package_file{perl_bom}),
            },
            logs => {},
            notice_license => relative_descriptor($retained, $package_file{notice}),
        },
        missing_entries => 0, duplicate_entries => 0,
    };
    if ($change{package_report}) {
        $package_document->{kind} = 'phase36-package-evidence-report';
        $package_document->{authoritative} = JSON::PP::false;
        $package_document->{mode} = 'report';
    }
    my $package_path = File::Spec->catfile($directory, 'package-evidence.json');
    write_json($package_path, $package_document);
    write_file(File::Spec->catfile($retained, $package_file{jar}), "tampered\n")
        if $change{tamper_package_artifact};

    my $make_source = $change{make_source} // $source_commit;
    my $make_path = File::Spec->catfile($directory, 'make-evidence.json');
    my %sidecar;
    for my $name (qw(jar_embedded jar_version make_log source_after
            source_before tool_versions)) {
        $sidecar{$name} = File::Spec->catfile($directory, "make-$name.dat");
        write_file($sidecar{$name}, "$name evidence\n");
    }
    my $file_descriptor = descriptor($jar);
    my $tool_descriptor = { %$file_descriptor,
        version_sha256 => sha256_hex('version') };
    my $state = {
        all_status_sha256 => sha256_hex('all'), diff_sha256 => sha256_hex('diff'),
        status_sha256 => sha256_hex('status'), head => $make_source,
        tracked_clean => JSON::PP::true,
        extras => { authority_inputs => [], generated_file_count => 0,
            generated_paths => [], generated_total_bytes => 0 },
    };
    my $make_document = {
        schema => 'perlonjava.phase36.make-evidence/v1', schema_version => 1,
        kind => 'make', producer => 'run_phase36_make_evidence.pl',
        mode => $change{make_mode} // 'acceptance', status => 'pass',
        verified => JSON::PP::true,
        authoritative => exists($change{make_authoritative})
            ? ($change{make_authoritative} ? JSON::PP::true : JSON::PP::false)
            : JSON::PP::true,
        identity => { source_commit => $make_source, runner_commit => $make_source,
            jar_sha256 => hash_file($jar), jar_reported_commit => $make_source,
            jar_embedded_commit => $make_source },
        source => { root => $source, before => {%$state}, after => {%$state} },
        command => { argv => [$jar], cwd => $source, environment => {},
            started_utc => '2026-08-23T00:00:00Z',
            finished_utc => '2026-08-23T00:00:01Z', duration_milliseconds => 1 },
        tools => {
            (map { $_ => {%$tool_descriptor} }
                qw(git jar_tool java make perl shell)),
            producer => {%$file_descriptor},
        },
        inputs => { map { $_ => {%$file_descriptor} } qw(build_gradle
            gradle_wrapper_jar gradle_wrapper_properties gradlew makefile
            settings_gradle) },
        completion => { %{green_completion()}, truncated => JSON::PP::false },
        warning_scan => empty_scan(), failure_scan => empty_scan(),
        artifacts => {
            jar => descriptor($jar),
            map { $_ => descriptor($sidecar{$_}) } keys %sidecar,
        },
    };
    seal_make($make_document, $make_path);
    if ($change{tamper_make_json}) {
        my $bytes = read_file($make_path);
        $bytes =~ s/("duration_milliseconds"\s*:\s*)1/${1}2/;
        write_file($make_path, $bytes);
    }
    return { package => $package_path, make => $make_path };
}

sub invoke {
    my ($name, $evidence, %arg) = @_;
    my $artifacts = File::Spec->catdir($temporary, "artifacts-$name");
    make_path($artifacts);
    my @command = (absolute_perl(), $producer,
        '--baseline', $baseline, '--artifact-dir', $artifacts,
        '--jar', ($arg{jar} // $jar), '--sbom', $sbom,
        '--source-dir', $source, '--perl5-dir', $source, '--jperl', $jperl,
        '--ledger-tool', $ledger, '--runner-tool', $runner,
        '--comparator-tool', $comparator, '--packaging-tool', $packaging);
    unless ($arg{omit_evidence}) {
        push @command, '--package-evidence', ($arg{package} // $evidence->{package})
            unless $arg{omit_package};
        push @command, '--make-evidence', ($arg{make} // $evidence->{make})
            unless $arg{omit_make};
    }
    return capture(@command);
}

sub green_completion {
    return { exit_code => 0, signal => 0, timeout => JSON::PP::false,
        incomplete => JSON::PP::false, review_stop => JSON::PP::false };
}

sub empty_scan {
    return { classifier => 'fixture', classifier_sha256 => sha256_hex('fixture'),
        complete_log_sha256 => sha256_hex('log'), count => 0, matches => [] };
}

sub relative_descriptor {
    my ($directory, $name) = @_;
    my $descriptor = descriptor(File::Spec->catfile($directory, $name));
    $descriptor->{path} = "package/$name";
    return $descriptor;
}

sub descriptor {
    my ($path) = @_;
    return { path => $path, sha256 => hash_file($path), size => 0 + (-s $path) };
}

sub seal_make {
    my ($document, $path) = @_;
    my $payload_sha = sha256_hex(JSON::PP->new->canonical->utf8->encode($document));
    $document->{seal} = { algorithm => 'SHA-256', payload_sha256 => $payload_sha };
    write_json($path, $document);
    my $bytes = read_file($path);
    write_file("$path.seal", "SHA-256 $payload_sha " . sha256_hex($bytes) . "\n");
}

sub fake_tool {
    my ($name, $contents) = @_;
    my $path = File::Spec->catfile($temporary, $name);
    write_file($path, $contents);
    return $path;
}

sub absolute_perl {
    return abs_path($^X) if File::Spec->file_name_is_absolute($^X);
    for my $directory (File::Spec->path) {
        my $path = File::Spec->catfile($directory, $^X);
        return abs_path($path) if -x $path;
    }
    die "Cannot resolve system Perl";
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

sub hash_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh or die "Cannot close $path: $!";
    return $sha->hexdigest;
}

sub write_json {
    my ($path, $document) = @_;
    write_file($path, JSON::PP->new->utf8->canonical->pretty->encode($document));
}

sub read_json {
    return JSON::PP->new->utf8->decode(read_file($_[0]));
}

sub write_file {
    my ($path, $contents) = @_;
    make_path(dirname($path));
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
