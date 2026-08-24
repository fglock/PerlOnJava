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
my $original = File::Spec->catfile($repo, 'dev', 'regex', 'tools',
    'run_make_evidence.pl');
my $perl = File::Spec->file_name_is_absolute($^X)
    ? abs_path($^X) : command_path($^X);
my $git = command_path('git');

subtest 'green fake make publishes sealed authoritative evidence' => sub {
    my $f = fixture('green');
    my $r = invoke($f);
    diag($r->{stderr}) if $r->{exit};
    is($r->{exit}, 0, 'producer succeeds');
    is($r->{stdout}, "$f->{output}\n", 'canonical output reported');
    is(read_file($f->{count}), "1\n", 'exact build make ran once');
    my $d = decode_json(read_file($f->{output}));
    is_deeply([sort keys %$d], [sort qw(artifacts authoritative command
        completion failure_scan identity inputs kind mode producer schema
        schema_version seal source status tools verified warning_scan)],
        'top-level schema is exact');
    is($d->{schema}, 'perlonjava.regex_implementation.make-evidence/v1', 'schema retained');
    is($d->{kind}, 'make', 'kind is make');
    is($d->{producer}, 'run_make_evidence.pl', 'producer is inert metadata');
    ok($d->{verified} && $d->{authoritative}, 'record is authoritative');
    is_deeply($d->{command}{argv}, [$f->{make}], 'exact argv retained');
    is($d->{command}{cwd}, $f->{source}, 'exact cwd retained');
    is($d->{identity}{source_commit}, $f->{commit}, 'source commit retained');
    is($d->{identity}{runner_commit}, $f->{commit}, 'runner commit retained');
    is($d->{identity}{jar_reported_commit}, $f->{commit}, 'JAR commit expanded');
    is($d->{identity}{jar_sha256}, $f->{jar_sha}, 'JAR hash bound');
    is_deeply($d->{completion}, { exit_code => 0, signal => 0,
        timeout => JSON::PP::false, incomplete => JSON::PP::false,
        truncated => JSON::PP::false, review_stop => JSON::PP::false },
        'completion schema is exact');
    is($d->{warning_scan}{count}, 0, 'warnings derived as zero');
    is($d->{failure_scan}{count}, 0, 'failures derived as zero');
    my %payload = %$d; delete $payload{seal};
    is($d->{seal}{payload_sha256}, sha256_hex(canonical(\%payload)),
        'canonical payload seal verifies');
    for my $name (qw(make_log source_before source_after tool_versions jar jar_version)) {
        my $a = $d->{artifacts}{$name};
        ok(-f $a->{path} && !-l $a->{path}, "$name is durable");
        is(sha256_file($a->{path}), $a->{sha256}, "$name hash verifies");
        is(-s $a->{path}, $a->{size}, "$name size verifies");
    }
    ok(-f "$f->{output}.seal", 'external seal exists');
    my $log = read_file($d->{artifacts}{make_log}{path});
    is(() = $log =~ /^BUILD SUCCESSFUL\b/mg, 1, 'single completion retained');
    is(count_key(read_file($f->{output}), 'kind'), 1, 'no duplicate kind key');
};

subtest 'TAP warning-shaped diagnostics are not classifier authority' => sub {
    my $f = fixture('tap');
    my $r = invoke($f);
    is($r->{exit}, 0, 'TAP diagnostics do not block acceptance');
    ok(-f $f->{output}, 'acceptance published');
};

for my $case (
    [dirty => qr/dirty before/, 'dirty source'],
    [mutate_source => qr/became dirty/, 'source mutation'],
    [mutate_head => qr/HEAD changed/, 'HEAD mutation'],
    [nonzero => qr/status 7/, 'nonzero exit'],
    [signal => qr/signal/, 'signal'],
    [timeout => qr/timed out/, 'timeout'],
    [oversized_log => qr/bounded size/, 'oversized/truncated log'],
    [no_completion => qr/exactly one BUILD SUCCESSFUL/, 'missing completion'],
    [duplicate_completion => qr/exactly one BUILD SUCCESSFUL/, 'duplicate completion'],
    [build_failed => qr/failure output|BUILD FAILED/, 'failure marker'],
    [success_nonzero => qr/status 7/, 'success text with failing status'],
    [stale_jar => qr/JAR is stale/, 'stale JAR'],
    [missing_jar => qr/not freshly produced/, 'missing JAR'],
    [wrong_commit => qr/no unique|conflicting commit/, 'wrong JAR commit'],
    [jar_late_mutation => qr/JAR mutated/, 'late JAR mutation'],
    [tool_late_mutation => qr/make executable mutated/, 'late tool mutation'],
) {
    my ($scenario, $pattern, $label) = @$case;
    subtest $label => sub {
        my $f = fixture($scenario);
        my $r = invoke($f, timeout => $scenario eq 'timeout' ? 1 : 5);
        isnt($r->{exit}, 0, 'producer rejects fixture');
        like($r->{stderr}, $pattern, 'specific failure reported');
        no_acceptance($f);
    };
}

for my $warning (
    [java_warning => 'warning: unchecked conversion'],
    [gradle_warning => 'WARNING: deprecated feature'],
    [uninitialized => 'Use of uninitialized value $x at lib/Foo.pm line 12.'],
    [numeric => q{Argument "x" isn't numeric in addition at lib/Foo.pm line 13.}],
    [interpolation => 'Possible unintended interpolation of @x in string at x.pl line 2.'],
    [wide => 'Wide character in print at x.pl line 3.'],
    [redefined => 'Subroutine Foo::bar redefined at x.pl line 4.'],
    [at_line => 'mysterious diagnostic at x.pl line 5'],
) {
    my $line = pop @$warning;
    my $name = shift @$warning;
    subtest "warning family: $name" => sub {
        my $f = fixture($name, warning => $line);
        my $r = invoke($f);
        isnt($r->{exit}, 0, 'warning blocks acceptance');
        like($r->{stderr}, qr/warning output/i, 'raw scan decides');
        no_acceptance($f);
    };
}

subtest 'stale expected source fails before launch' => sub {
    my $f = fixture('green');
    $f->{commit} = '0' x 40;
    my $r = invoke($f);
    isnt($r->{exit}, 0, 'stale expected source rejected');
    like($r->{stderr}, qr/trusted source commit/, 'staleness diagnosed');
    ok(!-e $f->{count}, 'make never launched');
    no_acceptance($f);
};

subtest 'effective source build tools must be regular tracked bounded inputs' => sub {
    for my $variant (qw(missing symlink untracked oversized)) {
        my $f = fixture('green', gradlew_variant => $variant);
        my $r = invoke($f);
        isnt($r->{exit}, 0, "$variant gradlew rejected");
        ok(!-e $f->{count}, 'make not launched');
        no_acceptance($f);
    }
};

subtest 'alternate authority, duplicate options, and malformed numerics reject' => sub {
    for my $extra (
        ['--log', '/tmp/caller.log'], ['--summary', 'passed'],
        ['--environment', '{}'], ['--path', '/bin'],
    ) {
        my $f = fixture('green');
        my $r = invoke($f, extra => $extra);
        isnt($r->{exit}, 0, "$extra->[0] rejected");
        like($r->{stderr}, qr/forbidden|Unknown option/i, 'authority rejection explicit');
        ok(!-e $f->{count}, 'make not launched');
    }
    my $f = fixture('green');
    my $r = invoke($f, extra => ['--timeout', '2']);
    isnt($r->{exit}, 0, 'duplicate option rejected');
    like($r->{stderr}, qr/Duplicate option/, 'duplicate diagnosed');
    for my $bad ('0', '-1', '01', '1.5', '999999999999999999999') {
        $f = fixture('green');
        $r = invoke($f, timeout => $bad);
        isnt($r->{exit}, 0, "bad timeout $bad rejected");
        ok(!-e $f->{count}, 'make not launched');
    }
};

subtest 'path escape, symlink source, and publication collision fail closed' => sub {
    my $f = fixture('green');
    my $r = invoke($f, jar => File::Spec->catfile($f->{tmp}, 'escape.jar'));
    isnt($r->{exit}, 0, 'JAR escape rejected');
    no_acceptance($f);

    $f = fixture('green');
    my $alias = File::Spec->catfile($f->{tmp}, 'source-link');
    symlink $f->{source}, $alias or die $!;
    $r = invoke($f, source => $alias);
    isnt($r->{exit}, 0, 'symlink source rejected');
    no_acceptance($f);

    $f = fixture('green');
    write_file($f->{output}, "occupied\n");
    $r = invoke($f);
    isnt($r->{exit}, 0, 'output collision rejected');
    is(read_file($f->{output}), "occupied\n", 'collision untouched');

    $f = fixture('green');
    write_file("$f->{output}.make.log", "partial\n");
    $r = invoke($f);
    isnt($r->{exit}, 0, 'partial publication collision rejected');
    ok(!-e $f->{output}, 'JSON not published');
};

subtest 'report mode failure is bounded and non-authoritative' => sub {
    my $f = fixture('java_warning', warning => 'warning: report fixture');
    $f->{output} .= '.report.json';
    my $r = invoke($f, mode => 'report');
    isnt($r->{exit}, 0, 'report preserves failure');
    my $d = decode_json(read_file($f->{output}));
    is($d->{kind}, 'make-report', 'distinct kind');
    ok(!$d->{verified} && !$d->{authoritative}, 'explicitly non-authoritative');
    cmp_ok(-s $f->{output}, '<=', 8192, 'report bounded');
    ok(!-e "$f->{output}.seal", 'no acceptance seal');
};

done_testing();

sub fixture {
    my ($scenario, %arg) = @_;
    my $tmp = abs_path(tempdir('regex_implementation-make-XXXXXX', TMPDIR => 1, CLEANUP => 1));
    my $source = File::Spec->catdir($tmp, 'source');
    my $tools = File::Spec->catdir($tmp, 'tools');
    make_path($tools, File::Spec->catdir($source, 'dev', 'regex', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'target'));
    my $producer = File::Spec->catfile($source, 'dev', 'regex', 'tools',
        'run_make_evidence.pl');
    write_file($producer, read_file($original)); chmod 0755, $producer;
    write_file(File::Spec->catfile($source, '.gitignore'), "target/\n");
    write_file(File::Spec->catfile($source, '.regex_implementation-fixture'),
        canonical({scenario => $scenario, warning => ($arg{warning} // '')}));
    write_file(File::Spec->catfile($source, 'tracked.txt'), "stable\n");
    write_file(File::Spec->catfile($source, 'Makefile'), "all:\n\t\@true\n");
    write_file(File::Spec->catfile($source, 'build.gradle'), "// fake\n");
    write_file(File::Spec->catfile($source, 'settings.gradle'), "// fake\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.jar'), "fake wrapper\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.properties'), "distributionUrl=file:fake.zip\n");
    my $gradlew = File::Spec->catfile($source, 'gradlew');
    write_file($gradlew, script(<<'GRADLEW'));
if (@ARGV == 1 && $ARGV[0] eq '--version') {
    print "Gradle 9.1 fake\n"; exit 0;
}
die "unexpected gradlew argv\n";
GRADLEW
    chmod 0755, $gradlew;

    my $count = File::Spec->catfile($tmp, 'make-count');
    my $make = File::Spec->catfile($tools, 'make');
    write_file($make, script(fake_make_body($count, $git)));
    chmod 0755, $make;
    my $shell = File::Spec->catfile($tools, 'shell');
    write_file($shell, script("print qq{fake shell 1.0\\n};\n")); chmod 0755, $shell;
    my $java = File::Spec->catfile($tools, 'java');
    write_file($java, script(fake_java_body($make))); chmod 0755, $java;

    run_ok([$git, 'init', '-q', $source]);
    run_ok([$git, '-C', $source, 'config', 'user.name', 'RegexImplementation Test']);
    run_ok([$git, '-C', $source, 'config', 'user.email', 'regex_implementation@invalid.example']);
    if (($arg{gradlew_variant} // '') eq 'untracked') { unlink $gradlew or die $! }
    run_ok([$git, '-C', $source, 'add', '-A']);
    run_ok([$git, '-C', $source, 'commit', '-q', '-m', 'fixture parent']);
    write_file(File::Spec->catfile($source, 'parent.txt'), "parent\n");
    run_ok([$git, '-C', $source, 'add', 'parent.txt']);
    run_ok([$git, '-C', $source, 'commit', '-q', '-m', 'fixture head']);
    if (($arg{gradlew_variant} // '') eq 'untracked') {
        write_file($gradlew, script("print qq{Gradle 9.1 fake\\n};\n")); chmod 0755, $gradlew;
    } elsif (($arg{gradlew_variant} // '') eq 'missing') {
        unlink $gradlew or die $!;
    } elsif (($arg{gradlew_variant} // '') eq 'symlink') {
        unlink $gradlew or die $!; symlink $shell, $gradlew or die $!;
    } elsif (($arg{gradlew_variant} // '') eq 'oversized') {
        open my $fh, '>>', $gradlew or die $!;
        truncate $fh, 65 * 1024 * 1024 or die $!; close $fh;
    }
    my $commit = capture([$git, '-C', $source, 'rev-parse', 'HEAD']); chomp $commit;
    if ($scenario eq 'dirty') {
        open my $dirty, '>>', File::Spec->catfile($source, 'tracked.txt') or die $!;
        print {$dirty} "dirty\n"; close $dirty;
    }
    my $jar = File::Spec->catfile($source, 'target', 'fake.jar');
    my $jar_bytes = "regex_implementation fake jar\n$commit\n";
    write_file($jar, $jar_bytes) if $scenario eq 'stale_jar';
    return {tmp => $tmp, source => $source, producer => $producer, make => $make,
        shell => $shell, java => $java, git => $git, perl => $perl,
        commit => $commit, jar => $jar, jar_sha => sha256_hex($jar_bytes),
        output => File::Spec->catfile($tmp, 'evidence.json'), count => $count};
}

sub fake_make_body {
    my ($count, $git_path) = @_;
    return <<"MAKE";
use JSON::PP;
if (\@ARGV == 1 && \$ARGV[0] eq '--version') {
    print "GNU Make 4.4 fake\\n"; exit 0;
}
die "wrong make argv\\n" if \@ARGV;
die "wrong make cwd\\n" unless -f '.regex_implementation-fixture' && -d '.git';
my \@required = qw(GIT_CONFIG_GLOBAL GIT_CONFIG_NOSYSTEM GRADLE_USER_HOME HOME
 JAVA_HOME LANG LC_ALL MAKEFLAGS MFLAGS GNUMAKEFLAGS PATH SHELL
 SOURCE_DATE_EPOCH TMPDIR TZ);
die "wrong make environment\\n"
    unless join("\\n", sort keys %ENV) eq join("\\n", sort \@required);
open my \$cf, '>>', '$count' or die \$!; print {\$cf} "1\\n"; close \$cf;
open my \$sf, '<', '.regex_implementation-fixture' or die \$!;
my \$cfg = JSON::PP->new->decode(do { local \$/; <\$sf> }); close \$sf;
my \$s = \$cfg->{scenario};
sleep 3 if \$s eq 'timeout';
if (\$s eq 'signal') { kill 9, \$\$; sleep 1 }
if (\$s eq 'mutate_source') {
    open my \$m, '>>', 'tracked.txt' or die \$!; print {\$m} "changed\\n"; close \$m;
}
if (\$s eq 'mutate_head') {
    system('$git_path', 'checkout', '--detach', 'HEAD^') == 0 or die "head mutation failed";
}
my \$commit = qx{'$git_path' rev-parse HEAD}; chomp \$commit;
unless (\$s eq 'missing_jar' || \$s eq 'stale_jar') {
    open my \$j, '>', 'target/fake.jar' or die \$!;
    print {\$j} "regex_implementation fake jar\\n\$commit\\n"; close \$j;
}
print \$cfg->{warning}, "\\n" if length \$cfg->{warning};
if (\$s eq 'tap') {
    print "# warning: TAP diagnostic only at tap.t line 9\\n";
    print "not ok 4 - WARNING: TAP assertion text at tap.t line 10\\n";
}
print "BUILD FAILED\\n" if \$s eq 'build_failed';
print "x" x (33 * 1024 * 1024) if \$s eq 'oversized_log';
print "BUILD SUCCESSFUL\\n" unless \$s eq 'no_completion' || \$s eq 'build_failed';
print "BUILD SUCCESSFUL\\n" if \$s eq 'duplicate_completion';
exit 7 if \$s eq 'nonzero' || \$s eq 'success_nonzero';
MAKE
}

sub fake_java_body {
    my ($make) = @_;
    return <<"JAVA";
use File::Basename qw(dirname);
use File::Spec;
use JSON::PP;
if (\@ARGV == 1 && \$ARGV[0] eq '-version') { print "fake java 24\\n"; exit 0 }
die "wrong java argv\\n"
    unless \@ARGV == 3 && \$ARGV[0] eq '-jar' && \$ARGV[2] eq '-v';
open my \$j, '<', \$ARGV[1] or die \$!; my \$bytes = do { local \$/; <\$j> }; close \$j;
my (\$commit) = \$bytes =~ /([0-9a-f]{40})/;
my \$control = File::Spec->catfile(dirname(dirname(\$ARGV[1])), '.regex_implementation-fixture');
open my \$sf, '<', \$control or die \$!;
my \$cfg = JSON::PP->new->decode(do { local \$/; <\$sf> }); close \$sf;
\$commit = '1' x 40 if \$cfg->{scenario} eq 'wrong_commit';
print "PerlOnJava commit \$commit\\n";
if (\$cfg->{scenario} eq 'jar_late_mutation') {
    open my \$m, '>>', \$ARGV[1] or die \$!; print {\$m} "late\\n"; close \$m;
}
if (\$cfg->{scenario} eq 'tool_late_mutation') {
    open my \$m, '>>', '$make' or die \$!; print {\$m} "# late\\n"; close \$m;
}
JAVA
}

sub invoke {
    my ($f, %arg) = @_;
    my @cmd = ($f->{perl}, $f->{producer},
        '--source-root', ($arg{source} // $f->{source}),
        '--expected-source-commit', $f->{commit},
        '--expected-runner-commit', $f->{commit},
        '--expected-jar', ($arg{jar} // $f->{jar}),
        '--expected-jar-sha256', $f->{jar_sha}, '--output', $f->{output},
        '--perl', $f->{perl}, '--git', $f->{git}, '--make', $f->{make},
        '--shell', $f->{shell}, '--java', $f->{java},
        '--timeout', (defined $arg{timeout} ? $arg{timeout} : 5),
        '--mode', ($arg{mode} // 'acceptance'));
    push @cmd, @{$arg{extra}} if $arg{extra};
    return run_capture(\@cmd, $f->{tmp});
}

sub run_capture {
    my ($cmd, $tmp) = @_;
    my $out = File::Spec->catfile($tmp, 'stdout-' . int(rand 1_000_000));
    my $err = File::Spec->catfile($tmp, 'stderr-' . int(rand 1_000_000));
    my $pid = fork(); die $! unless defined $pid;
    if ($pid == 0) {
        open STDOUT, '>', $out or die $!; open STDERR, '>', $err or die $!;
        exec {$cmd->[0]} @$cmd; die $!;
    }
    waitpid($pid, 0); my $status = $?;
    return {exit => ($status & 127) ? 128 + ($status & 127) : $status >> 8,
        stdout => -e $out ? read_file($out) : '',
        stderr => -e $err ? read_file($err) : ''};
}

sub no_acceptance {
    my ($f) = @_;
    ok(!-e $f->{output}, 'no acceptance JSON');
    ok(!-e "$f->{output}.seal", 'no acceptance seal');
}
sub script { return "#!$perl\nuse strict; use warnings;\n$_[0]" }
sub canonical { JSON::PP->new->canonical->utf8->encode($_[0]) }
sub count_key { my ($x, $k) = @_; return scalar(() = $x =~ /"\Q$k\E"\s*:/g) }
sub sha256_file { sha256_hex(read_file($_[0])) }
sub read_file {
    open my $fh, '<:raw', $_[0] or die "read $_[0]: $!";
    local $/; my $x = <$fh>; close $fh; return $x;
}
sub write_file {
    make_path(dirname($_[0])); open my $fh, '>:raw', $_[0] or die "write: $!";
    print {$fh} $_[1]; close $fh or die $!;
}
sub run_ok { system @{$_[0]}; die "command failed: @$_" if $? }
sub capture {
    my ($cmd) = @_; open my $fh, '-|', @$cmd or die $!;
    local $/; my $x = <$fh>; close $fh or die "capture failed"; return $x;
}
sub command_path {
    my ($name) = @_;
    for my $dir (File::Spec->path) {
        my $path = File::Spec->catfile($dir, $name);
        return abs_path($path) if -x $path;
    }
    die "Cannot find $name";
}
