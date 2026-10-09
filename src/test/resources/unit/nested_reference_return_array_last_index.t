use strict;
use warnings;
use Test::More;

sub generate_query {
    my @bind = (['column', 'Blue'], ['column', 'Cheesy']);
    return ('(SELECT ?)', \@bind);
}

sub pack_query {
    my ($sql, $bind) = generate_query();
    unshift @$bind, $sql;
    return \$bind;
}

sub resultset_as_query {
    my $attrs = {};
    my $query = pack_query();
    $query;
}

my $query = resultset_as_query();
is(ref($query), 'REF', 'query is a reference to a scalar');
is(ref($$query), 'ARRAY', 'query scalar contains the packed array');
is_deeply([1 .. $#$$query], [1, 2], 'range over the returned packed query reaches every bind');

done_testing;
