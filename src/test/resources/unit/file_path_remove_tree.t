use 5.38.0;
use strict;
use warnings;
use Test::More tests => 2;
use File::Path qw(remove_tree);

my $root = "/tmp/perlonjava-file-path-remove-tree-$$-" . time();
my $child = "$root/unreadable";

END {
    chmod 0700, $child if defined $child && -e $child;
    chmod 0700, $root  if defined $root  && -e $root;
    rmdir $child if defined $child;
    rmdir $root  if defined $root;
}

mkdir($root, 0700) or die "mkdir $root: $!";
mkdir($child, 0100) or die "mkdir $child: $!";
chmod(0100, $child) or die "chmod $child: $!";

my $removed = eval { remove_tree($root) };
my $err = $@;

is($err, '', 'remove_tree does not die on unreadable child directories');
ok(!-e $root, 'remove_tree removes unreadable child directories');
