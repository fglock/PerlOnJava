use strict;
use warnings;
use utf8;
use Test::More;

for my $non_final ('t', 'ft', 'ift', 'sift') {
    for my $upgraded (0, 1) {
        my $head = ('b' x 120) . "\x{00dc}";
        my $pattern = $non_final . 'enKalt';
        utf8::upgrade($pattern) if $upgraded;
        $pattern = $head . $pattern;
        my $reference = $pattern;

        ok($reference =~ /$pattern/i,
            "non-final reverse-fold component '$non_final' matches"
                . ($upgraded ? ' in an upgraded pattern' : ' in a byte pattern'));
    }
}

my $ordinary = ('b' x 120) . "\x{00dc}ordinaryliteral";
ok($ordinary =~ /$ordinary/i, 'ordinary literal control matches');
ok($ordinary =~ /\Q$ordinary\E/i,
    'quoted ordinary literal is equivalent to the optimizer candidate');

done_testing;
