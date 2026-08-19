use strict;
use warnings;
use utf8;
use Test::More tests => 10;

ok("ŉ" =~ /\A(?i:ʼN)\z/,
    'ordinary full folding accepts the mixed source');
ok("ŉ" !~ /\AʼN\z/iaa,
    'top-level aa blocks the mixed source');
ok("ŉ" !~ /\A(?aa:ʼN)\z/i,
    'scoped aa under outer i blocks uppercase mixed source');
ok("ŉ" !~ /\A(?aa:ʼn)\z/i,
    'scoped aa under outer i blocks lowercase mixed source');
ok("ŉ" !~ /\A(?iaa:ʼN)\z/,
    'self-contained iaa blocks the mixed source');
ok("ʼN" !~ /\A(?aa:ŉ)\z/i,
    'scoped aa blocks the reverse crossing');
ok("_ŉ_" !~ /\A_(?aa:ʼN)_\z/i,
    'scoped aa blocks a surrounded mixed source');
ok("ŉ" !~ /\A(?aa:(ʼN)+)\z/i,
    'scoped aa blocks a captured repeated mixed source');

my $rhs = "ʼN";
ok("ŉ" !~ /\A(?aa:$rhs)\z/i,
    'scoped aa blocks an interpolated mixed source');
ok("__ŉ" !~ /\A(?aa:(?:.|\R)*?$rhs)\z/i,
    'scoped aa blocks an interpolated mixed suffix after a lazy prefix');
