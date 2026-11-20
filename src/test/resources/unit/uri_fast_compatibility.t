use strict;
use warnings;
use URI::Fast qw(uri uri_split uri_encode uri_decode abs_uri);
use URI::Encode::XS qw(uri_encode_utf8 uri_decode_utf8);
use Test::More;

my $u = uri('https://user:pass@example.com:8443/a%20b?k=one&k=two#frag');
is($u->scheme, 'https', 'scheme accessor');
is($u->host, 'example.com', 'host accessor');
is($u->path, '/a%20b', 'path accessor preserves URI escapes');
is_deeply([$u->path], ['a b'], 'list path accessor decodes segments');
is($u->raw_path, '/a%20b', 'raw path accessor');
is($u->to_string, 'https://user:pass@example.com:8443/a%20b?k=one&k=two#frag', 'stringification preserves raw URI');
is_deeply([$u->param('k')], ['one', 'two'], 'repeated query parameters are retained');
is(uri_decode(uri_encode('a b')), 'a b', 'percent encoding round trip');
is(uri_encode_utf8("\x{2600}"), '%E2%98%80', 'UTF-8 percent encoder');
is(uri_decode_utf8('%E2%98%80'), "\x{2600}", 'UTF-8 percent decoder');
is_deeply([uri_split('https://example.com/p?q#f')],
    ['https', 'example.com', '/p', 'q', 'f'], 'URI split exposes components');
is(abs_uri('../next', 'https://example.com/a/b')->to_string, 'https://example.com/next', 'relative URI resolution');

done_testing;
