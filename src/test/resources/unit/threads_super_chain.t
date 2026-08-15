use strict;
use warnings;
use threads;

print "1..2\n";

{
    package ThreadSuperGrandparent;
    sub identify { 'grandparent' }
}

{
    package ThreadSuperParent;
    our @ISA = ('ThreadSuperGrandparent');
    sub identify { 'parent-' . shift->SUPER::identify }
}

{
    package ThreadSuperChild;
    our @ISA = ('ThreadSuperParent');
    sub identify { 'child-' . shift->SUPER::identify }
}

my $expected = 'child-parent-grandparent';
print(ThreadSuperChild->identify eq $expected
    ? "ok 1 - direct chained SUPER dispatch\n"
    : "not ok 1 - direct chained SUPER dispatch\n");

my $thread = threads->create(sub { ThreadSuperChild->identify });
my $result = $thread->join;
print(defined($result) && $result eq $expected
    ? "ok 2 - cloned chained SUPER dispatch\n"
    : "not ok 2 - cloned chained SUPER dispatch\n");
