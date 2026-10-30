package CryptX;

use strict;
use warnings;
our $VERSION = '0.091';

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

use Carp;
my $has_json;

BEGIN {
    $has_json = 1 if eval { require JSON; 1 };
}

sub _croak {
    die @_ if ref $_[0] || !$_[-1];
    if ($_[-1] =~ /^(.*)( at .+ line .+\n$)/s) {
        pop @_;
        push @_, $1;
    }
    die Carp::shortmess @_;
}

sub _decode_json {
    croak "FATAL: cannot find JSON module" if !$has_json;
    return JSON->new->utf8->decode(shift);
}

sub _encode_json {
    croak "FATAL: cannot find JSON module" if !$has_json;
    return JSON->new->utf8->canonical->encode(shift);
}

1;

__END__

=head1 NAME

CryptX - Cryptographic toolkit bootstrap for PerlOnJava

=head1 DESCRIPTION

This file preserves the CryptX bootstrap and pure-Perl helpers. The supported
cryptographic entry points are provided by PerlOnJava's Java XSLoader bridge.

=head1 COPYRIGHT AND LICENSE

CryptX is Copyright (c) 2013-2026 DCIT, a.s. / Karel Miko.
This file is distributed under the same terms as Perl itself.

=cut
