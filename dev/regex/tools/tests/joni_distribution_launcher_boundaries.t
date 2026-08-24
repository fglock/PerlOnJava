use strict;
use warnings;

use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IO::Compress::Zip qw($ZipError);
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'verify-joni-distribution.pl');
my $temporary = tempdir(CLEANUP => 1);
my $jar_name = 'perlonjava-5.44.0.jar';

subtest 'generated Unix and Windows launcher forms are accepted' => sub {
    my $distribution = fixture('valid');
    my ($status, $output) = run_tool($distribution);
    is($status, 0, 'valid launcher pair verifies');
    like($output, qr/relocation verification passed/, 'success is explicit');

    $distribution = fixture('quoted-windows',
        windows_assignment => qq{set "CLASSPATH=%APP_HOME%\\lib\\$jar_name"\r\n});
    ($status, $output) = run_tool($distribution);
    is($status, 0, 'quoted Windows set syntax verifies');
};

subtest 'JAR inventory is case-insensitive' => sub {
    rejected(fixture('uppercase-extra', extra_jar => 'EXTRA.JAR'),
        qr/exactly one runtime JAR \(found 2\)/, 'uppercase extra JAR');
    rejected(fixture('mixed-case-extra', extra_jar => 'extra.JaR'),
        qr/exactly one runtime JAR \(found 2\)/, 'mixed-case extra JAR');
};

subtest 'comments and unrelated Unix text cannot spoof the classpath' => sub {
    my $decoy = qq{CLASSPATH=\$APP_HOME/lib/thin.jar\n}
        . qq{# CLASSPATH=\$APP_HOME/lib/$jar_name\n}
        . unix_command();
    rejected(fixture('unix-comment-decoy', unix => $decoy),
        qr/Unix launcher CLASSPATH must select only/, 'Unix comment decoy');

    $decoy = qq{CLASSPATH=\$APP_HOME/lib/thin.jar\n}
        . qq{echo \$APP_HOME/lib/$jar_name\n}
        . unix_command();
    rejected(fixture('unix-text-decoy', unix => $decoy),
        qr/Unix launcher CLASSPATH must select only/, 'Unix unrelated text decoy');
};

subtest 'comments and unrelated Windows text cannot spoof the classpath' => sub {
    my $decoy = "set CLASSPATH=%APP_HOME%\\lib\\thin.jar\r\n"
        . "rem set CLASSPATH=%APP_HOME%\\lib\\$jar_name\r\n"
        . windows_command();
    rejected(fixture('windows-comment-decoy', windows => $decoy),
        qr/Windows launcher CLASSPATH must select only/, 'Windows comment decoy');

    $decoy = "set CLASSPATH=%APP_HOME%\\lib\\thin.jar\r\n"
        . "echo %APP_HOME%\\lib\\$jar_name\r\n"
        . windows_command();
    rejected(fixture('windows-text-decoy', windows => $decoy),
        qr/Windows launcher CLASSPATH must select only/, 'Windows unrelated text decoy');
};

subtest 'ambiguous and indirect classpaths fail closed' => sub {
    rejected(fixture('unix-multiple', unix => unix_assignment()
            . qq{CLASSPATH=\$APP_HOME/lib/$jar_name\n} . unix_command()),
        qr/exactly one static CLASSPATH assignment \(found 2\)/,
        'multiple Unix assignments');
    rejected(fixture('unix-wildcard', unix => qq{CLASSPATH=\$APP_HOME/lib/*\n}
            . unix_command()),
        qr/Unix launcher CLASSPATH must select only/, 'Unix wildcard');
    rejected(fixture('unix-missing-command', unix => unix_assignment()
            . qq{# -classpath "\$CLASSPATH" \\\nexec "\$JAVACMD" "\$@"\n}),
        qr/exactly one connected CLASSPATH argv block \(found 0\)/,
        'commented Unix command');
    rejected(fixture('windows-multiple', windows => windows_assignment()
            . windows_assignment() . windows_command()),
        qr/exactly one CLASSPATH assignment \(found 2\)/,
        'multiple Windows assignments');
    rejected(fixture('windows-directory',
            windows => "set CLASSPATH=%APP_HOME%\\lib\r\n" . windows_command()),
        qr/Windows launcher CLASSPATH must select only/, 'Windows directory');
    rejected(fixture('windows-missing-command',
            windows => windows_assignment()
                . "rem endlocal & \"%JAVA_EXE%\" -classpath \"%CLASSPATH%\" "
                . "org.perlonjava.app.cli.Main %*\r\n"),
        qr/exactly one effective CLASSPATH command \(found 0\)/,
        'commented Windows command');
};

subtest 'loose unrelocated classes remain fail-closed' => sub {
    rejected(fixture('loose-unrelocated', loose_unrelocated => 1),
        qr/unrelocated loose class .*org\/joni\/Regex\.class/,
        'loose unrelocated Joni class');
};

subtest 'checked launcher commands are the sole effective invocations' => sub {
    my $unix_escape = unix_assignment()
        . qq{set -- \\\n        -classpath "\$CLASSPATH" \\\n}
        . qq{        org.perlonjava.app.cli.Main \\\n        "\$@"\n}
        . qq{set -- -classpath "\$APP_HOME/../outside.jar" }
        . qq{org.perlonjava.app.cli.Main "\$@"\n}
        . qq{exec "\$JAVACMD" "\$@"\n};
    rejected(fixture('unix-later-argv-reset', unix => $unix_escape),
        qr/(?:extra or disconnected classpath command|resets argv)/,
        'later Unix argv reset selecting an outside JAR');

    my $windows_spoof = windows_assignment() . windows_command()
        . "\"%JAVA_EXE%\" -classpath \"%APP_HOME%\\..\\outside.jar\" "
        . "org.perlonjava.app.cli.Main %*\r\n";
    rejected(fixture('windows-second-java-command', windows => $windows_spoof),
        qr/(?:extra or disconnected classpath command|extra Java invocation)/,
        'second Windows Java invocation selecting an outside JAR');

    my $unix_reset = unix_assignment()
        . qq{set -- \\\n        -classpath "\$CLASSPATH" \\\n}
        . qq{        org.perlonjava.app.cli.Main \\\n        "\$@"\n}
        . qq{set -- --version "\$@"\nexec "\$JAVACMD" "\$@"\n};
    rejected(fixture('unix-plain-argv-reset', unix => $unix_reset),
        qr/resets argv after the checked classpath block/,
        'later Unix argv reset without another classpath token');

    my $windows_extra_java = windows_assignment()
        . "call \"%JAVA_EXE%\" -version\r\n" . windows_command();
    rejected(fixture('windows-extra-java', windows => $windows_extra_java),
        qr/extra Java invocation/,
        'additional Windows Java command without another classpath token');

    my $unix_preblock_java = unix_assignment()
        . qq{"\$JAVACMD" -jar "\$APP_HOME/../outside.jar"\n}
        . unix_command();
    rejected(fixture('unix-preblock-java', unix => $unix_preblock_java),
        qr/extra Java invocation/,
        'pre-block Unix Java invocation selecting an outside JAR');
};

done_testing;

sub fixture {
    my ($name, %option) = @_;
    my $distribution = File::Spec->catdir($temporary, $name);
    my $lib = File::Spec->catdir($distribution, 'lib');
    my $bin = File::Spec->catdir($distribution, 'bin');
    my $licenses = File::Spec->catdir($distribution, 'share', 'licenses');
    make_path($lib, $bin, $licenses);

    my $tree = File::Spec->catdir($temporary, "$name-jar");
    make_path(
        File::Spec->catdir($tree, 'org', 'perlonjava', 'internal', 'joni'),
        File::Spec->catdir($tree, 'org', 'perlonjava', 'internal', 'jcodings'),
    );
    write_file(File::Spec->catfile(
        $tree, 'org', 'perlonjava', 'internal', 'joni', 'Regex.class'), 'joni');
    write_file(File::Spec->catfile(
        $tree, 'org', 'perlonjava', 'internal', 'jcodings', 'Encoding.class'), 'jcodings');
    create_jar(File::Spec->catfile($lib, $jar_name), $tree);
    write_file(File::Spec->catfile($lib, $option{extra_jar}), 'extra')
        if $option{extra_jar};
    if ($option{loose_unrelocated}) {
        my $loose = File::Spec->catdir($distribution, 'org', 'joni');
        make_path($loose);
        write_file(File::Spec->catfile($loose, 'Regex.class'), 'unrelocated');
    }

    my %notices = (
        'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
        'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile(
            $root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
        'jcodings-LICENSE.txt' => File::Spec->catfile(
            $root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
    );
    for my $notice (keys %notices) {
        write_file(File::Spec->catfile($licenses, $notice), read_file($notices{$notice}));
    }

    write_file(File::Spec->catfile($bin, 'perlonjava'),
        exists $option{unix} ? $option{unix} : unix_assignment() . unix_command());
    write_file(File::Spec->catfile($bin, 'perlonjava.bat'),
        exists $option{windows} ? $option{windows}
            : ($option{windows_assignment} // windows_assignment()) . windows_command());
    return $distribution;
}

sub unix_assignment {
    return qq{CLASSPATH=\$APP_HOME/lib/$jar_name\n};
}

sub unix_command {
    return qq{set -- \\\n        -classpath "\$CLASSPATH" \\\n}
        . qq{        org.perlonjava.app.cli.Main \\\n        "\$@"\n}
        . qq{exec "\$JAVACMD" "\$@"\n};
}

sub windows_assignment {
    return "set CLASSPATH=%APP_HOME%\\lib\\$jar_name\r\n";
}

sub windows_command {
    return "endlocal & \"%JAVA_EXE%\" -classpath \"%CLASSPATH%\" "
        . "org.perlonjava.app.cli.Main %*\r\n";
}

sub create_jar {
    my ($jar, $tree) = @_;
    my @names;
    find({ no_chdir => 1, wanted => sub {
        return unless -f $_;
        my $name = File::Spec->abs2rel($_, $tree);
        $name =~ s{\\}{/}g;
        push @names, $name;
    }}, $tree);
    @names = sort @names;
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
