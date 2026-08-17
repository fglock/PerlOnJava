use strict;
use warnings;
use Test::More tests => 4;

sub token { join '|', @_ }

is(token(eq => 'value'), 'eq|value',
    'word operator before fat comma is a bareword key');
is(token(cmp => 'value'), 'cmp|value',
    'comparison operator before fat comma is a bareword key');

use constant READ_ONLY_VALUE => 1;
eval 'READ_ONLY_VALUE = 2';
like($@, qr/Can't modify constant item in scalar assignment/,
    'assignment to a constant reports the constant-item diagnostic');

$Astro::Constants::2019::VERSION = '0.15';
is($Astro::Constants::2019::VERSION, '0.15',
    'qualified scalar permits a numeric package segment');
