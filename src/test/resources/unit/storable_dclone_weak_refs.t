use strict;
use warnings;
use Test::More;
use Scalar::Util qw(isweak weaken);
use Storable qw(dclone);

my $root = ['root'];
my $parent = ['tag', 'ul', {}, $root];
push @$root, $parent;
my $child = ['tag', 'li', {}, $parent];
push @$parent, $child;
weaken($child->[3]);

my $copy = dclone($root);
undef $root;
undef $parent;
undef $child;

my $copied_parent = $copy->[1];
my $copied_child = $copied_parent->[4];
ok(isweak($copied_child->[3]), 'dclone preserves weak references');
ok(defined($copied_child->[3]), 'cloned weak reference remains live through a strong owner');
is($copied_child->[3], $copied_parent,
    'cloned weak reference points into the cloned graph');

my $forward_target = [];
my $forward_weak = $forward_target;
weaken($forward_weak);
my $forward_copy = dclone([$forward_weak, $forward_target]);

ok(defined($forward_copy->[0]),
    'weak edge preceding strong edge survives graph construction');
ok(!isweak($forward_copy->[0]),
    'first weak edge is promoted to strong like native Storable');
is($forward_copy->[0], $forward_copy->[1],
    'forward weak edge points to cloned strong referent');

my $hash_parent = ['hash parent'];
my $hash_child = ['child', $hash_parent];
weaken($hash_child->[1]);
push @$hash_parent, $hash_child;
my $hash_copy = dclone({ parent => $hash_parent });

ok(defined($hash_copy->{parent}[1][1]),
    'hash-owned cloned referent keeps weak back-reference live');
ok(isweak($hash_copy->{parent}[1][1]),
    'hash-owned cloned back-reference remains weak');
is($hash_copy->{parent}[1][1], $hash_copy->{parent},
    'hash-owned cloned back-reference points to cloned parent');

my $dom_root = ['root'];
my $dom_parent = ['tag', 'body', {}, $dom_root];
weaken($dom_parent->[3]);
my $dom_child = ['tag', 'p', {}, $dom_parent];
weaken($dom_child->[3]);
push @$dom_parent, $dom_child;
push @$dom_root, $dom_parent;
my $dom_copy = dclone($dom_root);

ok(defined($dom_copy->[1][3]),
    'dclone return owner keeps a weakly linked root alive');
ok(isweak($dom_copy->[1][3]), 'cloned child-to-root edge remains weak');
is($dom_copy->[1][3], $dom_copy,
    'cloned child-to-root edge points to returned root');
ok(defined($dom_copy->[1][4][3]),
    'descendant weak parent remains live in returned graph');
is($dom_copy->[1][4][3], $dom_copy->[1],
    'descendant weak parent points inside returned graph');

done_testing;
