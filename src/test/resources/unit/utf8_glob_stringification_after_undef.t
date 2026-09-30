use strict;
use warnings;
use Test::More;
use utf8;

my $globref = \*αabcdefg_::_;
() = substr($$globref, 2, 3);
*_abcdefgα:: = \%αabcdefg_::;
undef %αabcdefg_::;
{ no strict; () = *{"_abcdefgα::_"} }
is substr($$globref, 2, 3), 'abc',
    'glob stringification retains the aliased package name after undef';

done_testing();
