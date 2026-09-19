use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my $ok = eval q{
    class FieldInitializerStrictSelf {
        field $value = $self + 1;
    }
    1;
};

ok(!$ok, 'field initializers do not expose a user lexical $self');
like($@, qr/Global symbol "\$self" requires explicit package name/,
    'strict-vars diagnostic is retained for $self in a field initializer');

done_testing;
