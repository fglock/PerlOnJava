use strict;
use warnings;
use Test::More tests => 1;

my $anchor = qr{\G(?:[\x81-\x9F\xE0-\xFC][\x00-\xFF]|[\x00-\xFF])*?};
my $matched = (('A' x 32768) . 'B') =~ /(?:${anchor}B)/;
pass('deep backtracking returns normally');
