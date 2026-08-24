use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(basename dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $repo = abs_path(File::Spec->catdir(dirname(__FILE__), '..', '..', '..'));
my $producer_source = File::Spec->catfile($repo, 'dev', 'tools',
    'run_phase36_make_evidence.pl');
my $perl = File::Spec->file_name_is_absolute($^X)
    ? abs_path($^X) : command_path($^X);
my $git = command_path('git');

subtest 'durable success publishes JSON only after complete sidecars and seal' => sub {
    my $f = fixture();
    my $r = invoke($f);
    diag($r->{stderr}) if $r->{exit};
    is($r->{exit}, 0, 'durable producer succeeds');
    is($r->{stdout}, "$f->{output}\n", 'authoritative JSON path reported');
    ok(-f $f->{output} && !-l $f->{output}, 'authoritative JSON exists');
    for my $path (sidecars($f)) {
        ok(-f $path && !-l $path, "durable sidecar exists: " . basename($path));
    }
    is(read_file($f->{count}), "1\n", 'make invoked exactly once');
};

subtest 'directory sync and post-authority failpoints roll back authority' => sub {
    for my $case (
        ['fail-sidecar-directory-sync', qr/sidecar directory-sync failure/,
            'sidecar directory fsync error'],
        ['after-sidecar-directory-sync', qr/after-sidecar-directory-sync/,
            'failure after durable sidecars'],
        ['fail-authority-directory-sync', qr/authority directory-sync failure/,
            'authority directory fsync error'],
        ['after-authority-directory-sync', qr/after-authority-directory-sync/,
            'failure after durable authority name'],
        ['signal-after-authority-link', qr/interrupted by TERM/,
            'signal after authority link'],
    ) {
        my ($failpoint, $pattern, $label) = @$case;
        my $f = fixture();
        my $r = invoke($f, failpoint => $failpoint);
        isnt($r->{exit}, 0, "$label rejects");
        like($r->{stderr}, $pattern, "$label is explicit");
        ok(!-e $f->{output} && !-l $f->{output},
            "$label leaves no authoritative JSON");
        is(scalar(grep { -e $_ || -l $_ } sidecars($f)), 0,
            "$label removes every published sidecar");
        is(scalar(grep { -e $_ || -l $_ } stage_paths($f)), 0,
            "$label removes every staging path");
    }
};

done_testing();

sub fixture {
    my $tmp = abs_path(tempdir('phase36-durability-XXXXXX',
        TMPDIR => 1, CLEANUP => 1));
    my $source = File::Spec->catdir($tmp, 'source');
    my $tools = File::Spec->catdir($tmp, 'tools');
    make_path($tools, File::Spec->catdir($source, 'dev', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'target'));
    my $producer = File::Spec->catfile($source, 'dev', 'tools',
        'run_phase36_make_evidence.pl');
    write_file($producer, read_file($producer_source)); chmod 0755, $producer;
    write_file(File::Spec->catfile($source, '.gitignore'), "target/\nbuild/\n.gradle/\n");
    write_file(File::Spec->catfile($source, 'Makefile'), "all:\n\t\@true\n");
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
print "Gradle durability 1.0\n";
exit 0;
GRADLEW
    chmod 0755, $gradlew;

    my $count = File::Spec->catfile($tmp, 'make-count');
    my $jar = File::Spec->catfile($source, 'target', 'fake.jar');
    my $make = File::Spec->catfile($tools, 'make');
    write_file($make, script(qq{
if (\@ARGV == 1 && \$ARGV[0] eq '--version') {
    print "GNU Make durability 1.0\\n"; exit 0;
}
die "unexpected make argv\\n" if \@ARGV;
open my \$count_fh, '>', '$count' or die \$!;
print {\$count_fh} "1\\n"; close \$count_fh;
my \$commit = qx{'$git' -C '$source' rev-parse HEAD}; chomp \$commit;
open my \$jar_fh, '>', '$jar' or die \$!;
print {\$jar_fh} "phase36 durability jar\\n\$commit\\n"; close \$jar_fh;
print "BUILD SUCCESSFUL\\n";
exit 0;
}));
    chmod 0755, $make;
    my $shell = File::Spec->catfile($tools, 'shell');
    write_file($shell, script("print qq{shell durability 1.0\\n}; exit 0;\n"));
    chmod 0755, $shell;
    my $java = File::Spec->catfile($tools, 'java');
    write_file($java, script(qq{
if (\@ARGV == 1 && \$ARGV[0] eq '-version') {
    print "java durability 24\\n"; exit 0;
}
die "unexpected java argv\\n"
    unless \@ARGV == 3 && \$ARGV[0] eq '-jar' && \$ARGV[2] eq '-v';
my \$commit = qx{'$git' -C '$source' rev-parse HEAD}; chomp \$commit;
print "PerlOnJava commit \$commit\\n";
exit 0;
}));
    chmod 0755, $java;

    run_ok([$git, 'init', '-q', $source]);
    run_ok([$git, '-C', $source, 'config', 'user.name', 'Phase36 Durability']);
    run_ok([$git, '-C', $source, 'config', 'user.email',
        'phase36-durability@invalid.example']);
    run_ok([$git, '-C', $source, 'add', '-A']);
    run_ok([$git, '-C', $source, 'commit', '-q', '-m', 'durability fixture']);
    my $commit = capture([$git, '-C', $source, 'rev-parse', 'HEAD']);
    chomp $commit;
    my $jar_bytes = "phase36 durability jar\n$commit\n";
    return { tmp => $tmp, source => $source, producer => $producer,
        make => $make, shell => $shell, java => $java, commit => $commit,
        count => $count, jar => $jar, jar_sha => sha256_hex($jar_bytes),
        output => File::Spec->catfile($tmp, 'evidence.json') };
}

sub invoke {
    my ($f, %arg) = @_;
    my @cmd = ($perl, $f->{producer}, '--source-root', $f->{source},
        '--expected-source-commit', $f->{commit}, '--expected-runner-commit',
        $f->{commit}, '--expected-jar', $f->{jar}, '--expected-jar-sha256',
        $f->{jar_sha}, '--output', $f->{output}, '--perl', $perl, '--git', $git,
        '--make', $f->{make}, '--shell', $f->{shell}, '--java', $f->{java},
        '--timeout', '3', '--mode', 'acceptance');
    return run_capture(\@cmd, $f->{tmp}, $arg{failpoint});
}

sub run_capture {
    my ($cmd, $tmp, $failpoint) = @_;
    my $stdout = File::Spec->catfile($tmp, 'stdout');
    my $stderr = File::Spec->catfile($tmp, 'stderr');
    my $pid = fork(); die $! unless defined $pid;
    if ($pid == 0) {
        if (defined $failpoint) {
            $ENV{PHASE36_MAKE_EVIDENCE_FAILPOINT} = $failpoint;
        } else {
            delete $ENV{PHASE36_MAKE_EVIDENCE_FAILPOINT};
        }
        open STDOUT, '>', $stdout or die $!;
        open STDERR, '>', $stderr or die $!;
        exec {$cmd->[0]} @$cmd; die $!;
    }
    waitpid($pid, 0); my $status = $?;
    return { exit => ($status & 127) ? 128 + ($status & 127) : $status >> 8,
        stdout => -e $stdout ? read_file($stdout) : '',
        stderr => -e $stderr ? read_file($stderr) : '' };
}

sub sidecars {
    my ($f) = @_;
    return map { "$f->{output}.$_" } qw(make.log source-before.json
        source-after.json tool-versions.json jar-version.log jar-embedded.json seal);
}

sub stage_paths {
    my ($f) = @_;
    opendir my $dh, $f->{tmp} or die $!;
    my @path = map { File::Spec->catfile($f->{tmp}, $_) }
        grep { /^evidence\.json\.stage-/ } readdir $dh;
    closedir $dh;
    return @path;
}

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
