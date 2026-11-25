use strict;
use warnings;
BEGIN { $ENV{PERL_JSON_BACKEND} = 'JSON::backportPP' }
use Test::More;
use JSON;

plan skip_all => 'bundled JSON dispatcher is not loaded' unless $^X =~ /jperl/i;
is(JSON->backend, 'JSON::backportPP', 'backportPP selection remains available');
is($JSON::BackendModulePP, 'JSON::backportPP',
   'backportPP remains the selected PP module');
ok(!defined $JSON::BackendModuleXS, 'backportPP does not claim native XS');
done_testing();
