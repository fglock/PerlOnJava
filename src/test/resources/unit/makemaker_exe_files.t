use strict;
use warnings;
use Test::More;
use Config;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);

if ($^O eq 'MSWin32') {
    pass('EXE_FILES shell-wrapper execution is Unix-only');
}
else {
    my $orig_dir = getcwd();
    my $tmpdir = tempdir(CLEANUP => 1);
    my $expected_jperl = $^X;
    if (!File::Spec->file_name_is_absolute($expected_jperl)) {
        my $candidate = File::Spec->catfile($orig_dir, $expected_jperl);
        $expected_jperl = $candidate if -x $candidate;
    }

    END {
        chdir $orig_dir if defined $orig_dir;
    }

    chdir $tmpdir or die "chdir $tmpdir: $!";
    make_path('script', 'lib/Foo') or die "make_path test dirs: $!";

    open my $module, '>', 'lib/Foo/Script.pm'
        or die "create lib/Foo/Script.pm: $!";
    print {$module} "package Foo::Script;\nour \$VERSION = '0.001';\n1;\n";
    close $module or die "close lib/Foo/Script.pm: $!";

    open my $script, '>', 'script/demo'
        or die "create script/demo: $!";
    print {$script} "#!perl -w\nprint qq(ok\\n);\n";
    close $script or die "close script/demo: $!";
    chmod 0755, 'script/demo' or die "chmod script/demo: $!";

    use ExtUtils::MakeMaker;

    WriteMakefile(
        NAME      => 'Foo::Script',
        VERSION   => '0.001',
        EXE_FILES => ['script/demo'],
    );

    my $make = $Config::Config{make} || 'make';
    my $status = system($make, 'blib_scripts');
    is($status, 0, 'blib_scripts target succeeds');

    open my $staged, '<', 'blib/script/demo'
        or die "open staged script: $!";
    my $first_line = <$staged>;
    my $second_line = <$staged>;
    my $third_line = <$staged>;
    close $staged or die "close staged script: $!";
    chomp $first_line;
    chomp $second_line;
    chomp $third_line;

    is($first_line, '#!/bin/sh', 'EXE_FILES shebang is rewritten to a shell wrapper');
    is($second_line, 'dir=$(dirname "$0")', 'shell wrapper locates its script directory');
    is($third_line, qq{exec "$expected_jperl" -w "\$dir/.demo.perlonjava" "\$@"}, 'shell wrapper execs jperl payload');

    ok(-f 'blib/script/.demo.perlonjava', 'original Perl script is staged as hidden payload');

    my $output = qx{blib/script/demo};
    is($?, 0, 'rewritten staged script executes');
    is($output, "ok\n", 'rewritten staged script produces expected output');
}

done_testing();
