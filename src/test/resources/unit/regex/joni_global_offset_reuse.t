use strict;
use warnings;
use Test::More tests => 3;

my $repetitions = 2_000;
my $subject = "A\x{E9}\x{1F642}" x $repetitions;
my ($matches, $last) = (0, '');

while ($subject =~ /(.)/g) {
    $matches++;
    $last = $1;
}

is($matches, 3 * $repetitions, 'global match visits every Unicode code point');
is($last, "\x{1F642}", 'last capture retains a supplementary code point');
ok(!defined(pos($subject)), 'failed terminal global match resets pos');
