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
    my $scalar = bless { name => 'scalar' }, 'Issue2043::TeardownOrderProbe';
    my %hash = (
        value => bless({ name => 'hash' }, 'Issue2043::TeardownOrderProbe'),
    );
    my @array = (
        bless({ name => 'array' }, 'Issue2043::TeardownOrderProbe'),
    );
}

is_deeply(
    \@destroy_order,
    [qw(hash scalar array)],
    'mixed lexical teardown releases hash values before scalars and arrays',
);

done_testing();
