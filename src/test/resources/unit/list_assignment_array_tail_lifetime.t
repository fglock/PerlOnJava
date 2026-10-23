use strict;
use warnings;
use Test::More tests => 4;

{
    package Local::ListAssignmentTailLifetime;
    our $destroyed = 0;
    sub DESTROY { $destroyed++ }
}

my $old = bless {}, 'Local::ListAssignmentTailLifetime';
my $new = bless {}, 'Local::ListAssignmentTailLifetime';
my @tail = ($old);
my $head;

($head, @tail) = (7, $new);
undef $old;
is($Local::ListAssignmentTailLifetime::destroyed, 1,
    'list assignment releases the displaced array-tail value');

undef $new;
is($Local::ListAssignmentTailLifetime::destroyed, 1,
    'the assigned array tail keeps its value alive');

my $removed = pop @tail;
is($Local::ListAssignmentTailLifetime::destroyed, 1,
    'pop transfers the final array owner to its result');

undef $removed;
is($Local::ListAssignmentTailLifetime::destroyed, 2,
    'releasing the popped result destroys the final value');
