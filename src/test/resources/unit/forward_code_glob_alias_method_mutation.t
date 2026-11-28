use strict;
use warnings;
use Test::More tests => 3;

{
    package Local::ForwardCodeAliasMutation;

    BEGIN { *_multiply = \&_multiply_impl; }

    sub _multiply_impl {
        my ($class, $left, $right) = @_;
        $left->[0] *= $right->[0];
        return $left;
    }
}

my $left = [2];
my $right = [3];
my $product = Local::ForwardCodeAliasMutation->_multiply($left, $right);

is($left->[0], 6, 'method call through forward glob alias mutates its argument');
is($product, $left, 'forward glob alias returns the original mutable value');
is($product->[0], 6, 'forward glob alias returns the computed product');
