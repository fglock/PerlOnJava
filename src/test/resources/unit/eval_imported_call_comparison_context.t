use strict;
use warnings;
use Test::More tests => 1;

my $passed = eval q{
    use Test::More;
    sub context_values($) {
        my @values = qw(a b c d);
        @values;
    }
    context_values(1) == 4;
};

ok($passed, 'comparison operands use scalar context after an import in eval STRING');
