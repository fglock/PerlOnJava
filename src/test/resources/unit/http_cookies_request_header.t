use strict;
use warnings;

use Test::More tests => 6;
use HTTP::Cookies;
use HTTP::Request;

my $jar = HTTP::Cookies->new;
$jar->set_cookie(1, 'who', 'cookie_man', '/', 'example.test');
$jar->set_cookie(0, 'section', 'news', '/news', 'example.test');
$jar->set_cookie(0, 'secure', 'yes', '/', 'example.test', undef, 0, 1);

my $request = HTTP::Request->new(GET => 'http://example.test/news/item');
is($jar->add_cookie_header($request), $request,
    'add_cookie_header returns the request');
like($request->header('Cookie'), qr/(?:^|; )who=cookie_man(?:;|$)/,
    'domain cookie is added');
like($request->header('Cookie'), qr/(?:^|; )section=news(?:;|$)/,
    'matching path cookie is added');
unlike($request->header('Cookie'), qr/secure=yes/,
    'secure cookie is omitted from HTTP');

my $other = HTTP::Request->new(GET => 'http://other.test/news/item');
$jar->add_cookie_header($other);
is($other->header('Cookie'), undef, 'cookie is omitted for another domain');

my $https = HTTP::Request->new(GET => 'https://example.test/elsewhere');
$jar->add_cookie_header($https);
like($https->header('Cookie'), qr/(?:^|; )secure=yes(?:;|$)/,
    'secure cookie is added to HTTPS');
