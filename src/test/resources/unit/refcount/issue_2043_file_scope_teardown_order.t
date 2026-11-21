use strict;
use warnings;
use Test::More;

our @destroy_order;

{
    package Issue2043::TeardownOrderProbe;
    sub DESTROY {
        push @main::destroy_order, $_[0]{name};
    }
}

{
    my $scalar_first = bless { name => 'scalar_first' }, 'Issue2043::TeardownOrderProbe';
    my $scalar_second = bless { name => 'scalar_second' }, 'Issue2043::TeardownOrderProbe';
    my %hash_first = (
        value => bless({ name => 'hash_first' }, 'Issue2043::TeardownOrderProbe'),
    );
    my %hash_second = (
        value => bless({ name => 'hash_second' }, 'Issue2043::TeardownOrderProbe'),
    );
    my @array = (
        bless({ name => 'array' }, 'Issue2043::TeardownOrderProbe'),
    );
}

is_deeply(
    \@destroy_order,
    [qw(hash_second hash_first scalar_second scalar_first array)],
    'lexical teardown reverses hash and scalar declaration order before arrays',
);

done_testing();
