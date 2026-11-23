use strict;
use warnings;
use Test::More;

our @events;

{
    package Issue2043::LoopRow;

    sub new { bless { id => $_[1] }, $_[0] }

    sub delete {
        my ($self) = @_;
        push @main::events, 'delete:' . $self->{id} . ':'
            . ($self->{destroyed} ? 'destroyed' : 'live');
        $self->{related_resultsets} = {};
    }

    sub DESTROY {
        my ($self) = @_;
        push @main::events, 'destroy:' . $self->{id};
        $self->{destroyed} = 1;
    }
}

package main;

sub rows {
    return map { Issue2043::LoopRow->new($_) } 1 .. 3;
}

$_->delete for rows();

is_deeply(
    [grep { /^delete:/ } @events],
    [qw(delete:1:live delete:2:live delete:3:live)],
    'values returned as a list remain alive through the for modifier body',
);
is(scalar grep({ /^destroy:/ } @events), 3,
    'each returned object is destroyed after the loop');

done_testing();
