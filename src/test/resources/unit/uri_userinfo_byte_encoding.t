use strict;
use warnings;
use Test::More;
use URI;

{
    package Local::Neo4jAuth;
    use strict;
    use warnings;
    use URI::Escape ();

    sub uri_with_auth {
        my ($uri, $auth) = @_;
        my $userid = $auth->{principal} // '';
        my $passwd = $auth->{credentials} // '';
        my $userinfo = join ':', map {
            utf8::encode($_) if utf8::is_utf8($_);
            URI::Escape::uri_escape($_);
        } $userid, $passwd;
        $uri = $uri->clone;
        $uri->userinfo($userinfo);
        return $uri;
    }
}

my $config;
subtest 'auth variations' => sub {
    plan tests => 4;
    my $base = URI->new('http://net.test/');
    $config = { auth => { principal => "\xC4\x80", credentials => '' } };
    ok(!utf8::is_utf8($config->{auth}{principal}),
        'Basic Auth configuration retains an unflagged byte username');
    my $byte_uri = Local::Neo4jAuth::uri_with_auth($base, $config->{auth});
    is($byte_uri->as_string, 'http://%C4%80:@net.test/',
        'Basic Auth userinfo preserves the C4 80 byte string through URI cloning');

    my $unicode_uri = Local::Neo4jAuth::uri_with_auth($base,
        { principal => '', credentials => "\x{100}" });
    is($unicode_uri->as_string, 'http://:%C4%80@net.test/',
        'Basic Auth UTF-8 encodes a decoded Unicode password');

    my $ascii_uri = Local::Neo4jAuth::uri_with_auth($base,
        { principal => 'user', credentials => 'pass' });
    is($ascii_uri->as_string, 'http://user:pass@net.test/',
        'Basic Auth leaves ASCII userinfo unchanged');
};

done_testing;
