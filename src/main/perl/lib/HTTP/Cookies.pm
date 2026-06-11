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

1;
