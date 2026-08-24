use strict;
use warnings;
use utf8;
use lib 'src/test/resources/unit/lib';
use Test::More tests => 3;

BEGIN {
    eval "use Local::NoUtf8ForBeginEval; 1" or die $@;
}

my $chars = "Café Paris|Garçon";
is(length($chars), 17,
    'UTF-8 source remains decoded after BEGIN-time eval loads a module');
is(ord(substr($chars, 3, 1)), 0xe9,
    'first non-ASCII source character remains decoded');
is(ord(substr($chars, 14, 1)), 0xe7,
    'second non-ASCII source character remains decoded');
