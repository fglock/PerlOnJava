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
    qr/List::Util|PREREQ_PM/,
    'Makefile records prerequisite metadata',
);

open my $ym, '<', 'MYMETA.yml' or die "open generated MYMETA.yml: $!";
my $mymeta = do { local $/; <$ym> };
close $ym or die "close generated MYMETA.yml: $!";

like($mymeta, qr/File::Spec|Scalar::Util|List::Util/, 'runtime prerequisites are represented in MYMETA');
like($mymeta, qr/List::Util/, 'explicit PREREQ_PM remains in MYMETA');
like($mymeta, qr/Test::More|build_requires|prereqs/, 'build prereq metadata is represented in MYMETA');
like($mymeta, qr/ExtUtils::MakeMaker|configure_requires|prereqs/, 'configure prereq metadata is represented in MYMETA');
like($mymeta, qr/version: '0\.001'/, 'distribution version is represented in MYMETA');

done_testing();
