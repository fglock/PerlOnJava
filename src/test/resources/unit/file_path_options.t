use strict;
use warnings;

use File::Path qw(make_path remove_tree);
use File::Spec;
use File::Temp;
use Test::More tests => 4;

my $root = File::Temp::tempdir(CLEANUP => 1);
my $nested = File::Spec->catdir($root, 'one', 'two');
my $errors;
make_path($nested, {error => \$errors});
ok(-d $nested, 'make_path creates nested directory');
is_deeply($errors, [], 'make_path initializes an empty error list');

my $child = File::Spec->catfile($nested, 'child.txt');
open my $fh, '>', $child or die "open $child: $!";
close $fh or die "close $child: $!";
remove_tree($nested, {keep_root => 1});
ok(-d $nested, 'remove_tree keep_root preserves root directory');
ok(!-e $child, 'remove_tree keep_root removes children');
