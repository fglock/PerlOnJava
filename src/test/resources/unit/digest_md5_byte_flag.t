use strict;
use warnings;

use Digest::MD5 qw(md5);
use Encode qw(is_utf8);
use Test::More;

for my $digest (
    md5('x'),
    Digest::MD5->new->add('x')->digest,
) {
    ok(!is_utf8($digest), 'raw MD5 digest has the UTF-8 flag off');
    is(length($digest), 16, 'raw MD5 digest contains 16 characters');
    {
        use bytes;
        is(length($digest), 16, 'raw MD5 digest contains 16 octets');
    }
}

done_testing;
