use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

use ExtUtils::MakeMaker;

my $tmp = tempdir(CLEANUP => 1);
my $old = File::Spec->rel2abs('.');
chdir $tmp or die "chdir $tmp: $!";

make_path('lib/Fixture/InstallShare', 'share');
open my $module, '>', 'lib/Fixture/InstallShare.pm'
    or die "open module: $!";
print {$module} "package Fixture::InstallShare; our \$VERSION = '0.001'; 1;\n";
close $module or die "close module: $!";
open my $payload, '>', 'share/sample.dat' or die "open share payload: $!";
print {$payload} "share payload\n";
close $payload or die "close share payload: $!";
open my $makefile_pl, '>', 'Makefile.PL' or die "open Makefile.PL: $!";
print {$makefile_pl} "use ExtUtils::MakeMaker;\n";
close $makefile_pl or die "close Makefile.PL: $!";

no warnings 'once';
my $site = File::Spec->catdir($tmp, 'site');
local $ExtUtils::MakeMaker::INSTALL_BASE = $site;
WriteMakefile(
    NAME => 'Fixture::InstallShare',
    VERSION => '0.001',
    INSTALL_BASE => $site,
    INSTALLSITELIB => $site,
);

open my $makefile, '>>', 'Makefile' or die "append Makefile: $!";
print {$makefile} "\nconfig::\n"
    . "\t\$(NOECHO) \$(MOD_INSTALL) \\\n"
    . "\t\t\"share\" \$(INST_AUTODIR)\n";
close $makefile or die "close Makefile: $!";

my $make = $ENV{MAKE} || 'make';
is(system($make, '-C', $tmp), 0,
    'generated Makefile builds and runs the install_share rule');
open $makefile, '<', 'Makefile' or die "open Makefile: $!";
my $makefile_text = do { local $/; <$makefile> };
close $makefile or die "close Makefile: $!";
like($makefile_text, qr/MOD_INSTALL = .*ExtUtils::Install/s,
    'MOD_INSTALL invokes ExtUtils::Install for generated share rules');
my $staged = File::Spec->catfile(
    'blib', 'lib', 'auto', 'Fixture', 'InstallShare', 'sample.dat');
ok(-f $staged, 'share file is staged under the module auto directory');

is(system($make, '-C', $tmp, 'pure_install'), 0,
    'generated Makefile installs staged share files');
ok(-f File::Spec->catfile($site, 'auto', 'Fixture', 'InstallShare', 'sample.dat'),
    'share file is copied to INSTALL_BASE');

chdir $old or die "chdir $old: $!";
done_testing();
