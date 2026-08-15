package HTTP::Cookies;

use strict;
use warnings;
use HTTP::Date ();

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

sub add_cookie_header {
    my ($self, $request) = @_;
    return unless $request;

    my $uri = $request->uri;
    my $scheme = lc($uri->scheme || '');
    return unless $scheme eq 'http' || $scheme eq 'https';

    my $host = lc($uri->host || '');
    $host .= '.local' unless $host =~ /\./;
    my $request_path = $uri->path;
    $request_path = '/' unless defined($request_path) && length($request_path);
    my $request_port = $uri->port;
    my $now = time;
    my @values;
    my $set_version;

    for my $domain (sort keys %{ $self->{COOKIES} }) {
        my $match_domain = lc $domain;
        $match_domain =~ s/^\.//;
        next unless $host eq $match_domain || $host =~ /\.\Q$match_domain\E\z/;

        my $paths = $self->{COOKIES}{$domain};
        for my $path (sort { length($b) <=> length($a) } keys %$paths) {
            next unless index($request_path, $path) == 0;
            for my $key (sort keys %{ $paths->{$path} }) {
                my ($version, $value, $port, $path_spec, $secure, $expires)
                    = @{ $paths->{$path}{$key} };
                next if $secure && $scheme ne 'https';
                next if $expires && $expires < $now;
                if (defined $port) {
                    $port =~ s/^_//;
                    my %allowed = map { $_ => 1 } split /,/, $port;
                    next unless $allowed{$request_port};
                }

                if (!$set_version++) {
                    if ($version && $version >= 1) {
                        push @values, "\$Version=$version";
                    }
                    elsif (!$self->{hide_cookie2}) {
                        $request->header(Cookie2 => '\$Version="1"');
                    }
                }

                if ($version && $value =~ /\W/) {
                    $value =~ s/([\\"])/\\$1/g;
                    $value = qq("$value");
                }
                push @values, "$key=$value";
                if ($version && $version >= 1) {
                    push @values, qq(\$Path="$path") if $path_spec;
                    push @values, qq(\$Domain="$domain") if $domain =~ /^\./;
                }
            }
        }
    }

    if (@values) {
        my $old = $request->header('Cookie');
        unshift @values, $old if defined($old) && length($old);
        $request->header(Cookie => join('; ', @values));
    }
    return $request;
}

sub set_cookie {
    my ($self, $version, $key, $val, $path, $domain, $port,
        $path_spec, $secure, $maxage, $discard) = @_;
    $domain = defined($domain) && length($domain) ? $domain : '.local';
    $path = defined($path) && length($path) ? $path : '/';
    if (defined($maxage) && $maxage <= 0) {
        delete $self->{COOKIES}{$domain}{$path}{$key};
        return $self;
    }
    my $expires = defined($maxage) ? time + $maxage : undef;
    $self->{COOKIES}{$domain}{$path}{$key} = [
        $version, $val, $port, $path_spec, $secure, $expires, $discard
    ];
    return $self;
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

sub _join_header_words {
    my @cur = @{ $_[0] };
    my @attributes;
    while (@cur) {
        my $key = shift @cur;
        my $value = shift @cur;
        if (defined $value) {
            if (!length($value)
                || $value =~ /[\x00-\x20()<>@,;:\\"\/\[\]?={}\x7f-\xff]/) {
                $value =~ s/(["\\])/\\$1/g;
                $key .= qq(="$value");
            }
            else {
                $key .= "=$value";
            }
        }
        push @attributes, $key;
    }
    return join '; ', @attributes;
}

sub as_string {
    my ($self, $skip_discard) = @_;
    my @result;

    $self->scan(sub {
        my ($version, $key, $value, $path, $domain, $port,
            $path_spec, $secure, $expires, $discard, $rest) = @_;
        return if $discard && $skip_discard;

        my @header = ($key, $value, path => $path, domain => $domain);
        push @header, port => $port if defined $port;
        push @header, path_spec => undef if $path_spec;
        push @header, secure => undef if $secure;
        push @header, expires => HTTP::Date::time2isoz($expires) if $expires;
        push @header, discard => undef if $discard;
        for my $attribute (sort keys %{ $rest || {} }) {
            push @header, $attribute => $rest->{$attribute};
        }
        push @header, version => $version;
        push @result, 'Set-Cookie3: ' . _join_header_words(\@header);
    });

    return join "\n", @result, '';
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
