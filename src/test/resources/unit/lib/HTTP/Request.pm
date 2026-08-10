package HTTP::Request;

use strict;
use warnings;

sub new {
    my ($class, $method, $uri) = @_;
    return bless {
        method  => $method,
        uri     => HTTP::Request::_URI->new($uri),
        headers => {},
    }, $class;
}

sub uri { $_[0]{uri} }

sub header {
    my ($self, $name, $value) = @_;
    my $key = lc $name;
    $self->{headers}{$key} = $value if @_ > 2;
    return $self->{headers}{$key};
}

package HTTP::Request::_URI;

use strict;
use warnings;

sub new {
    my ($class, $uri) = @_;
    my ($scheme, $authority, $path) =
        $uri =~ m{\A([A-Za-z][A-Za-z0-9+.-]*)://([^/]+)(/[^?#]*)?};
    die "unsupported test URI: $uri" unless defined $scheme;

    my ($host, $port) = $authority =~ /\A(.+):([0-9]+)\z/
        ? ($1, $2)
        : ($authority, undef);
    $port = lc($scheme) eq 'https' ? 443 : 80 unless defined $port;
    return bless {
        scheme => lc($scheme),
        host   => lc($host),
        port   => $port,
        path   => defined($path) && length($path) ? $path : '/',
    }, $class;
}

sub scheme { $_[0]{scheme} }
sub host   { $_[0]{host} }
sub port   { $_[0]{port} }
sub path   { $_[0]{path} }

1;
