use strict;
use warnings;
use Test::More tests => 12;

{
    package StableHashMethod::Counter;

    sub add {
        my ($self, $amount) = @_;
        $self->{x} += $amount;
        $self->{y} += $amount;
        return $self->{x} + $self->{y};
    }
}

my $counter = bless { x => 1, y => 2 }, 'StableHashMethod::Counter';
my $x_alias = \$counter->{x};

is($counter->add(2), 7, 'simple method returns the updated field sum');
is($counter->{x}, 3, 'method updates the first hash field in place');
is($counter->{y}, 4, 'method updates the second hash field in place');
is($$x_alias, 3, 'existing field aliases observe in-place method updates');

$counter->{x} = 1000;
$counter->{y} = 2000;
is($counter->add(3), 3006, 'method handles fields assigned between calls');
is($counter->{x}, 1003, 'first reassigned field is updated');
is($counter->{y}, 2003, 'second reassigned field is updated');

delete $counter->{x};
$counter->{x} = 20;
is($counter->add(1), 2025, 'method handles a deleted and recreated hash field');
is($counter->{x}, 21, 'recreated field is updated in place');

my @list_result = $counter->add(1);
is_deeply(\@list_result, [2027], 'list context preserves the one-value method result');
$counter->add(2);
is($counter->{x}, 24, 'void context still applies the first field update');
is($counter->{y}, 2007, 'void context still applies the second field update');
