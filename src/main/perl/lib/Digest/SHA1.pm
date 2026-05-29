package Digest::SHA1;

use strict;
use warnings;
use Digest::SHA ();
use Exporter ();

our $VERSION = '2.13';
our @ISA = qw(Exporter Digest::SHA);
our @EXPORT_OK = qw(sha1 sha1_hex sha1_base64 sha1_transform);

sub new {
    my ($class) = @_;
    return $class->reset if ref $class;

    my $self = Digest::SHA->new(1);
    bless $self, $class;
    return $self;
}

sub sha1        { Digest::SHA::sha1(@_) }
sub sha1_hex    { Digest::SHA::sha1_hex(@_) }
sub sha1_base64 { Digest::SHA::sha1_base64(@_) }

sub sha1_transform {
    my ($data) = @_;
    $data = '' unless defined $data;
    my $block = substr($data . ("\0" x 64), 0, 64);
    my @w = unpack('N16', $block);

    for my $i (16 .. 79) {
        $w[$i] = _rol($w[$i - 3] ^ $w[$i - 8] ^ $w[$i - 14] ^ $w[$i - 16], 1);
    }

    my ($a, $b, $c, $d, $e) = (
        0x67452301,
        0xefcdab89,
        0x98badcfe,
        0x10325476,
        0xc3d2e1f0,
    );

    for my $i (0 .. 79) {
        my ($f, $k);
        if ($i < 20) {
            $f = ($b & $c) | ((~$b) & $d);
            $k = 0x5a827999;
        }
        elsif ($i < 40) {
            $f = $b ^ $c ^ $d;
            $k = 0x6ed9eba1;
        }
        elsif ($i < 60) {
            $f = ($b & $c) | ($b & $d) | ($c & $d);
            $k = 0x8f1bbcdc;
        }
        else {
            $f = $b ^ $c ^ $d;
            $k = 0xca62c1d6;
        }

        my $temp = (_rol($a, 5) + $f + $e + $k + $w[$i]) & 0xffffffff;
        $e = $d;
        $d = $c;
        $c = _rol($b, 30);
        $b = $a;
        $a = $temp;
    }

    return pack(
        'N5',
        (0x67452301 + $a) & 0xffffffff,
        (0xefcdab89 + $b) & 0xffffffff,
        (0x98badcfe + $c) & 0xffffffff,
        (0x10325476 + $d) & 0xffffffff,
        (0xc3d2e1f0 + $e) & 0xffffffff,
    );
}

sub _rol {
    my ($x, $n) = @_;
    $x &= 0xffffffff;
    return (($x << $n) | ($x >> (32 - $n))) & 0xffffffff;
}

1;
