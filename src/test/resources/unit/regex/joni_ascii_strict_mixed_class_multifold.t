use strict;
use warnings;
use utf8;
use Test::More tests => 10;

ok("\x{017F}\x{017F}" =~ /^[\x{00DF}_]$/iaa,
    'mixed aa class expands sharp s to safe long-s pair');
ok("\x{017F}\x{017F}" =~ /^[\x{1E9E}_]$/iaa,
    'mixed aa class expands capital sharp s to safe long-s pair');
ok("x\x{017F}\x{017F}y" =~ /^x[\x{00DF}_]y$/iaa,
    'mixed aa class keeps safe expansion between literals');
ok("x\x{017F}\x{017F}y" =~ /^x([\x{1E9E}_])y$/iaa,
    'captured mixed aa class keeps safe expansion');
ok("\x{017F}\x{017F}\x{017F}\x{017F}" =~ /^[\x{00DF}_]+$/iaa,
    'quantified mixed aa class keeps repeated safe expansions');
ok("_\x{017F}\x{017F}_" =~ /^_[\x{1E9E}_]_$/iaa,
    'anchored mixed aa class keeps safe expansion');

ok("ss" !~ /^[\x{00DF}_]$/iaa,
    'mixed aa class still blocks ASCII sharp-s expansion');
ok("SS" !~ /^[\x{1E9E}_]$/iaa,
    'mixed aa class still blocks capital ASCII expansion');
ok("_" =~ /^[\x{00DF}_]$/iaa,
    'mixed aa class retains its unrelated ASCII member');
ok("\x{1E9E}" =~ /^[\x{00DF}_]$/iaa,
    'mixed aa class retains the one-code-point safe sibling');
