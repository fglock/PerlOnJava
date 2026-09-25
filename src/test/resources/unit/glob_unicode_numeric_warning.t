use strict;
use warnings;
use utf8;
use Test::More tests => 2;

my $warning = '';
local $SIG{__WARN__} = sub { $warning .= shift };
my $value = sprintf '%d', *PWÒMPF;

like $warning, qr/Argument "\*main::PW\\x\{d2\}MPF" isn't numeric in sprintf/,
    'numeric glob warning escapes its non-ASCII name';
is $value, 0, 'a glob still numifies to zero';
