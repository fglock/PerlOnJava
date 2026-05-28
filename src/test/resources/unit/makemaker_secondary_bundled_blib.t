use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

plan skip_all => 'Compress::Zlib is not bundled in this runtime'
    unless -f 'jar:PERL5LIB/Compress/Zlib.pm';

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);

END {
    chdir $orig_dir if defined $orig_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";
make_path('lib/Local', 'lib/Compress') or die "make_path test dirs: $!";

open my $primary, '>', 'lib/Local/NeedsBundled.pm'
    or die "create primary module: $!";
print {$primary} "package Local::NeedsBundled;\nour \$VERSION = '0.001';\n1;\n";
close $primary or die "close primary module: $!";

open my $secondary, '>', 'lib/Compress/Zlib.pm'
    or die "create secondary bundled module: $!";
print {$secondary} "package Compress::Zlib;\nour \$VERSION = '0.001';\n1;\n";
close $secondary or die "close secondary bundled module: $!";

use ExtUtils::MakeMaker;

my $site = "$tmpdir/site";
{
    local $ExtUtils::MakeMaker::INSTALL_BASE = $site;
    WriteMakefile(
        NAME    => 'Local::NeedsBundled',
        VERSION => '0.001',
    );
}

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^pm_to_blib:: .*lib\/Compress\/Zlib\.pm.*lib\/Local\/NeedsBundled\.pm$/m,
    'secondary bundled module remains a pm_to_blib dependency',
);
like(
    $makefile,
    qr/cp 'lib\/Compress\/Zlib\.pm' '\$\(INST_LIB\)\/Compress\/Zlib\.pm'/,
    'secondary bundled module is staged into blib for tests',
);
unlike(
    $makefile,
    qr/cp 'lib\/Compress\/Zlib\.pm' '\Q$site\E\/Compress\/Zlib\.pm'/,
    'secondary bundled module is not installed over the bundled shim',
);
like(
    $makefile,
    qr/cp 'lib\/Local\/NeedsBundled\.pm' '\Q$site\E\/Local\/NeedsBundled\.pm'/,
    'primary module is still installed normally',
);
unlike(
    $makefile,
    qr/skipping upstream test suite/,
    'secondary bundled modules do not make tests a no-op',
);

done_testing();
