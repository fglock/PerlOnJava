use strict;
use warnings;
use Test::More tests => 5;

our $destroyed = 0;

{
    package HashVoidAssignmentOwner;
    sub DESTROY { ++$main::destroyed }
}

{
    my %hash;
    %hash = (owner => bless({}, 'HashVoidAssignmentOwner'));
    is($destroyed, 0, 'void hash assignment keeps the stored value alive');
    is(ref($hash{owner}), 'HashVoidAssignmentOwner', 'hash stores its assigned value');

    %hash = ();
    is($destroyed, 1, 'clearing the hash releases the value after discarded result cleanup');
}

$destroyed = 0;
{
    my $row = bless {
        owner => bless({}, 'HashVoidAssignmentOwner'),
        state => 0,
    }, 'HashVoidAssignmentRow';
    is($destroyed, 0, 'blessed hash literal retains its field value');
    undef $row;
    is($destroyed, 1, 'discarded hash literal result does not retain a field value');
}
