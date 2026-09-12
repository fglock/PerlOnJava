use strict;
use warnings;
use Test::More;

sub leaf_increment {
    my ($value) = @_;
    return $value + 1;
}

is(leaf_increment(40), 41, 'simple leaf subroutine remains callable');
is(leaf_increment(41), 42, 'repeated simple leaf calls retain call isolation');

my $maker = sub {
    my ($value) = @_;
    return sub { $value + 1 };
};
my $capturing = $maker->(99);
is($capturing->(), 100, 'nested closure retains its capture after maker returns');

done_testing;
