use v5.18;
use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use Test::More;

my sub foo () { 42 }
my $error;
{
    use warnings FATAL => 'all';
    eval { undef &foo };
    $error = $@;
}

like $error, qr/Constant subroutine foo undefined at/,
    'undefining a lexical constant subroutine warns';

done_testing;
