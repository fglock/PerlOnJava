use strict;
use warnings;
use Test::More tests => 12;
use HTTP::Cookies;
use HTTP::Date ();

{
    package Local::CookieURI;
    sub new { bless {}, shift }
    sub host { 'session.example.test' }
    sub port { 80 }
    sub path { '/' }

    package Local::CookieRequest;
    sub new { bless { uri => Local::CookieURI->new }, shift }
    sub uri { $_[0]{uri} }

    package Local::CookieResponse;
    sub new {
        bless {
            request => Local::CookieRequest->new,
            headers => { 'Set-Cookie' => $_[1] },
        }, shift;
    }
    sub request { $_[0]{request} }
    sub header { $_[0]{headers}{$_[1]} }
}

my $expires = HTTP::Date::time2str(time + 3600);
my $response = Local::CookieResponse->new(
    "session=token; Path=/app; Expires=$expires; SameSite=Strict; Secure",
);
my $jar = HTTP::Cookies->new;
is($jar->extract_cookies($response), $response,
    'extract_cookies returns the response');

my @cookies;
$jar->scan(sub { @cookies = @_ });
is($cookies[1], 'session', 'extracts the cookie name');
is($cookies[2], 'token', 'extracts the cookie value');
is($cookies[3], '/app', 'retains the cookie path');
cmp_ok($cookies[8], '>', time, 'retains Expires as a future expiration');
is($cookies[10]{SameSite}, 'Strict', 'retains SameSite in extension attributes');
ok($cookies[7], 'retains the Secure attribute');

$jar->set_cookie(0, 'other', 'value', '/', 'other.example.test');
is($jar->clear('session.example.test'), $jar,
    'clear returns the cookie jar');
my @remaining;
$jar->scan(sub { push @remaining, $_[1] });
is_deeply(\@remaining, ['other'], 'clear(domain) removes only that domain');

$jar->set_cookie(0, 'first', 'value', '/sub', 'other.example.test');
$jar->set_cookie(0, 'second', 'value', '/sub', 'other.example.test');
$jar->clear('other.example.test', '/sub');
my @after_path_clear;
$jar->scan(sub { push @after_path_clear, $_[1] });
is_deeply(\@after_path_clear, ['other'],
    'clear(domain, path) removes cookies from only that path');

$jar->set_cookie(0, 'first', 'value', '/', 'other.example.test');
$jar->set_cookie(0, 'second', 'value', '/', 'other.example.test');
$jar->clear('other.example.test', '/', 'first');
my @after_key_clear;
$jar->scan(sub { push @after_key_clear, $_[1] });
is_deeply(\@after_key_clear, ['other', 'second'],
    'clear(domain, path, key) removes only that cookie');

$jar->clear;
my @after_clear;
$jar->scan(sub { push @after_clear, $_[1] });
is_deeply(\@after_clear, [], 'clear() removes every cookie');
