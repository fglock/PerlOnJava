use strict;
use warnings;
use Test::More tests => 9;

use_ok('Cpanel::JSON::XS');

is( Cpanel::JSON::XS->encode_json( { a => 1 } ), '{"a":1}', 'encode_json object' );

is( Cpanel::JSON::XS::encode_json( [ 1, 2 ] ), '[1,2]', 'encode_json functional' );

is_deeply( Cpanel::JSON::XS::decode_json('{"x":2}'), { x => 2 }, 'decode_json' );

eval { Cpanel::JSON::XS::decode_json( 'null', 1 ) };
ok( !$@, 'decode_json with allow_nonref' ) or diag $@;

eval { Cpanel::JSON::XS::encode_json( [], [] ) };
like( $@, qr/type-aware encode_json/, 'encode_json rejects type second arg' );

use_ok('Cpanel::JSON::XS::Type');
is( Cpanel::JSON::XS::Type::JSON_TYPE_INT, 0x0002, 'JSON_TYPE_INT constant' );

use_ok('Cpanel::JSON::XS::Boolean');
