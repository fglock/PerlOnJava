use strict;
use warnings;
BEGIN { $ENV{PERL_JSON_BACKEND} = 'JSON::XS' }
use Test::More;
use JSON;

plan skip_all => 'bundled JSON dispatcher is not loaded' unless $^X =~ /jperl/i;
plan tests => 6;

my $backend = JSON->backend;
ok($backend eq 'JSON::XS' || $backend eq 'JSON::Backend::XS',
   'dispatcher reports the requested XS-compatible backend');
ok($backend->isa('JSON::XS'), 'selected backend has the JSON::XS API');
is($backend->is_xs, $backend eq 'JSON::XS' ? 0 : 1,
   'native XS status matches the selected implementation');
ok(!defined $JSON::BackendModuleXS,
   'pure-Perl JSON::XS adapter does not claim a native XS module');
is(JSON::encode_json({ answer => 42 }), '{"answer":42}',
   'selected backend encodes through its compatibility implementation');
is_deeply(JSON::decode_json('{"answer":42}'), { answer => 42 },
          'selected backend decodes through its compatibility implementation');
