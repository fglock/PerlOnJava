use strict;
use warnings;
use utf8;
use Test::More tests => 3;

local $ENV{PERLONJAVA_UTF8_ENV_TEST} = 'bär';
my $value = $ENV{PERLONJAVA_UTF8_ENV_TEST};
ok(!utf8::is_utf8($value), '%ENV downgrades a Latin-1 value to a byte string');
is(join(',', map { ord($_) } split //, $value), '98,228,114',
   '%ENV preserves native byte values while downgrading');

local $ENV{PERLONJAVA_NUMERIC_ENV_TEST} = 42;
ok(!utf8::is_utf8($ENV{PERLONJAVA_NUMERIC_ENV_TEST}),
   '%ENV stringifies numeric values without a UTF-8 flag');
