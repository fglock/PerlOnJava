use strict;
use warnings;
use Test::More;

use Cpanel::JSON::XS;
use JSON::PP ();

# Neo4j::Driver converts decoded boolean properties through builtin::false
# when both response representations are identified as booleans.
my $json = Cpanel::JSON::XS->new->allow_nonref;
my $decoded_false = $json->decode('false');
my $neo4j_false = $decoded_false ? builtin::true() : builtin::false();

is $neo4j_false, JSON::PP::false,
    'converted Neo4j false property compares equal to JSON::PP::false';

my $decoded_true = $json->decode('true');
my $neo4j_true = $decoded_true ? builtin::true() : builtin::false();
is $neo4j_true, JSON::PP::true,
    'converted Neo4j true property compares equal to JSON::PP::true';

done_testing;
