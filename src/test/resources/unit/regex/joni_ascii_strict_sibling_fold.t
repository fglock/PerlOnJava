use strict;
use warnings;
use utf8;
use Test::More tests => 14;

ok("\x{1E9E}" =~ /^\x{00DF}$/iaa,
    'aa keeps capital sharp s as lowercase sharp s sibling');
ok("\x{00DF}" =~ /^\x{1E9E}$/iaa,
    'aa keeps lowercase sharp s as capital sharp s sibling');
ok("ss" !~ /^\x{00DF}$/iaa,
    'aa blocks sharp s pattern from expanding to ASCII');
ok("\x{00DF}" !~ /^ss$/iaa,
    'aa blocks ASCII pattern from matching sharp s');

ok("\x{FB06}" =~ /^\x{FB05}$/iaa,
    'aa keeps the two non-ASCII st ligatures as siblings');
ok("\x{FB05}" =~ /^\x{FB06}$/iaa,
    'aa keeps the reverse non-ASCII st ligature sibling');
ok("st" !~ /^\x{FB05}$/iaa,
    'aa blocks first st ligature pattern from expanding to ASCII');
ok("\x{FB05}" !~ /^st$/iaa,
    'aa blocks ASCII pattern from matching first st ligature');

ok("\x{1E9E}" =~ /^[\x{00DF}]$/iaa,
    'aa keeps sharp s siblings in a positive class');
ok("\x{FB06}" =~ /^[\x{FB05}]$/iaa,
    'aa keeps ligature siblings in a positive class');

ok("x\x{1E9E}y" =~ /^x(?iaa:\x{00DF})y$/u,
    'scoped aa keeps sharp s siblings');
ok("xssy" !~ /^x(?iaa:\x{00DF})y$/u,
    'scoped aa still blocks sharp s ASCII expansion');
ok("_\x{FB06}_" =~ /^_(?iaa:\x{FB05})_$/u,
    'anchored aa keeps ligature siblings with surrounding literals');
ok("_st_" !~ /^_(?iaa:\x{FB05})_$/u,
    'anchored aa blocks ligature ASCII expansion');
