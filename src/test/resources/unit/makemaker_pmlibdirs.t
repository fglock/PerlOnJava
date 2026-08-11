use strict;
use warnings;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = tempdir(CLEANUP => 1);
make_path(File::Spec->catdir($root, 'Lock'));

open my $makefile_pl, '>', File::Spec->catfile($root, 'Makefile.PL')
    or die "cannot write Makefile.PL: $!";
print {$makefile_pl} "# generated test distribution\n";
close $makefile_pl;

for my $file (
    [ 'Simple.pm',      "package LockFile::Simple; our \$VERSION = '1.00'; 1;\n" ],
    [ 'Lock.pm',        "package LockFile::Lock; 1;\n" ],
    [ 'Lock/Simple.pm', "package LockFile::Lock::Simple; 1;\n" ],
) {
    my $path = File::Spec->catfile($root, split m{/}, $file->[0]);
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $file->[1];
    close $fh;
}

my $old = getcwd();
chdir $root or die "cannot chdir to $root: $!";
require ExtUtils::MakeMaker;
ExtUtils::MakeMaker::WriteMakefile(
    NAME       => 'LockFile::Simple',
    VERSION    => '1.00',
    PMLIBDIRS  => [ 'Lock' ],
);

ok(system($ENV{MAKE} || 'make') == 0, 'MakeMaker stages explicit PMLIBDIRS');
ok(-f File::Spec->catfile('blib', 'lib', 'LockFile', 'Lock', 'Simple.pm'),
   'explicit sibling package tree is mapped below the NAME parent');
ok(-f File::Spec->catfile('blib', 'lib', 'LockFile', 'Simple.pm'),
   'root primary module is staged normally');

chdir $old or die "cannot restore cwd: $!";
done_testing;
