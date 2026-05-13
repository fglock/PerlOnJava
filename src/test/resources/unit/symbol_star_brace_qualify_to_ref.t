# Regression: *{ qualify EXPR } must parse EXPR as code (Symbol::qualify_to_ref).
#
# Run: perl src/test/resources/unit/symbol_star_brace_qualify_to_ref.t
#      ./jperl src/test/resources/unit/symbol_star_brace_qualify_to_ref.t

use strict;
use warnings;
use Test::More tests => 3;

use Symbol qw( qualify qualify_to_ref );

package Testophile;

no strict 'refs';
*{ 'bin' } = [ 'one', 'two' ];

package main;

is(
    Symbol::qualify( 'bin', 'Testophile' ),
    'Testophile::bin',
    'qualify(bin, Testophile)',
);

my $r = do {
    package Testophile;
    Symbol::qualify_to_ref('bin');
};
is( ref($r), 'GLOB', 'qualify_to_ref (1-arg, caller Testophile) returns GLOB ref' );

is(
    scalar( @{ *{$r} } ),
    2,
    '@{ *qualify_to_ref(...) } aliases @Testophile::bin',
);
