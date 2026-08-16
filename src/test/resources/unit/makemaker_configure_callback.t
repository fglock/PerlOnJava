use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);
my $configure_calls = 0;

END {
    chdir $orig_dir if defined $orig_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";

make_path('d');
open my $dummy, '>', 'd/Configured.pm' or die "create metadata stub: $!";
print {$dummy} "package Fixture::Configured;\n0;\n";
close $dummy or die "close metadata stub: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME         => 'Fixture::Configured',
    VERSION_FROM => 'Configured.pm',
    CONFIGURE    => sub {
        $configure_calls++;
        open my $pm, '>', 'Configured.pm' or die "create Configured.pm: $!";
        print {$pm} "package Fixture::Configured;\nour \$VERSION = '0.016';\n1;\n";
        close $pm or die "close Configured.pm: $!";
        return {
            PM => { 'Configured.pm' => '$(INST_LIBDIR)/Configured.pm' },
        };
    },
);

is($configure_calls, 1, 'CONFIGURE callback runs exactly once');
ok(-f 'Configured.pm', 'CONFIGURE callback can generate the module source');

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like($makefile, qr/Configured\.pm/, 'CONFIGURE return values are merged into MakeMaker arguments');
like($makefile, qr/0\.016/, 'version is read after CONFIGURE generated VERSION_FROM');
unlike($makefile, qr{d/Configured\.pm}, 'generated root module wins over auxiliary metadata stub');

done_testing();
