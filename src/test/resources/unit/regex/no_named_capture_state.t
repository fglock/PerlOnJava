use strict;
use warnings;
use Test::More;

ok('plain-42' =~ /(plain)-(42)/, 'unnamed captures match');
is_deeply({ %+ }, {}, 'unnamed captures publish an empty named-capture hash');
is_deeply({ %- }, {}, 'unnamed captures publish no named-capture offsets');

ok('named-42' =~ /(?<word>named)-(42)/, 'named capture after unnamed match');
is($+{word}, 'named', 'later named match replaces the empty named-capture state');

done_testing;
