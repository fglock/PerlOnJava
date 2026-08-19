use strict;
use warnings;
use utf8;
use Test::More tests => 12;

ok("\x{1E9E}" !~ /^[^\x{00DF}]$/iaa,
    'aa negative class excludes capital sharp s sibling');
ok("\x{00DF}" !~ /^[^\x{1E9E}]$/iaa,
    'aa negative class excludes lowercase sharp s sibling');
ok("\x{FB06}" !~ /^[^\x{FB05}]$/iaa,
    'aa negative class excludes second st ligature sibling');
ok("\x{FB05}" !~ /^[^\x{FB06}]$/iaa,
    'aa negative class excludes first st ligature sibling');

ok("s" =~ /^[^\x{00DF}]$/iaa,
    'aa negative sharp s class retains ASCII s');
ok("ä" =~ /^[^\x{00DF}]$/iaa,
    'aa negative sharp s class retains unrelated non-ASCII');
ok("s" =~ /^[^\x{FB05}]$/iaa,
    'aa negative ligature class retains ASCII s');
ok("ä" =~ /^[^\x{FB05}]$/iaa,
    'aa negative ligature class retains unrelated non-ASCII');

ok("x\x{1E9E}y" !~ /^x(?iaa:[^\x{00DF}])y$/u,
    'scoped aa negative class excludes sharp s sibling');
ok("xäy" =~ /^x(?iaa:[^\x{00DF}])y$/u,
    'scoped aa negative class retains unrelated member');
ok("_\x{FB06}_" !~ /^_(?iaa:[^\x{FB05}])_$/u,
    'anchored aa negative class excludes ligature sibling');
ok("_ä_" =~ /^_(?iaa:[^\x{FB05}])_$/u,
    'anchored aa negative class retains unrelated member');
