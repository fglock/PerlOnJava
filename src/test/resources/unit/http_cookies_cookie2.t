#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use HTTP::Cookies;
use HTTP::Request;

my $cookies = HTTP::Cookies->new;
$cookies->set_cookie(0, 'X_TEST', 'MyCookie', '/', 'myhost.local');

my $request = HTTP::Request->new(POST => 'http://myhost/');
$cookies->add_cookie_header($request);

is($request->header('Cookie2'), '$Version="1"',
    'Cookie2 advertises RFC 2965 support without a literal escape');

done_testing;
