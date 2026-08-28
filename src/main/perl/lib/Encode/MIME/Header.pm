package Encode::MIME::Header;
use strict;
use warnings;

use Encode ();
use MIME::Base64 ();

our $VERSION = '2.28';
our @ISA = qw(Encode::Encoding);

my %defaults = (
    decode_b => 1,
    decode_q => 1,
    encode   => 'B',
    charset  => 'UTF-8',
    bpl      => 75,
);

my @encodings = (
    bless({ %defaults, Name => 'MIME-Header' }, __PACKAGE__),
    bless({ %defaults, Name => 'MIME-B', decode_q => 0 }, __PACKAGE__),
    bless({ %defaults, Name => 'MIME-Q', decode_b => 0, encode => 'Q' }, __PACKAGE__),
);

Encode::define_encoding($_, $_->{Name}) for @encodings;

sub needs_lines { 1 }
sub perlio_ok   { 0 }

sub decode {
    my ($self, $text, $check) = @_;
    return undef unless defined $text;

    # RFC 2047 says linear whitespace between adjacent encoded words is not
    # displayed.  Normalize it before decoding the individual words.
    $text =~ s{(=\?[^?\s]+\?[BbQq]\?[^?]*\?=)(?:[ \t\r\n]+)(?==\?)}{$1}g;

    $text =~ s{
        (=\?([^?\s]+)(?:\*[A-Za-z0-9-]+)?\?([BbQq])\?([^?]*)\?=)
    }{
        _decode_word($self, $1, $2, $3, $4, $check)
    }egx;

    return $text;
}

sub _decode_word {
    my ($self, $original, $charset, $kind, $payload, $check) = @_;
    return $original if uc($kind) eq 'B' && !$self->{decode_b};
    return $original if uc($kind) eq 'Q' && !$self->{decode_q};

    my $encoding = Encode::find_mime_encoding($charset)
        || Encode::find_encoding($charset);
    return $original unless defined $encoding;

    my $octets;
    if (uc($kind) eq 'B') {
        $octets = MIME::Base64::decode($payload);
    } else {
        ($octets = $payload) =~ tr/_/ /;
        $octets =~ s/=([0-9A-Fa-f]{2})/pack('C', hex($1))/eg;
    }

    my $decoded = eval { $encoding->decode($octets, $check || 0) };
    return defined $decoded ? $decoded : $original;
}

sub encode {
    my ($self, $text, $check) = @_;
    return undef unless defined $text;

    my $encoding = Encode::find_mime_encoding($self->{charset})
        || Encode::find_encoding($self->{charset});
    return $text unless defined $encoding;

    my $octets = $encoding->encode($text, $check || 0);
    if ($self->{encode} eq 'Q') {
        $octets =~ s/([^A-Za-z0-9!*+\/-])/sprintf('=%02X', ord($1))/eg;
        $octets =~ tr/ /_/;
        return '=?' . $encoding->mime_name . '?Q?' . $octets . '?=';
    }

    return '=?' . $encoding->mime_name . '?B?'
        . MIME::Base64::encode_base64($octets, '') . '?=';
}

1;
