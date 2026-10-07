use strict;
use warnings;
use feature 'switch';
no warnings 'deprecated';
no strict 'refs';
use Test::More tests => 1;

*mybreak = \&CORE::break;

my $after_break;
CORE::given(1) {
    CORE::when(1) {
        &mybreak;
        $after_break = 'unreachable';
    }
}

is($after_break, undef,
    'CORE::break code references exit the enclosing given block');
