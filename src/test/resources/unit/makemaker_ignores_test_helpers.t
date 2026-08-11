use strict;
use warnings;

use Cwd qw(getcwd);
use ExtUtils::MakeMaker;
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

my $original = getcwd();
my $dist = tempdir(CLEANUP => 1);

END {
    chdir $original if defined $original;
}

chdir $dist or die "chdir $dist: $!";
make_path('lib/IO/All', 't');

open my $module, '>', 'lib/IO/All/Filesys.pm'
    or die "create library module: $!";
print {$module} "package IO::All::Filesys; sub set_lock { 1 } 1;\n";
close $module or die "close library module: $!";

# IO-All has a test helper with this shape: it starts in its own package but
# later reopens a production package to add test-only methods.  It is not a
# library payload and must never overwrite lib/IO/All/Filesys.pm in blib.
open my $helper, '>', 't/IO_Dumper.pm' or die "create test helper: $!";
print {$helper} <<'HELPER';
package IO_Dumper;
sub io { 1 }
package IO::All::Filesys;
sub dump { 1 }
1;
HELPER
close $helper or die "close test helper: $!";

WriteMakefile(NAME => 'IO::All', VERSION => '0.001');

open my $makefile_fh, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$makefile_fh> };
close $makefile_fh or die "close generated Makefile: $!";

like(
    $makefile,
    qr{lib/IO/All/Filesys\.pm},
    'production module is staged',
);
unlike(
    $makefile,
    qr{t/IO_Dumper\.pm},
    'test helper is not treated as a library payload',
);

done_testing;
