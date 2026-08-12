use strict;
use warnings;
use Test::More;

BEGIN {
    if (!eval { require Convert::Bencode_XS; 1 }) {
        if ($^X =~ /jperl/) {
            require XSLoader;
            XSLoader::load('Convert::Bencode_XS', '0.06');
            $Convert::Bencode_XS::COERCE = 1;
        }
        else {
            plan skip_all => 'Convert::Bencode_XS required';
        }
    }
}

is Convert::Bencode_XS::bencode(12), 'i12e', 'encodes integer';
is Convert::Bencode_XS::bencode('test'), '4:test', 'encodes string';
is Convert::Bencode_XS::bencode({ b => 2, a => 1 }), 'd1:ai1e1:bi2ee', 'sorts dictionary keys by bytes';
is_deeply Convert::Bencode_XS::bdecode('d1:ali1e1:xe1:bi2ee'), { a => [1, 'x'], b => 2 }, 'decodes nested values';
ok !eval { Convert::Bencode_XS::bdecode('4:tes'); 1 }, 'rejects short string';
ok !eval { Convert::Bencode_XS::bdecode('i12'); 1 }, 'rejects unterminated integer';

done_testing;
