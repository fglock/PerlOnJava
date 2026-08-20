use strict;
use warnings;
no warnings 'experimental::vlb';
use Test::More;

my $recursive = 'aa';
ok($recursive =~ /(?<W>a)(?<BB>(?=(?&W))(?<=(?&W)))(?&BB)/,
    'native recursive subpattern determines lookbehind width');
is($&, 'a',
    'recursive lookbehind publishes the expected whole match');

my $error = '';
eval q{qr/(?<WIDE>a{256})(?<=(?&WIDE))/};
$error = $@;
like($error, qr/^Lookbehind longer than 255 not implemented in regex/,
    'native recursive lookbehind still enforces the width ceiling');

done_testing;
