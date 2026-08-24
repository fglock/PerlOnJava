use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use JSON::PP;
use Test::More;

my $repo = abs_path(File::Spec->catdir(dirname(__FILE__), '..', '..', '..', '..'));
my $producer_source = File::Spec->catfile($repo, 'dev', 'regex', 'tools',
    'run_make_evidence.pl');
my $perl = File::Spec->file_name_is_absolute($^X)
    ? abs_path($^X) : command_path($^X);
my $git = command_path('git');

subtest 'authority race collision is exclusive and preserves competitor' => sub {
    my $f = fixture();
    my $r = invoke($f);
    isnt($r->{exit}, 0, 'authority publication collision rejects');
    like($r->{stderr}, qr/Cannot exclusively publish authoritative JSON.*(?:exists|exist)/i,
        'kernel no-replace collision is explicit');
    is(read_file($f->{output}), "collision sentinel\n",
        'racing destination is not overwritten or removed');
    ok(!eval { decode_json(read_file($f->{output})); 1 },
        'collision sentinel is not an acceptance JSON document');
    is(scalar(grep { -e $_ || -l $_ } sidecars($f)), 0,
        'all producer sidecars are rolled back');
    is(scalar(stage_paths($f)), 0, 'all producer staging paths are removed');
    is(read_file($f->{count}), "1\n", 'make ran exactly once before publication race');
};

done_testing();

sub fixture {
    my $tmp = abs_path(tempdir('regex_implementation-authority-collision-XXXXXX',
        TMPDIR => 1, CLEANUP => 1));
    my $source = File::Spec->catdir($tmp, 'source');
    my $tools = File::Spec->catdir($tmp, 'tools');
    make_path($tools, File::Spec->catdir($source, 'dev', 'regex', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'target'));
    my $producer = File::Spec->catfile($source, 'dev', 'regex', 'tools',
        'run_make_evidence.pl');
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
print "Gradle collision 1.0\n";
exit 0;
GRADLEW
    chmod 0755, $gradlew;

    my $count = File::Spec->catfile($tmp, 'make-count');
    my $jar = File::Spec->catfile($source, 'target', 'fake.jar');
    my $make = File::Spec->catfile($tools, 'make');
    write_file($make, script(qq{
if (\@ARGV == 1 && \$ARGV[0] eq '--version') {
    print "GNU Make collision 1.0\\n"; exit 0;
}
die "unexpected make argv\\n" if \@ARGV;
open my \$count_fh, '>', '$count' or die \$!;
print {\$count_fh} "1\\n"; close \$count_fh;
my \$commit = qx{'$git' -C '$source' rev-parse HEAD}; chomp \$commit;
open my \$jar_fh, '>', '$jar' or die \$!;
print {\$jar_fh} "regex_implementation collision jar\\n\$commit\\n"; close \$jar_fh;
print "BUILD SUCCESSFUL\\n";
exit 0;
}));
    chmod 0755, $make;
    my $shell = File::Spec->catfile($tools, 'shell');
    write_file($shell, script("print qq{shell collision 1.0\\n}; exit 0;\n"));
    chmod 0755, $shell;
    my $java = File::Spec->catfile($tools, 'java');
    write_file($java, script(qq{
if (\@ARGV == 1 && \$ARGV[0] eq '-version') {
    print "java collision 24\\n"; exit 0;
}
die "unexpected java argv\\n"
    unless \@ARGV == 3 && \$ARGV[0] eq '-jar' && \$ARGV[2] eq '-v';
my \$commit = qx{'$git' -C '$source' rev-parse HEAD}; chomp \$commit;
print "PerlOnJava commit \$commit\\n";
exit 0;
}));
    chmod 0755, $java;

    run_ok([$git, 'init', '-q', $source]);
    run_ok([$git, '-C', $source, 'config', 'user.name', 'RegexImplementation Collision']);
    run_ok([$git, '-C', $source, 'config', 'user.email',
        'regex_implementation-collision@invalid.example']);
    run_ok([$git, '-C', $source, 'add', '-A']);
    run_ok([$git, '-C', $source, 'commit', '-q', '-m', 'collision fixture']);
    my $commit = capture([$git, '-C', $source, 'rev-parse', 'HEAD']);
    chomp $commit;
    my $jar_bytes = "regex_implementation collision jar\n$commit\n";
    return { tmp => $tmp, source => $source, producer => $producer,
        make => $make, shell => $shell, java => $java, commit => $commit,
        count => $count, jar => $jar, jar_sha => sha256_hex($jar_bytes),
        output => File::Spec->catfile($tmp, 'evidence.json') };
}

sub invoke {
    my ($f) = @_;
    my @cmd = ($perl, $f->{producer}, '--source-root', $f->{source},
        '--expected-source-commit', $f->{commit}, '--expected-runner-commit',
        $f->{commit}, '--expected-jar', $f->{jar}, '--expected-jar-sha256',
        $f->{jar_sha}, '--output', $f->{output}, '--perl', $perl, '--git', $git,
        '--make', $f->{make}, '--shell', $f->{shell}, '--java', $f->{java},
        '--timeout', '3', '--mode', 'acceptance');
    my $stdout = File::Spec->catfile($f->{tmp}, 'stdout');
    my $stderr = File::Spec->catfile($f->{tmp}, 'stderr');
    my $pid = fork(); die $! unless defined $pid;
    if ($pid == 0) {
        $ENV{REGEX_IMPLEMENTATION_MAKE_EVIDENCE_FAILPOINT} =
            'collision-at-authority-publication';
        open STDOUT, '>', $stdout or die $!;
        open STDERR, '>', $stderr or die $!;
        exec {$cmd[0]} @cmd; die $!;
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
    my @path = grep { /^evidence\.json\.stage-/ } readdir $dh;
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
