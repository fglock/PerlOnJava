use strict;
use warnings;
use Test::More tests => 2;
use HTTP::Cookies;
use HTTP::Date ();

{
    package Local::ExpiresURI;
    sub host { 'session.example.test' }

    package Local::ExpiresRequest;
    sub new { bless {}, shift }
    sub uri { bless {}, 'Local::ExpiresURI' }
    sub header {
        my ($self, $name, $value) = @_;
        $self->{headers}{$name} = $value if @_ > 2;
        return $self->{headers}{$name};
    }

    package Local::ExpiresResponse;
    sub new {
        bless {
            request => Local::ExpiresRequest->new,
            headers => { 'Set-Cookie' => $_[1] },
        }, shift;
    }
    sub request { $_[0]{request} }
    sub header { $_[0]{headers}{$_[1]} }
    sub _header { $_[0]{headers}{$_[1]} }
}

my $jar = HTTP::Cookies->new;
$jar->set_cookie(0, 'session', 'old', '/', 'session.example.test');
my $expired = Local::ExpiresResponse->new(
    'session=gone; Path=/; Expires=' . HTTP::Date::time2str(time - 3600),
);
$jar->extract_cookies($expired);
my @cookies;
$jar->scan(sub { push @cookies, $_[1] });
is_deeply(\@cookies, [], 'Expires in the past deletes an existing cookie');

my $max_age = Local::ExpiresResponse->new(
    'persistent=value; Path=/; Max-Age=3600',
);
$jar->extract_cookies($max_age);
$jar->scan(sub { push @cookies, $_[1] });
is_deeply(\@cookies, ['persistent'], 'Max-Age still retains a live cookie');
