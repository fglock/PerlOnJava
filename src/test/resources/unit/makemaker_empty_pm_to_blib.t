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
    NAME    => 'Local::Empty',
    VERSION => '0.001',
    PM      => {},
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like($makefile, qr/^pm_to_blib\b.*:/m, 'empty pm_to_blib target is valid make syntax');
unlike($makefile, qr/^pm_to_blib$/m, 'empty pm_to_blib target is not emitted without a colon');

done_testing();
