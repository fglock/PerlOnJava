use strict;
use warnings;
use utf8;
use Test::More;

my $globref = \*αabcdefg_::_;
() = substr($$globref, 2, 3);
*_abcdefgα:: = \%αabcdefg_::;
undef %αabcdefg_::;
{ no strict 'refs'; () = *{"_abcdefgα::_"} }

is substr($$globref, 2, 3), 'abc',
    'an aliased glob keeps its visible stash name after source stash deletion';

done_testing;
