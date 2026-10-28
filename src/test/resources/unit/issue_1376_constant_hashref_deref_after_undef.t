use strict;
use warnings;
use Test::More;

{
    package Local::HashReferenceConstant;

    use constant TABLE => {
        answer => [42],
    };

    sub values_for {
        my ($key) = @_;
        return exists TABLE->{$key} ? @{ TABLE->{$key} } : ();
    }
}

# Perl compiles the constant dereferences into values before runtime removes
# the visible typeglob.  A lazily compiled body must retain that same value.
undef *Local::HashReferenceConstant::TABLE;

my @values = Local::HashReferenceConstant::values_for('answer');
is(scalar @values, 1, 'hash-reference constant remains available after its typeglob is undefined');
is($values[0], 42, 'constant hash dereference returns the original array value');

done_testing;
