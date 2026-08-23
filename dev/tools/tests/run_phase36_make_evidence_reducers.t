use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use JSON::PP;
use IO::Compress::Zip qw($ZipError);
use Test::More;
use Time::HiRes qw(sleep time);

my $repo = abs_path(File::Spec->catdir(dirname(__FILE__), '..', '..', '..'));
my $producer_source = File::Spec->catfile($repo, 'dev', 'tools',
    'run_phase36_make_evidence.pl');
my $perl = File::Spec->file_name_is_absolute($^X)
    ? abs_path($^X) : command_path($^X);
my $git = command_path('git');

subtest 'short-lived gradlew version capture terminates promptly' => sub {
    my $f = capture_fixture('make_fail');
    my $started = time();
    my $r = invoke($f);
    my $elapsed = time() - $started;
    isnt($r->{exit}, 0, 'intentional fake make failure is retained');
    like($r->{stderr}, qr/Make exited with status 9/,
        'producer reached make after all bounded version captures');
    cmp_ok($elapsed, '<', 10, 'short-lived captures and cleanup terminate promptly');
    is(read_file($f->{count}), "1\n", 'fake make build invoked exactly once');
    ok(!-e $f->{output}, 'no authoritative JSON from failing make');
};

subtest 'ignored canonical wrappers are authority inputs and extras reject' => sub {
    my $f = capture_fixture('green', ignored_wrappers => 1);
    my $r = invoke($f);
    diag($r->{stderr}) if $r->{exit};
    is($r->{exit}, 0, 'explicit canonical ignored wrapper inputs are accepted');
    my $d = decode_json(read_file($f->{output}));
    is_deeply($d->{source}{before}{extras}{authority_inputs},
        [qw(gradle/wrapper/gradle-wrapper.jar
            gradle/wrapper/gradle-wrapper.properties gradlew)],
        'only exact ignored authority inputs are retained');
    is_deeply($d->{command}{argv}, [$f->{make}], 'accepted make argv is exact');

    $f = capture_fixture('ignored_extra', ignored_wrappers => 1);
    $r = invoke($f);
    isnt($r->{exit}, 0, 'unapproved ignored path rejects');
    like($r->{stderr}, qr/Unapproved untracked or ignored source path: rogue\.log/,
        'ignored-path rejection is explicit');
    no_authority($f);
};

subtest 'successful report is permanently non-authoritative' => sub {
    my $f = capture_fixture('green', ignored_wrappers => 1);
    $f->{output} .= '.report.json';
    my $r = invoke($f, mode => 'report');
    diag($r->{stderr}) if $r->{exit};
    is($r->{exit}, 0, 'successful report completes');
    my $d = decode_json(read_file($f->{output}));
    is($d->{schema}, 'perlonjava.phase36.make-evidence-report/v1',
        'report schema differs from acceptance');
    is($d->{kind}, 'make-report', 'report kind is structurally distinct');
    ok(!$d->{verified} && !$d->{authoritative},
        'successful report cannot assert verification or authority');
    ok(!consumer_accepts(read_file($f->{output}), ''),
        'strict A241-like reducer rejects report shape');
};

subtest 'trusted archive operation authenticates embedded JAR commit' => sub {
    my $f = capture_fixture('zip_green');
    my $r = invoke($f);
    diag($r->{stderr}) if $r->{exit};
    is($r->{exit}, 0, 'ZIP/JAR fixture succeeds');
    my $d = decode_json(read_file($f->{output}));
    my $embedded = decode_json(read_file($d->{artifacts}{jar_embedded}{path}));
    is($embedded->{method}, 'trusted-unzip-configuration-class',
        'trusted extraction operation, not runtime output, authenticated contents');
    is($embedded->{resolved_commit}, $f->{commit}, 'full commit cross-check retained');
    is($embedded->{archive_tool}{sha256}, $d->{tools}{jar_tool}{sha256},
        'trusted archive executable hash is bound');

    $f = capture_fixture('zip_wrong');
    $r = invoke($f);
    isnt($r->{exit}, 0, 'tampered archive entry rejects');
    like($r->{stderr}, qr/embedded JAR contents has no unique/,
        'independent archive-content mismatch is explicit');
    no_authority($f);
};

for my $case (
    [make_inherited_descendant => qr/retained or closed output|incomplete/,
        'make descendant retaining pipe'],
    [make_closed_descendant => qr/retained or closed output|incomplete/,
        'make descendant closing pipe'],
    [capture_inherited_descendant => qr/process tree was incomplete/,
        'version descendant retaining pipe'],
    [capture_closed_descendant => qr/process tree was incomplete/,
        'version descendant closing pipe'],
    [capture_oversized => qr/exceeded bounded size/,
        'version output on-write bound'],
) {
    my ($scenario, $pattern, $label) = @$case;
    subtest $label => sub {
        my $f = capture_fixture($scenario);
        my $r = invoke($f);
        isnt($r->{exit}, 0, 'process tree/output reducer rejects');
        like($r->{stderr}, $pattern, 'bounded failure is explicit');
        no_authority($f);
        sleep 0.1;
        ok(!-e $f->{descendant_marker}, 'descendant tree was cleaned');
    };
}

for my $case (
    [producer_mutation => qr/producer mutated/, 'producer late mutation'],
    [input_mutation => qr/gradlew input mutated/, 'authority input late mutation'],
    [embedded_wrong => qr/embedded JAR contents has no unique/,
        'embedded JAR tamper'],
    [embedded_conflict => qr/embedded JAR contents contains conflicting/,
        'embedded JAR conflicting identities'],
) {
    my ($scenario, $pattern, $label) = @$case;
    subtest $label => sub {
        my $f = capture_fixture($scenario);
        my $r = invoke($f);
        isnt($r->{exit}, 0, 'late mutation/authentication reducer rejects');
        like($r->{stderr}, $pattern, 'specific fail-closed reason retained');
        no_authority($f);
    };
}

subtest 'CLI is exact, case-sensitive, non-abbreviating, and duplicate-free' => sub {
    for my $extra (
        ['--source-r', '/tmp/x'], ['--Source-root', '/tmp/x'],
        ['--unknown-phase36', 'x'], ['--mode', 'acceptance'],
    ) {
        my $f = capture_fixture('make_fail');
        my $r = invoke($f, extra => $extra);
        isnt($r->{exit}, 0, "$extra->[0] rejected before semantics");
        like($r->{stderr}, qr/Unknown option|Duplicate option/,
            'exact CLI diagnostic retained');
        ok(!-e $f->{count}, 'make never launched');
    }
};

subtest 'interrupted publication never exposes authoritative JSON' => sub {
    my $f = capture_fixture('green');
    my $r = invoke($f, interrupt_publication => 1);
    isnt($r->{exit}, 0, 'publication interruption rejects');
    like($r->{stderr}, qr/interrupted/i, 'signal interruption retained');
    no_authority($f);
    ok(!grep { -e $_ || -l $_ } sidecars($f),
        'signal cleanup removed staged publication sidecars');
};

subtest 'recursive extra and duplicate JSON fields reject after resealing' => sub {
    my $f = capture_fixture('green');
    my $r = invoke($f);
    diag($r->{stderr}) if $r->{exit};
    is($r->{exit}, 0, 'baseline acceptance exists');
    my $bytes = read_file($f->{output});
    my $external = read_file("$f->{output}.seal");
    ok(consumer_accepts($bytes, $external), 'strict consumer accepts baseline');
    my $d = decode_json($bytes);
    $d->{source}{before}{extras}{recursive_extra} = 1;
    reseal($d);
    my $extra = JSON::PP->new->canonical->pretty->utf8->encode($d);
    ok(!consumer_accepts($extra, external_seal($d, $extra)),
        'recursively nested extra field rejects even when resealed');
    (my $duplicate = $bytes) =~ s/\{/{\n   "kind" : "make",/;
    ok(!consumer_accepts($duplicate, $external), 'duplicate JSON key rejects');
};

done_testing();

sub capture_fixture {
    my ($scenario, %arg) = @_;
    my $tmp = abs_path(tempdir('phase36-capture-reducer-XXXXXX',
        TMPDIR => 1, CLEANUP => 1));
    my $source = File::Spec->catdir($tmp, 'source');
    my $tools = File::Spec->catdir($tmp, 'tools');
    make_path($tools, File::Spec->catdir($source, 'dev', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'target'));
    my $producer = File::Spec->catfile($source, 'dev', 'tools',
        'run_phase36_make_evidence.pl');
    write_file($producer, read_file($producer_source)); chmod 0755, $producer;
    my $ignore = "target/\nbuild/\n.gradle/\n*.log\n";
    $ignore .= "gradlew\ngradle/wrapper/gradle-wrapper.jar\n"
        . "gradle/wrapper/gradle-wrapper.properties\n" if $arg{ignored_wrappers};
    write_file(File::Spec->catfile($source, '.gitignore'), $ignore);
    write_file(File::Spec->catfile($source, 'Makefile'), "all:\n\t\@false\n");
    write_file(File::Spec->catfile($source, 'build.gradle'), "// fake\n");
    write_file(File::Spec->catfile($source, 'settings.gradle'), "// fake\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.jar'), "fake wrapper\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.properties'), "distributionUrl=file:fake.zip\n");
    my $gradlew = File::Spec->catfile($source, 'gradlew');
    write_file($gradlew, script(<<'GRADLEW'));
die "unexpected gradlew argv\n"
    unless @ARGV == 1 && $ARGV[0] eq '--version';
print "Gradle reducer 1.0\n";
exit 0;
GRADLEW
    chmod 0755, $gradlew;
    my $count = File::Spec->catfile($tmp, 'make-count');
    my $descendant_marker = File::Spec->catfile($tmp, 'descendant-survived');
    my $jar = File::Spec->catfile($source, 'target', 'fake.jar');
    my $jar_template = File::Spec->catfile($tmp, 'jar-template.zip');
    my $make = File::Spec->catfile($tools, 'make');
    write_file($make, script(qq{
use File::Basename qw(dirname);
if (\@ARGV == 1 && \$ARGV[0] eq '--version') {
    print "GNU Make reducer 1.0\\n"; exit 0;
}
die "unexpected make argv\\n" if \@ARGV;
open my \$fh, '>>', '$count' or die \$!;
print {\$fh} "1\\n"; close \$fh;
exit 9 if '$scenario' eq 'make_fail';
my \$commit = qx{'$git' -C '$source' rev-parse HEAD}; chomp \$commit;
if ('$scenario' eq 'zip_green' || '$scenario' eq 'zip_wrong') {
    open my \$in, '<:raw', '$jar_template' or die \$!;
    open my \$out, '>:raw', '$jar' or die \$!;
    while (read(\$in, my \$chunk, 65536)) { print {\$out} \$chunk or die \$! }
    close \$in; close \$out or die \$!;
} else {
my \$embedded = '$scenario' eq 'embedded_wrong' ? ('1' x 40)
    : '$scenario' eq 'embedded_conflict' ? "\$commit\\n" . ('1' x 40)
    : \$commit;
open my \$jar_fh, '>', '$jar' or die \$!;
print {\$jar_fh} "phase36 fake jar\\n\$embedded\\n"; close \$jar_fh;
}
if ('$scenario' eq 'make_inherited_descendant'
        || '$scenario' eq 'make_closed_descendant') {
    my \$child = fork(); die \$! unless defined \$child;
    if (!\$child) {
        if ('$scenario' eq 'make_closed_descendant') {
            close STDOUT; close STDERR;
        }
        sleep 3;
        open my \$marker, '>', '$descendant_marker' or die \$!;
        print {\$marker} "survived\\n"; close \$marker; exit 0;
    }
}
print "BUILD SUCCESSFUL\\n";
exit 0;
}));
    chmod 0755, $make;
    my $shell = File::Spec->catfile($tools, 'shell');
    write_file($shell, script(qq{
die "unexpected shell argv\\n"
    unless \@ARGV == 1 && \$ARGV[0] eq '--version';
if ('$scenario' eq 'capture_inherited_descendant'
        || '$scenario' eq 'capture_closed_descendant') {
    my \$child = fork(); die \$! unless defined \$child;
    if (!\$child) {
        if ('$scenario' eq 'capture_closed_descendant') {
            close STDOUT; close STDERR;
        }
        sleep 3;
        open my \$marker, '>', '$descendant_marker' or die \$!;
        print {\$marker} "survived\\n"; close \$marker; exit 0;
    }
}
print "x" x (2 * 1024 * 1024) if '$scenario' eq 'capture_oversized';
print "shell reducer 1.0\\n"; exit 0;
}));
    chmod 0755, $shell;
    my $java = File::Spec->catfile($tools, 'java');
    write_file($java, script(qq{
if (\@ARGV == 1 && \$ARGV[0] eq '-version') {
    print "java reducer 24\\n"; exit 0;
}
die "unexpected java argv\\n"
    unless \@ARGV == 3 && \$ARGV[0] eq '-jar' && \$ARGV[2] eq '-v';
my \$commit = qx{'$git' -C '$source' rev-parse HEAD}; chomp \$commit;
print "PerlOnJava commit \$commit\\n";
if ('$scenario' eq 'producer_mutation') {
    open my \$m, '>>', '$producer' or die \$!; print {\$m} "# late\\n"; close \$m;
}
if ('$scenario' eq 'input_mutation') {
    open my \$m, '>>', '$gradlew' or die \$!; print {\$m} "# late\\n"; close \$m;
}
exit 0;
}));
    chmod 0755, $java;
    run_ok([$git, 'init', '-q', $source]);
    run_ok([$git, '-C', $source, 'config', 'user.name', 'Phase36 Reducer']);
    run_ok([$git, '-C', $source, 'config', 'user.email',
        'phase36-reducer@invalid.example']);
    run_ok([$git, '-C', $source, 'add', '-A']);
    run_ok([$git, '-C', $source, 'commit', '-q', '-m', 'capture reducer']);
    my $commit = capture([$git, '-C', $source, 'rev-parse', 'HEAD']);
    chomp $commit;
    write_file(File::Spec->catfile($source, 'rogue.log'), "ignored rogue\n")
        if $scenario eq 'ignored_extra';
    my $embedded = $scenario eq 'embedded_wrong' ? ('1' x 40)
        : $scenario eq 'embedded_conflict' ? "$commit\n" . ('1' x 40)
        : $scenario eq 'zip_wrong' ? ('1' x 40)
        : $commit;
    my $jar_bytes;
    if ($scenario eq 'zip_green' || $scenario eq 'zip_wrong') {
        my $zip = IO::Compress::Zip->new($jar_template,
            Name => 'org/perlonjava/core/Configuration.class', Time => 0)
            or die "zip fixture: $ZipError";
        print {$zip} "PerlOnJava embedded commit $embedded\n";
        close $zip or die "close zip fixture: $ZipError";
        $jar_bytes = read_file($jar_template);
    } else {
        $jar_bytes = "phase36 fake jar\n$embedded\n";
    }
    return { tmp => $tmp, source => $source, producer => $producer,
        make => $make, shell => $shell, java => $java, commit => $commit,
        count => $count, output => File::Spec->catfile($tmp, 'evidence.json'),
        jar => $jar, jar_sha => sha256_hex($jar_bytes),
        descendant_marker => $descendant_marker };
}

sub invoke {
    my ($f, %arg) = @_;
    my @cmd = ($perl, $f->{producer}, '--source-root', $f->{source},
        '--expected-source-commit', $f->{commit}, '--expected-runner-commit',
        $f->{commit}, '--expected-jar', $f->{jar}, '--expected-jar-sha256',
        $f->{jar_sha}, '--output', $f->{output}, '--perl', $perl,
        '--git', $git, '--make', $f->{make}, '--shell', $f->{shell}, '--java',
        $f->{java}, '--timeout', '3', '--mode', ($arg{mode} // 'acceptance'));
    push @cmd, @{$arg{extra}} if $arg{extra};
    return run_capture(\@cmd, $f->{tmp},
        interrupt_output => $arg{interrupt_publication} ? $f->{output} : undef);
}

sub run_capture {
    my ($cmd, $tmp, %arg) = @_;
    my $stdout = File::Spec->catfile($tmp, 'stdout');
    my $stderr = File::Spec->catfile($tmp, 'stderr');
    my $pid = fork(); die $! unless defined $pid;
    if ($pid == 0) {
        open STDOUT, '>', $stdout or die $!;
        open STDERR, '>', $stderr or die $!;
        exec {$cmd->[0]} @$cmd; die $!;
    }
    my $status;
    if ($arg{interrupt_output}) {
        my $deadline = time() + 10;
        while (time() < $deadline) {
            my $waited = waitpid($pid, 1);
            if ($waited == $pid) { $status = $?; last }
            if (-e "$arg{interrupt_output}.seal" && !-e $arg{interrupt_output}) {
                kill 'TERM', $pid; last;
            }
            select undef, undef, undef, 0.005;
        }
    }
    unless (defined $status) { waitpid($pid, 0); $status = $? }
    return { exit => ($status & 127) ? 128 + ($status & 127) : $status >> 8,
        stdout => -e $stdout ? read_file($stdout) : '',
        stderr => -e $stderr ? read_file($stderr) : '' };
}

sub no_authority {
    my ($f) = @_;
    ok(!-e $f->{output}, 'no authoritative JSON');
}

sub sidecars {
    my ($f) = @_;
    return map { "$f->{output}.$_" } qw(make.log source-before.json
        source-after.json tool-versions.json jar-version.log jar-embedded.json seal);
}

sub exact_keys {
    my ($hash, @wanted) = @_;
    return 0 unless ref($hash) eq 'HASH';
    return join("\0", sort keys %$hash) eq join("\0", sort @wanted);
}

sub consumer_accepts {
    my ($bytes, $external) = @_;
    my $d = eval { decode_json($bytes) } or return 0;
    return 0 unless JSON::PP->new->canonical->pretty->utf8->encode($d) eq $bytes;
    return 0 unless exact_keys($d, qw(artifacts authoritative command completion
        failure_scan identity inputs kind mode producer schema schema_version seal
        source status tools verified warning_scan));
    return 0 unless $d->{schema} eq 'perlonjava.phase36.make-evidence/v1'
        && $d->{kind} eq 'make' && $d->{mode} eq 'acceptance'
        && $d->{verified} && $d->{authoritative};
    return 0 unless exact_keys($d->{identity}, qw(jar_embedded_commit
        jar_reported_commit jar_sha256 runner_commit source_commit));
    return 0 unless exact_keys($d->{source}, qw(after before root));
    for my $when (qw(before after)) {
        return 0 unless exact_keys($d->{source}{$when}, qw(all_status_sha256
            diff_sha256 extras head status_sha256 tracked_clean));
        return 0 unless exact_keys($d->{source}{$when}{extras},
            qw(authority_inputs generated_file_count generated_paths
                generated_total_bytes));
    }
    return 0 unless exact_keys($d->{command}, qw(argv cwd duration_milliseconds
        environment finished_utc started_utc));
    return 0 unless exact_keys($d->{tools}, qw(git jar_tool java make perl
        producer shell));
    return 0 unless exact_keys($d->{inputs}, qw(build_gradle gradle_wrapper_jar
        gradle_wrapper_properties gradlew makefile settings_gradle));
    return 0 unless exact_keys($d->{completion}, qw(exit_code incomplete
        review_stop signal timeout truncated));
    return 0 unless exact_keys($d->{artifacts}, qw(jar jar_embedded jar_version
        make_log source_after source_before tool_versions));
    return 0 unless exact_keys($d->{seal}, qw(algorithm payload_sha256));
    my %payload = %$d; delete $payload{seal};
    return 0 unless $d->{seal}{payload_sha256} eq sha256_hex(canonical(\%payload));
    return $external eq external_seal($d, $bytes);
}

sub reseal {
    my ($d) = @_;
    delete $d->{seal};
    $d->{seal} = { algorithm => 'SHA-256',
        payload_sha256 => sha256_hex(canonical($d)) };
}

sub external_seal {
    my ($d, $bytes) = @_;
    return "SHA-256 $d->{seal}{payload_sha256} " . sha256_hex($bytes) . "\n";
}

sub canonical { return JSON::PP->new->canonical->utf8->encode($_[0]) }

sub script { return "#!$perl\nuse strict; use warnings;\n$_[0]" }
sub read_file {
    open my $fh, '<:raw', $_[0] or die "read $_[0]: $!";
    local $/; my $bytes = <$fh>; close $fh; return $bytes;
}
sub write_file {
    make_path(dirname($_[0]));
    open my $fh, '>:raw', $_[0] or die "write $_[0]: $!";
    print {$fh} $_[1]; close $fh or die $!;
}
sub run_ok { system @{$_[0]}; die "command failed: @{$_[0]}" if $? }
sub capture {
    my ($cmd) = @_;
    open my $fh, '-|', @$cmd or die $!;
    local $/; my $bytes = <$fh>; close $fh or die "capture failed";
    return $bytes;
}
sub command_path {
    my ($name) = @_;
    for my $dir (File::Spec->path) {
        my $path = File::Spec->catfile($dir, $name);
        return abs_path($path) if -x $path;
    }
    die "Cannot find $name";
}
