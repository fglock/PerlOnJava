package HTTP::Cookies;

use strict;
use warnings;

our $VERSION = '6.11';
our $EPOCH_OFFSET = 0;

sub new {
    my $class = shift;
    my $self = bless { COOKIES => {} }, $class;
    my %cnf = @_;
    for my $key (keys %cnf) {
        $self->{lc $key} = $cnf{$key};
    }
    $self->load;
    return $self;
}

sub load { return 1 }

sub save { return 1 }

sub set_cookie {
    my ($self, $version, $key, $val, $path, $domain, $port,
        $path_spec, $secure, $maxage, $discard) = @_;
    $domain = defined($domain) && length($domain) ? $domain : '.local';
    $path = defined($path) && length($path) ? $path : '/';
    $self->{COOKIES}{$domain}{$path}{$key} = [
        $version, $val, $port, $path_spec, $secure, $maxage, $discard
    ];
    return 1;
}

sub scan {
    my ($self, $callback) = @_;
    return unless $callback;
    for my $domain (sort keys %{ $self->{COOKIES} }) {
        for my $path (sort keys %{ $self->{COOKIES}{$domain} }) {
            for my $key (sort keys %{ $self->{COOKIES}{$domain}{$path} }) {
                my $cookie = $self->{COOKIES}{$domain}{$path}{$key};
                my ($version, $val, $port, $path_spec, $secure, $expires, $discard) = @$cookie;
                $callback->($version, $key, $val, $path, $domain, $port,
                    $path_spec, $secure, $expires, $discard, {});
            }
        }
    }
    return 1;
}

sub extract_cookies {
    my ($self, $response) = @_;
    return unless $response;

    my @set = $response->header('Set-Cookie');
    return $response unless @set;

    my $request = $response->request;
    my $uri = $request && $request->uri;
    my $host = $uri ? $uri->host : undef;
    $host = 'localhost' unless defined $host && length $host;
    $host .= '.local' unless $host =~ /\./;

    for my $line (@set) {
        next unless defined $line;
        my @parts = split /\s*;\s*/, $line;
        my ($key, $value) = split /=/, shift(@parts), 2;
        next unless defined $key && length $key && defined $value;

        my %attr;
        for my $part (@parts) {
            my ($name, $attr_value) = split /=/, $part, 2;
            next unless defined $name;
            $attr{lc $name} = defined $attr_value ? $attr_value : 1;
        }

        my $domain = defined $attr{domain} && length $attr{domain}
            ? lc $attr{domain} : $host;
        my $path = defined $attr{path} && length $attr{path}
            ? $attr{path} : '/';
        $self->set_cookie(
            0, $key, $value, $path, $domain, undef,
            exists($attr{path}) ? 1 : 0,
            exists($attr{secure}) ? 1 : 0,
            $attr{'max-age'}, 1,
        );
    }

    return $response;
}

1;
