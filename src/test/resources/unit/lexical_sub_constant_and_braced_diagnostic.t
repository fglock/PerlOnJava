use v5.18;
use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use Test::More;

my sub literal_constant () { 8 }
is(literal_constant(), 8, 'lexical constant sub returns its literal');
isnt(\scalar literal_constant(), \scalar literal_constant(),
    'lexical constant sub returns a fresh scalar for each call');

my sub method_literal_constant ():method { 3 }
isnt(\scalar method_literal_constant(), \scalar method_literal_constant(),
    'lexical :method constant sub returns a fresh scalar for each call');

eval "\${ \xB6eeeeeeeeeeee\n'x\n";
like($@, qr/after \$\{ <-- HERE near column 4/,
    'scalar braced variable retains Perl diagnostic position for malformed byte');

done_testing;
