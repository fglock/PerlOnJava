package Crypt::Eksblowfish::Bcrypt;
use strict;
use warnings;
use Carp qw(croak);
use Exporter 'import';
use MIME::Base64 2.21 qw(encode_base64 decode_base64);

our $VERSION = '0.009';
our @EXPORT_OK = qw(bcrypt_hash en_base64 de_base64 bcrypt);

use XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

sub bcrypt_hash($$) {
    my ($settings, $password) = @_;
    return _bcrypt_hash_java(!!$settings->{key_nul}, $settings->{cost},
        $settings->{salt}, $password);
}

sub en_base64($) {
    my $text = encode_base64($_[0], '');
    $text =~ tr#A-Za-z0-9+/=#./A-Za-z0-9#d;
    return $text;
}

sub de_base64($) {
    my $text = $_[0];
    croak 'bad base64 encoding' unless $text =~ m#\A(?>(?:[./A-Za-z0-9]{4})*)(?:|[./A-Za-z0-9]{2}[.CGKOSWaeimquy26]|[./A-Za-z0-9][.Oeu])\z#x;
    $text =~ tr#./A-Za-z0-9#A-Za-z0-9+/#;
    $text .= '=' x (3 - (length($text) + 3) % 4);
    return decode_base64($text);
}

sub bcrypt($$) {
    my ($password, $settings) = @_;
    croak 'bad bcrypt settings' unless $settings =~ m#\A\$2(a?)\$([0-9]{2})\$([./A-Za-z0-9]{22})#;
    my ($key_nul, $cost, $salt64) = ($1, $2, $3);
    my $hash = bcrypt_hash({key_nul => $key_nul, cost => $cost, salt => de_base64($salt64)}, $password);
    return "\$2${key_nul}\$${cost}\$${salt64}" . en_base64($hash);
}

1;
