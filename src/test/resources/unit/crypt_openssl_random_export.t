use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Crypt::OpenSSL::Random; 1 }
        or plan skip_all => 'Crypt::OpenSSL::Random required';
    Crypt::OpenSSL::Random->import('random_bytes');
}

plan tests => 1;

is(length(random_bytes(32)), 32, 'random_bytes can be imported on request');
