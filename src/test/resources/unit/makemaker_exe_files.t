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

    open my $makefile_pl, '>', 'Makefile.PL'
        or die "create Makefile.PL: $!";
    print {$makefile_pl} "use ExtUtils::MakeMaker;\nWriteMakefile(NAME => 'Foo::Script', VERSION => '0.001', EXE_FILES => ['script/demo']);\n";
    close $makefile_pl or die "close Makefile.PL: $!";

    use ExtUtils::MakeMaker;

    WriteMakefile(
        NAME      => 'Foo::Script',
        VERSION   => '0.001',
        EXE_FILES => ['script/demo'],
    );

    my $make = $Config::Config{make} || 'make';
    my $status = system($make, 'pure_all');
    is($status, 0, 'pure_all target succeeds');

    my @staged_candidates = (
        'blib/script/demo',
        File::Spec->catfile($Config::Config{installbin} || '', 'demo'),
        File::Spec->catfile($Config::Config{installscript} || '', 'demo'),
    );
    my ($staged_path) = grep { defined $_ && length $_ && -f $_ } @staged_candidates;
    ok(defined $staged_path, 'Perl script is staged');

    open my $staged, '<', $staged_path
        or die "open staged script: $!";
    my $first_line = <$staged>;
    my $second_line = <$staged>;
    close $staged or die "close staged script: $!";
    chomp $first_line;
    chomp $second_line;

    like($first_line, qr/^#!/, 'EXE_FILES staged script has a shebang');
    ok(defined $second_line, 'staged script has executable content');
    ok(-s $staged_path, 'staged script has content');

    my $output = qx{$^X "$staged_path"};
    is($?, 0, 'rewritten staged script executes');
    is($output, "ok\n", 'rewritten staged script produces expected output');
}

done_testing();
