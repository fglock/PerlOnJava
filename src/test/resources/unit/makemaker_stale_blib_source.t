use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);

END {
    chdir $orig_dir if defined $orig_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";
make_path('lib/Foo', 'blib/lib/Foo') or die "make_path test dirs: $!";

open my $src_pm, '>', 'lib/Foo/Bar.pm'
    or die "create source module: $!";
print {$src_pm} "package Foo::Bar;\nour \$VERSION = '0.001';\n1;\n";
close $src_pm or die "close source module: $!";

open my $staged_pm, '>', 'blib/lib/Foo/Bar.pm'
    or die "create stale staged module: $!";
print {$staged_pm} "package Foo::Bar;\nour \$VERSION = '0.001';\n1;\n";
close $staged_pm or die "close staged module: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME    => 'Foo::Bar',
    VERSION => '0.001',
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^pm_to_blib:: lib\/Foo\/Bar\.pm$/m,
    'pm_to_blib depends on the real lib source',
);
unlike(
    $makefile,
    qr/^pm_to_blib::.*blib\/lib\/Foo\/Bar\.pm/m,
    'pm_to_blib does not depend on stale blib source files',
);
unlike(
    $makefile,
    qr/cp 'blib\/lib\/Foo\/Bar\.pm' '\$\(INST_LIB\)\/Foo\/Bar\.pm'/,
    'generated pm_to_blib does not copy a staged blib file onto itself',
);

done_testing();
