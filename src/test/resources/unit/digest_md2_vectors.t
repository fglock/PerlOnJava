use strict;
use warnings;
use Test::More;

eval { require Digest::MD2; Digest::MD2->import(qw(md2 md2_hex md2_base64)); 1 }
    or plan skip_all => 'Digest::MD2 required';

my @cases = (
    [ '' => '8350e5a3e24c153df2275c9f80692773' ],
    [ 'a' => '32ec01ec4a6dac72c0ab96fb34c0b5d1' ],
    [ 'abc' => 'da853b0d3f88d99b30283a69e6ded6bb' ],
    [ 'message digest' => 'ab4f496bfb2a530b219ff33031fe06b0' ],
    [ 'abcdefghijklmnopqrstuvwxyz' => '4e8ddff3650292ab5a4108c3aa47940b' ],
    [ 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789' => 'da33def2a42df13975352846c30338cd' ],
    [ '12345678901234567890123456789012345678901234567890123456789012345678901234567890' => 'd5976f79d83d3a0dc9806c3c66f3efd8' ],
);

plan tests => @cases * 4 + 3;

for my $case (@cases) {
    my ($message, $hex) = @$case;
    my $raw = pack 'H*', $hex;
    is(md2($message), $raw, "md2 raw for '$message'");
    is(md2_hex($message), $hex, "md2_hex for '$message'");
    is(Digest::MD2->new->add($message)->digest, $raw, "OO digest for '$message'");
    is(Digest::MD2->new->add($message)->hexdigest, $hex, "OO hexdigest for '$message'");
}

my $ctx = Digest::MD2->new;
$ctx->add('ab');
my $clone = $ctx->clone;
$ctx->add('c');
$clone->add('c');
is($ctx->hexdigest, 'da853b0d3f88d99b30283a69e6ded6bb', 'clone leaves original state usable');
is($clone->hexdigest, 'da853b0d3f88d99b30283a69e6ded6bb', 'clone copies digest state');
is(Digest::MD2->new->add('abc')->b64digest, md2_base64('abc'), 'base64 OO and function agree');
