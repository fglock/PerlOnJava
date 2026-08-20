use strict;
use warnings;
use utf8;
use re '/u';
use Test::More;

ok("\x{212A}K" =~ /\A(?<fold>\x{212A})\k<fold>\z/i,
   'named backreference folds Kelvin sign to K');
ok("\x{00C5}\x{212B}" =~ /\A(?<fold>\x{00C5})\k<fold>\z/i,
   'named backreference folds A-ring to Angstrom sign');
ok(!("\x{212A}K" =~ /\A(?aa:(?<fold>\x{212A})\k<fold>)\z/i),
   'ASCII-strict backreference rejects Kelvin to K');

done_testing;
