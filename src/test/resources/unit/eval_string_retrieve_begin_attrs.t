#!/usr/bin/env perl
# Interpreter eval STRING: my %lex : ATTR(...) must still dispatch MODIFY_HASH_ATTRIBUTES
# when the lexical cell is installed via RETRIEVE_BEGIN_* (named subs parsed later may
# register evalBeginIds for the same OperatorNode before bytecode emission).

use strict;
use warnings;
use Test::More;

{
    package RTBeginAttr;
    our $modify_ran = 0;

    sub MODIFY_HASH_ATTRIBUTES {
        my ( $pkg, $ref, @attrs ) = @_;
        $modify_ran = 1;
        return ();
    }
}

eval q{
    package RTBeginAttr;
    {
        my %h : ATTR(test);
        sub _probe { scalar keys %h }
        1;
    }
} or die $@;

is( $RTBeginAttr::modify_ran, 1,
    'MODIFY_HASH_ATTRIBUTES runs for my %lex : ATTR under eval STRING (RETRIEVE_BEGIN path)' );

done_testing();
