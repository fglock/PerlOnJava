use strict;
use warnings;
use Test::More tests => 2;

my $packed = pack 'U', 0xFF;

{
    use bytes;
    ok($packed eq "\xC3\xBF", 'pack U exposes UTF-8 octets under use bytes');
    is(length($packed), 2, 'pack U retains its UTF-8 flag under use bytes');
}
