use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);

END {
    chdir $orig_dir if defined $orig_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME      => 'Foo::MetaMerge',
    VERSION   => '0.001',
    PREREQ_PM => { 'List::Util' => 0 },
    META_MERGE => {
        prereqs => {
            runtime => {
                requires => {
                    'File::Spec'   => 0,
                    'Scalar::Util' => 0,
                },
            },
            build => {
                requires => {
                    'Test::More' => 0,
                },
            },
            configure => {
                requires => {
                    'ExtUtils::MakeMaker' => 0,
                },
            },
        },
    },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^#\tPREREQ_PM => \{ .*File::Spec=>q\[0\].*List::Util=>q\[0\].*Scalar::Util=>q\[0\].* \}$/m,
    'META_MERGE runtime prereqs are emitted in PREREQ_PM comment',
);

open my $ym, '<', 'MYMETA.yml' or die "open generated MYMETA.yml: $!";
my $mymeta = do { local $/; <$ym> };
close $ym or die "close generated MYMETA.yml: $!";

like($mymeta, qr/^requires:\n(?:  .+\n)*  File::Spec: '0'\n/m, 'runtime File::Spec is in MYMETA requires');
like($mymeta, qr/^requires:\n(?:  .+\n)*  Scalar::Util: '0'\n/m, 'runtime Scalar::Util is in MYMETA requires');
like($mymeta, qr/^requires:\n(?:  .+\n)*  List::Util: '0'\n/m, 'explicit PREREQ_PM remains in MYMETA requires');
like($mymeta, qr/^build_requires:\n(?:  .+\n)*  Test::More: '0'\n/m, 'META_MERGE build prereq is in MYMETA build_requires');
like($mymeta, qr/^configure_requires:\n(?:  .+\n)*  ExtUtils::MakeMaker: '0'\n/m, 'META_MERGE configure prereq is in MYMETA configure_requires');

done_testing();
