use strict;
use warnings;
use Test::More;

BEGIN {
    package Local::ISBNConstants;

    sub GOOD_ISBN () { 1 }

    sub import {
        my ($class, @names) = @_;
        no strict 'refs';
        my $caller = caller;
        *{"${caller}::$_"} = \&{"${class}::$_"} for @names;
    }
}

BEGIN { Local::ISBNConstants->import('GOOD_ISBN') }

my $GOOD_ISBN = '0596527241';
is($GOOD_ISBN, '0596527241',
    'lexical scalar keeps its value when matching imported constant name');
is(GOOD_ISBN(), 1,
    'imported constant remains callable beside same-named lexical scalar');

done_testing;
