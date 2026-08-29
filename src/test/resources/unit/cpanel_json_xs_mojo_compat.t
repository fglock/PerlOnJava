use strict;
use warnings;

use Cpanel::JSON::XS;
use Test::More tests => 5;

{
    package Local::JSON::Stringified;
    use overload '""' => sub { 'works!' }, fallback => 1;
    sub new { bless {}, shift }
}

{
    package Local::JSON::Converted;
    sub new { bless {}, shift }
    sub TO_JSON { return {converted => 1} }
}

my $json = Cpanel::JSON::XS->new->utf8->canonical->allow_nonref
  ->allow_unknown->allow_blessed->convert_blessed->stringify_infnan
  ->escape_slash->allow_dupkeys;

is($json->encode(Local::JSON::Stringified->new), '"works!"',
   'configured encoder stringifies overloaded blessed objects');
is($json->encode(Local::JSON::Converted->new), '{"converted":1}',
   'configured encoder converts objects with TO_JSON');
is($json->encode(bless({}, 'Local::JSON::Plain')), 'null',
   'configured encoder maps other blessed objects to null');
like($json->encode({value => 9**9**9}), qr/^\{"value":".*"\}$/,
   'stringify_infnan quotes infinity');
is($json->encode('/test/123'), '"\\/test\\/123"',
   'escape_slash escapes slashes in strings');
