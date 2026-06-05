package PerlOnJava::Distroprefs::CryptX;

use strict;
use warnings;

sub test_phase {
    require Crypt::PRNG;
    Crypt::PRNG->import(qw(
        random_bytes random_bytes_hex random_bytes_b64 random_bytes_b64u
        random_string random_string_from rand irand
    ));

    die "random_bytes length" unless length(random_bytes(16)) == 16;
    die "random_bytes_hex shape" unless random_bytes_hex(4) =~ /\A[0-9a-f]{8}\z/;
    die "random_bytes_b64 length" unless length(random_bytes_b64(6)) == 8;
    die "random_bytes_b64u shape" unless random_bytes_b64u(6) =~ /\A[A-Za-z0-9_-]{8}\z/;
    die "random_string shape" unless random_string(16) =~ /\A[A-Za-z0-9]{16}\z/;
    die "random_string_from shape" unless random_string_from('ABC', 16) =~ /\A[ABC]{16}\z/;
    die "rand range" unless rand(10) >= 0 && rand(10) < 10;
    die "irand range" unless irand() >= 0 && irand() <= 0xFFFFFFFF;

    my $prng = Crypt::PRNG->new('ChaCha20', 'seed');
    $prng->add_entropy('more seed');
    die "oo bytes length" unless length($prng->bytes(8)) == 8;
    die "oo int32 range" unless $prng->int32 >= 0 && $prng->int32 <= 0xFFFFFFFF;

    print "Crypt::PRNG bundled smoke ok\n";
    return 1;
}

1;
