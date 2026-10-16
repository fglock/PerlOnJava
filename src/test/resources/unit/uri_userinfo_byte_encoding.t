use strict;
use warnings;
use Test::More;

{
    package Local::Neo4jAuth;
    use strict;
    use warnings;
    use URI::Escape ();

    sub userinfo_for_auth {
        my ($auth) = @_;
        my $userid = $auth->{principal} // '';
        my $passwd = $auth->{credentials} // '';
        return join ':', map {
            utf8::encode($_) if utf8::is_utf8($_);
            URI::Escape::uri_escape($_);
        } $userid, $passwd;
    }
}

my $config;
subtest 'auth variations' => sub {
    plan tests => 4;
    $config = { auth => { principal => "\xC4\x80", credentials => '' } };
    ok(!utf8::is_utf8($config->{auth}{principal}),
        'Basic Auth configuration retains an unflagged byte username');
    my $byte_userinfo = Local::Neo4jAuth::userinfo_for_auth($config->{auth});
    is('http://' . $byte_userinfo . '@net.test/', 'http://%C4%80:@net.test/',
        'Basic Auth userinfo preserves the C4 80 byte string');

    my $unicode_userinfo = Local::Neo4jAuth::userinfo_for_auth(
        { principal => '', credentials => "\x{100}" });
    is('http://' . $unicode_userinfo . '@net.test/', 'http://:%C4%80@net.test/',
        'Basic Auth UTF-8 encodes a decoded Unicode password');

    my $ascii_userinfo = Local::Neo4jAuth::userinfo_for_auth(
        { principal => 'user', credentials => 'pass' });
    is('http://' . $ascii_userinfo . '@net.test/', 'http://user:pass@net.test/',
        'Basic Auth leaves ASCII userinfo unchanged');
};

done_testing;
