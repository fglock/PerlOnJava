#!/usr/bin/perl
use strict;
use warnings;
use Config;
use Test::More;

BEGIN {
    Test::More::plan(skip_all => 'tests PerlOnJava Net::SSLeay implementation')
        unless $Config{archname} =~ /^java-/;
    eval { require Net::SSLeay; Net::SSLeay->import; 1 }
        or do {
            require Test::More;
            Test::More->import;
            Test::More::plan(skip_all => 'Net::SSLeay not available');
        };
}

plan tests => 9;

for my $case (
    [ 0, 'zero-bit RSA key' ],
    [ 512, '512-bit RSA key' ],
    [ 1023, 'sub-2048-bit RSA key' ],
    [ 1024, 'legacy 1024-bit RSA key' ],
    [ 2047, 'RSA key below the 2048-bit minimum' ],
    [ 8193, 'RSA key above the generation limit' ],
    [ 'not-a-number', 'nonnumeric RSA key size' ],
) {
    my ($bits, $description) = @$case;
    my ($key, $completed);
    $completed = eval {
        $key = Net::SSLeay::RSA_generate_key($bits, 65537);
        1;
    };
    ok($completed && !defined($key), "$description returns undef");
}

my $minimumKey = Net::SSLeay::RSA_generate_key(2048, 65537);
ok(defined($minimumKey), '2048-bit RSA generation is accepted at the minimum');
is(Net::SSLeay::RSA_size($minimumKey), 256, 'minimum RSA key size is 2048 bits');
Net::SSLeay::RSA_free($minimumKey);
