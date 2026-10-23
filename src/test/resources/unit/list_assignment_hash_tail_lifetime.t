use strict;
use warnings;
use Test::More tests => 4;

{
    package Local::ListAssignmentHashTailLifetime;
    our $destroyed = 0;
    sub DESTROY { $destroyed++ }
}

my $old = bless {}, 'Local::ListAssignmentHashTailLifetime';
my $new = bless {}, 'Local::ListAssignmentHashTailLifetime';
my %tail = (old => $old);
my $head;

($head, %tail) = (7, new => $new);
undef $old;
is($Local::ListAssignmentHashTailLifetime::destroyed, 1,
    'list assignment releases the displaced hash-tail value');

undef $new;
is($Local::ListAssignmentHashTailLifetime::destroyed, 1,
    'the assigned hash tail keeps its value alive');

my $removed = delete $tail{new};
is($Local::ListAssignmentHashTailLifetime::destroyed, 1,
    'delete transfers the final hash owner to its result');

undef $removed;
is($Local::ListAssignmentHashTailLifetime::destroyed, 2,
    'releasing the deleted result destroys the final value');
