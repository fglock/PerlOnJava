use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr);
use Clone qw(clone);
use Storable qw(dclone);

subtest 'Clone handles self-referential scalar refs' => sub {
    my $scalar = 'Scalar';
    $scalar = \$scalar;

    my $copy = clone($scalar);

    isnt(refaddr($copy), refaddr($scalar), 'clone returns a distinct scalar ref');
    is(refaddr($$copy), refaddr($copy), 'cloned scalar still points to itself');
};

subtest 'Storable dclone handles self-referential scalar refs' => sub {
    my $scalar = 3.141;
    $scalar = \$scalar;

    my $copy = dclone($scalar);

    isnt(refaddr($copy), refaddr($scalar), 'dclone returns a distinct scalar ref');
    is(refaddr($$copy), refaddr($copy), 'dcloned scalar still points to itself');
};

subtest 'scalar ref clones are independent' => sub {
    my $value = 42;
    my $ref = \$value;

    for my $cloner (
        [ Clone => \&clone ],
        [ Storable => \&dclone ],
    ) {
        my ($name, $code) = @$cloner;
        my $copy = $code->($ref);

        isnt(refaddr($copy), refaddr($ref), "$name returns a distinct scalar ref");
        is($$copy, 42, "$name preserves scalar referent value");
        $$copy = 99;
        is($value, 42, "$name clone is independent of original scalar");
    }
};

done_testing;
