use strict;
use warnings;
use Test::More;

{
    package Local::IndirectMethod;
    sub new { bless { calls => [] }, shift }
    sub union {
        my ($self, @args) = @_;
        push @{ $self->{calls} }, \@args;
        return $self;
    }
}

my $set = Local::IndirectMethod->new;
my $other = Local::IndirectMethod->new;
my $array = [1, 5, 2, 8, 9, 1, 3, 4, 9];

union $set;
union $set "1-5,8-9";
union $set $other;
union $set +$array;

is(scalar @{ $set->{calls} }, 4, 'all indirect method calls dispatch to the object');
is(scalar @{ $set->{calls}[0] }, 0, 'indirect method accepts no extra arguments');
is($set->{calls}[1][0], '1-5,8-9', 'indirect method passes a string argument');
is($set->{calls}[2][0], $other, 'indirect method passes an object argument');
is_deeply($set->{calls}[3][0], $array, 'indirect method passes an array reference argument');

done_testing;
