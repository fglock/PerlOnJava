use strict;
use warnings;
use Test::More;

{
    my $octets = "\xC1\xAF\xC1\xAF\xC1\xB0\xC1\xB3";
    open my $fh, '<:utf8', \$octets or die "Could not open scalar handle: $!";
    ok(read($fh, my $value, length($octets)), 'read malformed UTF-8 octets');
    my $printed = '';
    open my $out, '>:raw', \$printed or die "Could not open output scalar: $!";
    print {$out} $value;
    close $out;
    is(unpack('H*', $printed), 'c1afc1afc1b0c1b3',
        ':utf8 preserves malformed octets for output');
}

{
    my $octets = "\xC4\x80";
    open my $fh, '<:utf8', \$octets or die "Could not open scalar handle: $!";
    ok(read($fh, my $value, length($octets)), 'read valid UTF-8 bytes');
    is(ord($value), 256, ':utf8 decodes a valid multibyte character');
}

done_testing();
