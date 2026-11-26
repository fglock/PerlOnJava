use strict;
use warnings;
BEGIN { delete $ENV{PERL_JSON_BACKEND} }
use Test::More;
use JSON;

plan skip_all => 'bundled JSON dispatcher is not loaded' unless $^X =~ /jperl/i;
is(JSON->backend, 'JSON::PP', 'default backend remains JSON::PP');
is($JSON::BackendModulePP, 'JSON::PP', 'default PP module remains selected');
ok(!defined $JSON::BackendModuleXS, 'default backend does not claim native XS');
done_testing();
