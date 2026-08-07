use strict;
use warnings;
use Test::More tests => 3;
use Crypt::Eksblowfish::Bcrypt qw(bcrypt_hash bcrypt);

is(bcrypt_hash({key_nul => 0, cost => 5, salt => 'abcdefghijklmnop'},
        'supercalifragilisticexpialidocious'),
    pack('H*', '1e514c325869b8c311f852ffe630bac51519a66409ed77'),
    'bcrypt raw vector without key NUL');
is(bcrypt_hash({key_nul => 1, cost => 6, salt => 'ABCDEFGHIJKLMNOP'},
        'Libelar! Timmah!'),
    pack('H*', '2b7453cbc43bc27cb59c1a1a2ce520d79557f7a1a17b9b'),
    'bcrypt raw vector with key NUL');
is(bcrypt('U*U', '$2a$05$CCCCCCCCCCCCCCCCCCCCC.'),
    '$2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW',
    'bcrypt crypt vector');
