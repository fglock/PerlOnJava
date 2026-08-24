use strict;
use warnings;

use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IO::Compress::Zip qw($ZipError);
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'verify-joni-distribution.pl');
my $build_gradle = File::Spec->catfile($root, 'build.gradle');
my $temporary = tempdir(CLEANUP => 1);

subtest 'relocated standalone distribution is accepted' => sub {
    my $distribution = fixture('green');
    my ($status, $output) = run_tool($distribution);
    is($status, 0, 'green distribution verifies');
    like($output, qr/relocation verification passed/, 'success is explicit');
};

subtest 'runtime classpath is fail-closed' => sub {
    rejected(fixture('missing-jar', missing_jar => 1),
        qr/exactly one runtime JAR \(found 0\)/, 'missing runtime JAR');
    rejected(fixture('extra-jar', extra_jar => 1),
        qr/exactly one runtime JAR \(found 2\)/, 'additional runtime JAR');
    rejected(fixture('unrelocated-joni', unrelocated_joni => 1),
        qr/unrelocated Joni classes/, 'unrelocated Joni');
    rejected(fixture('unrelocated-jcodings', unrelocated_jcodings => 1),
        qr/unrelocated JCodings classes/, 'unrelocated JCodings');
    rejected(fixture('missing-relocated-joni', missing_relocated_joni => 1),
        qr/does not contain relocated Joni classes/, 'missing relocated Joni');
    rejected(fixture('missing-relocated-jcodings', missing_relocated_jcodings => 1),
        qr/does not contain relocated JCodings classes/, 'missing relocated JCodings');
    rejected(fixture('duplicate-class', duplicate_class => 1),
        qr/duplicate class entry/, 'duplicate relocated class');
};

subtest 'installed notices are fail-closed and byte-exact' => sub {
    rejected(fixture('missing-notice', missing_notice => 1),
        qr/missing joni-LICENSE/, 'missing notice');
    rejected(fixture('mutated-notice', mutated_notice => 1),
        qr/notice bytes differ for joni-LICENSE/, 'mutated notice');
};

subtest 'launch scripts cannot restore a thin dependency classpath' => sub {
    rejected(fixture('extra-launch-jar', extra_launch_jar => 1),
        qr/launcher CLASSPATH must select only perlonjava-5\.44\.1\.jar/,
        'additional launcher classpath entry');
    rejected(fixture('wrong-launch-jar', wrong_launch_jar => 1),
        qr/launcher CLASSPATH must select only perlonjava-5\.44\.1\.jar/,
        'launcher missing standalone artifact');
};

subtest 'production fork packaging verification is explicitly strict' => sub {
    my $build = read_file($build_gradle);
    like($build, qr{
        tasks\.register\('verifyJoniPackaging',\s*Exec\).*?
        commandLine\s+'perl',\s*'dev/regex/tools/verify-joni-packaging\.pl',\s*
        '--strict',\s*"target/perlonjava-\$\{project\.version\}\.jar",
    }sx, 'Gradle packaging verification passes the explicit strict flag');
    my @strict_flags = $build =~ /'--strict'/g;
    is(scalar @strict_flags, 1,
        'strict mode is limited to the production fork packaging verifier');
};

done_testing;

sub fixture {
    my ($name, %option) = @_;
    my $distribution = File::Spec->catdir($temporary, $name);
    my $lib = File::Spec->catdir($distribution, 'lib');
    my $bin = File::Spec->catdir($distribution, 'bin');
    my $licenses = File::Spec->catdir($distribution, 'share', 'licenses');
    make_path($lib, $bin, $licenses);

    my $jar_name = 'perlonjava-5.44.1.jar';
    unless ($option{missing_jar}) {
        my $tree = File::Spec->catdir($temporary, "$name-jar");
        make_path(
            File::Spec->catdir($tree, 'org', 'perlonjava', 'internal', 'joni'),
            File::Spec->catdir($tree, 'org', 'perlonjava', 'internal', 'jcodings'),
        );
        write_file(File::Spec->catfile(
            $tree, 'org', 'perlonjava', 'internal', 'joni', 'Regex.class'), 'joni')
            unless $option{missing_relocated_joni};
        write_file(File::Spec->catfile(
            $tree, 'org', 'perlonjava', 'internal', 'jcodings', 'Encoding.class'),
            'jcodings') unless $option{missing_relocated_jcodings};
        if ($option{unrelocated_joni}) {
            make_path(File::Spec->catdir($tree, 'org', 'joni'));
            write_file(File::Spec->catfile($tree, 'org', 'joni', 'Regex.class'), 'bad');
        }
        if ($option{unrelocated_jcodings}) {
            make_path(File::Spec->catdir($tree, 'org', 'jcodings'));
            write_file(File::Spec->catfile(
                $tree, 'org', 'jcodings', 'Encoding.class'), 'bad');
        }
        my $jar = File::Spec->catfile($lib, $jar_name);
        create_jar($jar, $tree,
            $option{duplicate_class}
                ? 'org/perlonjava/internal/joni/Regex.class' : undef);
        write_file(File::Spec->catfile($lib, 'extra.jar'), 'extra')
            if $option{extra_jar};
    }

    my %notice_sources = (
        'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
        'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile(
            $root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
        'jcodings-LICENSE.txt' => File::Spec->catfile(
            $root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
    );
    for my $notice (sort keys %notice_sources) {
        next if $option{missing_notice} && $notice eq 'joni-LICENSE.txt';
        my $bytes = read_file($notice_sources{$notice});
        $bytes .= "mutated\n"
            if $option{mutated_notice} && $notice eq 'joni-LICENSE.txt';
        write_file(File::Spec->catfile($licenses, $notice), $bytes);
    }

    my $selected = $option{wrong_launch_jar} ? 'thin.jar' : $jar_name;
    my $extra_unix = $option{extra_launch_jar}
        ? ':$APP_HOME/lib/jcodings-1.0.64.jar' : '';
    my $extra_windows = $option{extra_launch_jar}
        ? ';%APP_HOME%\\lib\\jcodings-1.0.64.jar' : '';
    write_file(File::Spec->catfile($bin, 'perlonjava'),
        "CLASSPATH=\$APP_HOME/lib/$selected$extra_unix\n"
        . "set -- \\\n        -classpath \"\$CLASSPATH\" \\\n"
        . "        org.perlonjava.app.cli.Main \\\n        \"\$@\"\n"
        . "exec \"\$JAVACMD\" \"\$@\"\n");
    write_file(File::Spec->catfile($bin, 'perlonjava.bat'),
        "set CLASSPATH=%APP_HOME%\\lib\\$selected$extra_windows\r\n"
        . "endlocal & \"%JAVA_EXE%\" -classpath \"%CLASSPATH%\" "
        . "org.perlonjava.app.cli.Main %*\r\n");
    return $distribution;
}

sub create_jar {
    my ($jar, $tree, $duplicate) = @_;
    my @names;
    find({ no_chdir => 1, wanted => sub {
        return unless -f $_;
        my $name = File::Spec->abs2rel($_, $tree);
        $name =~ s{\\}{/}g;
        push @names, $name;
    }}, $tree);
    @names = sort @names;
    push @names, $duplicate if defined $duplicate;
    my $first = shift @names;
    my $zip = IO::Compress::Zip->new($jar, Name => $first)
        or die "Cannot create fixture JAR: $ZipError";
    print {$zip} read_file(File::Spec->catfile($tree, split m{/}, $first));
    for my $name (@names) {
        $zip->newStream(Name => $name)
            or die "Cannot add fixture JAR entry: $ZipError";
        print {$zip} read_file(File::Spec->catfile($tree, split m{/}, $name));
    }
    close $zip or die "Cannot close fixture JAR: $ZipError";
}

sub rejected {
    my ($distribution, $expected, $name) = @_;
    my ($status, $output) = run_tool($distribution);
    isnt($status, 0, "$name is rejected");
    like($output, $expected, "$name has a specific diagnostic");
}

sub run_tool {
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $^X } $^X, $tool, $_[0];
        die "exec: $!";
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub write_file {
    my ($file, $contents) = @_;
    open my $fh, '>:raw', $file or die "Cannot write $file: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $file: $!";
}

sub read_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!";
    return $contents;
}
