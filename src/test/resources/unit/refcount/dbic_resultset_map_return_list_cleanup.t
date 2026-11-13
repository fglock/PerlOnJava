use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# Mirrors DBIx::Class::Util::LeakTracer's resultset firing step. A method
# returns rows in list context, a nested map reads row columns, and the
# temporary result list is discarded when the localized collection field is
# restored. The returned row must not remain rooted by map's temporary $_.
{
    package Local::DBICMap::Row;
    sub get_columns { return (id => $_[0]{id}) }

    package Local::DBICMap::ResultSet;
    sub all { return @{ $_[0]{rows} } }
}

sub fire_resultsets_and_restore {
    my ($weak) = @_;
    my $row = bless { id => 1 }, 'Local::DBICMap::Row';
    my $resultset = bless { rows => [$row] }, 'Local::DBICMap::ResultSet';
    my $base_collection = {
        resultset => $resultset,
        random_results => [],
    };
    $weak->{row} = $row;
    weaken($weak->{row});

    my @resultsets = grep {
        ref($_) eq 'Local::DBICMap::ResultSet'
    } values %$base_collection;
    my $fire_resultsets = sub {
        map { $_, { $_->get_columns } }
            map { $_->all }
                @resultsets;
    };

    {
        local $base_collection->{random_results};
        push @{ $base_collection->{random_results} }, $fire_resultsets->();
    }
}

my %weak;
fire_resultsets_and_restore(\%weak);

if (defined $weak{row} && defined &Internals::jperl_owner_trace) {
    diag('row remains live: ' . Internals::jperl_owner_trace($weak{row}));
    diag('row root path: ' . (Internals::jperl_trace_to($weak{row}) // '<unreachable>'));
}
ok(!defined $weak{row}, 'row is released after resultset enumeration and temporary result cleanup');

done_testing();
