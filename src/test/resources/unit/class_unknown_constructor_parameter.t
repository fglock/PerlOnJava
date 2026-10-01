use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class UnknownConstructorParameter {
    field $known :param;
}

my $instance = eval { UnknownConstructorParameter->new(known => 1, extra => 2); 1 };
ok(!$instance, 'generated constructor rejects an unknown parameter');
like $@,
    qr/^Unrecogni[sz]ed parameters for "UnknownConstructorParameter" constructor: extra at /,
    'unknown parameter error names the constructor and parameter';

my $valid = UnknownConstructorParameter->new(known => undef);
isa_ok($valid, 'UnknownConstructorParameter', 'known parameter remains accepted');

done_testing;
