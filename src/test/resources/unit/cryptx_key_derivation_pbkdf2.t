use strict;
use warnings;
use Test::More;

plan skip_all => 'CryptX is optional' unless eval { require Crypt::KeyDerivation; 1 };

is unpack('H*', Crypt::KeyDerivation::pbkdf2('password', 'salt', 1, 'SHA256', 32)),
    '120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b',
    'PBKDF2-HMAC-SHA256 matches the known one-iteration vector';
is length(Crypt::KeyDerivation::pbkdf2('password', 'salt')), 32,
    'default PBKDF2 output is 32 bytes';

done_testing;
