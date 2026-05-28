use strict;
use warnings;
use Test::More;
use Scalar::Util qw(blessed reftype refaddr);
use Storable qw(freeze thaw);

{
    package StorableTopBlessedScalar;

    sub new {
        my ($class, $value) = @_;
        return bless \$value, $class;
    }
}

my $obj = StorableTopBlessedScalar->new('alive');
my $copy = thaw(freeze($obj));

is(blessed($copy), 'StorableTopBlessedScalar', 'top-level blessed scalar ref keeps its class');
is(reftype($copy), 'SCALAR', 'top-level blessed scalar ref keeps scalar reftype');
is($$copy, 'alive', 'top-level blessed scalar ref keeps referent value');
isnt(refaddr($copy), refaddr($obj), 'top-level blessed scalar ref thaw creates a distinct object');

done_testing();
