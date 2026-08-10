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

make_path('lib/Fixture/Data');
open my $makefile_pl, '>', 'Makefile.PL' or die "open Makefile.PL: $!";
print {$makefile_pl} "use ExtUtils::MakeMaker;\n";
close $makefile_pl or die "close Makefile.PL: $!";

for my $file (
    ['lib/Fixture/Data.pm',       "package Fixture::Data; our \$VERSION = '0.001'; 1;\n"],
    ['lib/Fixture/Data/types.db', "application/example example\n"],
    ['lib/Fixture/Data/noext',    "extensionless payload\n"],
    ['lib/Fixture/Data/editor~',  "must not be staged\n"],
) {
    open my $fh, '>', $file->[0] or die "open $file->[0]: $!";
    print {$fh} $file->[1];
    close $fh or die "close $file->[0]: $!";
}

no warnings 'once';
local $ExtUtils::MakeMaker::INSTALL_BASE = File::Spec->catdir($tmp, 'site');
WriteMakefile(NAME => 'Fixture::Data', VERSION => '0.001');

my $make = $ENV{MAKE} || 'make';
is(system($make), 0, 'generated Makefile builds');
ok(-f 'blib/lib/Fixture/Data.pm',       'Perl module is staged');
ok(-f 'blib/lib/Fixture/Data/types.db', 'arbitrary database payload is staged');
ok(-f 'blib/lib/Fixture/Data/noext',    'extensionless payload is staged');
ok(!-e 'blib/lib/Fixture/Data/editor~', 'editor backup is ignored');

chdir $old or die "chdir $old: $!";
done_testing;
