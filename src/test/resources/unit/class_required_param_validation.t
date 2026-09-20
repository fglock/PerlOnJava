use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class RequiredConstructorParam {
    field $required :param;
    field $optional :param = 'optional-default';

    method values { return ($required, $optional) }
}

ok !eval { RequiredConstructorParam->new() },
    'constructor rejects a missing required parameter';
like $@,
    qr/^Required parameter 'required' is missing for "RequiredConstructorParam" constructor at /,
    'missing required parameter reports the constructor and parameter name';

my $object = RequiredConstructorParam->new(required => undef);
is_deeply [$object->values], [undef, 'optional-default'],
    'an explicitly supplied undef satisfies a required parameter';

done_testing;
