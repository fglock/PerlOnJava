use strict;
use warnings;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = tempdir(CLEANUP => 1);
open my $makefile_pl, '>', File::Spec->catfile($root, 'Makefile.PL')
    or die "cannot write Makefile.PL: $!";
print {$makefile_pl} "# generated test distribution\n";
close $makefile_pl;

my $module = File::Spec->catfile($root, 'NCA.pm');
open my $fh, '>', $module or die "cannot write $module: $!";
print {$fh} <<'MODULE';
package Algorithm::Tree::NCA::Data;
our $VERSION = '0.02';

package Algorithm::Tree::NCA;
1;
MODULE
close $fh;

my $old = getcwd();
chdir $root or die "cannot chdir to $root: $!";
require ExtUtils::MakeMaker;
ExtUtils::MakeMaker::WriteMakefile(
    NAME    => 'Algorithm::Tree::NCA',
    VERSION => '0.02',
);

is(system($ENV{MAKE} || 'make'), 0, 'MakeMaker stages root-level modules');
ok(-f File::Spec->catfile('blib', 'lib', 'Algorithm', 'Tree', 'NCA.pm'),
   'root module is staged at the exact NAME package path');
ok(!-f File::Spec->catfile('blib', 'lib', 'Algorithm', 'Tree', 'NCA', 'Data.pm'),
   'earlier helper package does not determine the root module path');

local @INC = (File::Spec->catdir('blib', 'lib'), @INC);
ok(eval { require Algorithm::Tree::NCA; 1 },
   'the staged primary module can be loaded');
is($@, '', 'loading the staged primary module has no error');

chdir $old or die "cannot restore cwd: $!";
done_testing;
