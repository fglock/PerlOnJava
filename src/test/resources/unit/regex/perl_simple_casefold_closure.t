use strict;
use warnings;
use utf8;
use Test::More;

ok("\x{212A}" =~ /\A(?d:k)\z/i,
    'default charset literal folds ASCII k to Kelvin sign');
ok("\x{212B}" =~ /\A(?u:\x{00e5})\z/i,
    'Unicode literal folds A-ring to Angstrom sign');
ok("\x{212A}K" =~ /\A(?a:kk)\z/i,
    'ASCII charset literal preserves Perl simple-class closure across a sequence');
ok("\x{212A}" =~ /\A(?d:[k])\z/i,
    'default charset character class folds ASCII k to Kelvin sign');
ok("\x{212A}" =~ /\A(?d:[K])\z/i,
    'default charset uppercase character class folds Kelvin sign');
ok("\x{212A}" !~ /\A(?aa:k)\z/i,
    'ASCII strict literal forbids the ASCII to non-ASCII crossing');

done_testing;
