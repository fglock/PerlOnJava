use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# Isolate the result-list materialization boundary behind DBIx's
# fire_resultsets path. The direct and single-map cases are controls for the
# nested-map result list, which is then exercised with and without the
# localized random_results collection used by LeakTracer.
{
    package Local::DBICMapDepth::Row;
    sub get_columns { return (id => $_[0]{id}) }

    package Local::DBICMapDepth::ResultSet;
    sub all { return @{ $_[0]{rows} } }
}

sub run_case {
    my ($weak, $mode) = @_;
    my $row = bless { id => 1 }, 'Local::DBICMapDepth::Row';
    my $resultset = bless { rows => [$row] }, 'Local::DBICMapDepth::ResultSet';
    my $collection = {
        resultset => $resultset,
        random_results => [],
    };

    $weak->{$mode} = $row;
    weaken($weak->{$mode});

    my @resultsets = grep {
        ref($_) eq 'Local::DBICMapDepth::ResultSet'
    } values %$collection;

    if ($mode eq 'direct') {
        my @rows = $resultset->all;
    }
    elsif ($mode eq 'single_map') {
        my @rows = map { $_->all } @resultsets;
    }
    elsif ($mode eq 'nested_map') {
        my @rows = map { $_, { $_->get_columns } }
            map { $_->all } @resultsets;
    }
    elsif ($mode eq 'localized_collection') {
        my $fire_resultsets = sub {
            map { $_, { $_->get_columns } }
                map { $_->all } @resultsets;
        };
        {
            local $collection->{random_results};
            push @{ $collection->{random_results} }, $fire_resultsets->();
        }
    }
}

my %weak;
for my $mode (qw(direct single_map nested_map localized_collection)) {
    run_case(\%weak, $mode);

    if (defined $weak{$mode} && defined &Internals::jperl_owner_trace) {
        diag("$mode retained row owner snapshot: "
            . Internals::jperl_owner_trace($weak{$mode}));
    }

    ok(!defined $weak{$mode}, "$mode releases its result row");
}

done_testing();
