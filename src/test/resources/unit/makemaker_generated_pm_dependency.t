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

open my $pl, '>', 'ReadKey.pm.PL'
    or die "create generated pm template: $!";
print {$pl} "open my \$out, '>', 'ReadKey.pm' or die \$!; print {\$out} qq{package Term::ReadKey; 1;\\n}; close \$out;\n";
close $pl or die "close generated pm template: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME     => 'Term::ReadKey',
    VERSION  => '0.001',
    PL_FILES => { 'ReadKey.pm.PL' => 'ReadKey.pm' },
    PM       => { 'ReadKey.pm'    => '$(INST_ARCHLIBDIR)/ReadKey.pm' },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^all\b.*:/m,
    'Makefile emits an all target',
);
like(
    $makefile,
    qr/^pm_to_blib\b.*:/m,
    'Makefile emits a valid pm_to_blib target',
);
like(
    $makefile,
    qr/ReadKey\.pm\.PL/,
    'PL_FILES command is still emitted',
);

done_testing();
