use strict;
use warnings;
use utf8;
use Test::More;

ok("K\x{212A}" !~ /^(K)\1$/iaa,
    'top-level aa rejects ASCII to Kelvin numbered backreference fold');
ok("K\x{212A}" !~ /(?iaa:^(K)\1$)/,
    'scoped aa rejects ASCII to Kelvin numbered backreference fold');
ok("s\x{017F}" !~ /^(?<g>s)\k<g>$/iaa,
    'top-level aa rejects ASCII to long-s named backreference fold');
ok("s\x{017F}" !~ /(?iaa:^(?<g>s)\k<g>$)/,
    'scoped aa rejects ASCII to long-s named backreference fold');

ok("\x{00DF}\x{017F}\x{017F}" =~ /^(\x{00DF})\1$/iaa,
    'top-level aa permits sharp-s to two long-s numbered fold');
ok("\x{00DF}\x{017F}\x{017F}" =~ /(?iaa:^(\x{00DF})\1$)/,
    'scoped aa permits sharp-s to two long-s numbered fold');
ok("\x{017F}\x{017F}\x{00DF}" =~ /^(?<g>\x{017F}\x{017F})\k<g>$/iaa,
    'top-level aa permits two long-s to sharp-s named fold');
ok("\x{1E9E}\x{017F}\x{017F}" =~ /(?iaa:^(?<g>\x{1E9E})\k<g>$)/,
    'scoped aa permits capital sharp-s to two long-s named fold');

ok("K\x{212A}" =~ /^(K)\1$/ia,
    'single a retains ASCII to Kelvin backreference folding');
ok("\x{00E9}A\x{00C9}a" =~ /(?iaa:^(\x{00E9}A)\1$)/,
    'aa permits aligned non-ASCII and ASCII folds in a mixed capture');
ok("\x{0149}\x{02BC}N" !~ /(?iaa:^(\x{0149})\1$)/,
    'aa rejects a non-ASCII capture folding to mixed source provenance');
ok("\x{02BC}N\x{0149}" !~ /(?iaa:^(\x{02BC}N)\1$)/,
    'aa rejects mixed captured provenance folding to one non-ASCII target');

my $byte_sharp_s = chr 0xDF;
utf8::downgrade($byte_sharp_s);
my $subject = $byte_sharp_s . "\x{017F}\x{017F}";
ok($subject =~ /(?iaa:^($byte_sharp_s)\1$)/,
    'byte-backed interpolated sharp-s folds to Unicode long-s pair');
utf8::upgrade($byte_sharp_s);
ok($subject =~ /(?iaa:^($byte_sharp_s)\1$)/,
    'upgraded interpolated sharp-s folds to Unicode long-s pair');

done_testing;
