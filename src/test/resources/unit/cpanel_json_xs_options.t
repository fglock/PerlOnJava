use strict;
use warnings;
use Test::More;
use Cpanel::JSON::XS;

my $json = Cpanel::JSON::XS->new;

is($json->stringify_infnan, $json, 'stringify_infnan chains');
is($json->escape_slash, $json, 'escape_slash chains');
is($json->allow_dupkeys, $json, 'allow_dupkeys chains');

done_testing;
