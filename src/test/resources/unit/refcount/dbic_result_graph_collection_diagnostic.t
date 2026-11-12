use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
use Storable ();

# Isolate ownership cleanup for the graph shape behind DBIx t/52leaks.t:
# Schema owns Storage and ResultSources, while rows own their sources and one
# row can reference another. Weak backrefs keep the fixture acyclic. This omits
# DBIx DESTROY behavior so a failure points at base graph ownership; on
# PerlOnJava, each retained node reports its owner trace before its assertion.
sub make_dbic_style_graph {
    my $schema = bless { source_registrations => {} }, 'Local::DBICGraph::Schema';
    my $storage = bless {}, 'Local::DBICGraph::Storage';
    my $dbh = bless { driver_name => 'SQLite' }, 'Local::DBICGraph::DBH';
    my $artist_source = bless { name => 'Artist', schema => $schema },
        'Local::DBICGraph::ResultSource';
    my $cd_source = bless { name => 'CD', schema => $schema },
        'Local::DBICGraph::ResultSource';
    my $artist = bless { result_source => $artist_source, cds => [] },
        'Local::DBICGraph::Row';
    my $cd = bless { result_source => $cd_source, artist => $artist },
        'Local::DBICGraph::Row';

    $storage->{schema} = $schema;
    weaken($storage->{schema});
    weaken($artist_source->{schema});
    weaken($cd_source->{schema});
    $storage->{dbh} = $dbh;
    $schema->{storage} = $storage;
    $schema->{source_registrations}{Artist} = $artist_source;
    $schema->{source_registrations}{CD} = $cd_source;
    return {
        schema        => $schema,
        storage       => $storage,
        dbh           => $dbh,
        artist_source => $artist_source,
        cd_source     => $cd_source,
        artist        => $artist,
        cd            => $cd,
        rows          => [$artist, $cd],
    };
}

my @nodes = qw(schema storage dbh artist_source cd_source artist cd);
my %weak;
{
    my $original = make_dbic_style_graph();
    my $clone = Storable::dclone($original);
    my $double_clone = Storable::dclone($clone);

    my %graphs = (
        original     => $original,
        clone        => $clone,
        double_clone => $double_clone,
    );
    for my $label (qw(original clone double_clone)) {
        my $graph = $graphs{$label};
        for my $node (@nodes) {
            my $key = "$label/$node";
            $weak{$key} = $graph->{$node};
            weaken($weak{$key});
        }
    }
}

for my $generation (qw(original clone double_clone)) {
    for my $node (@nodes) {
        my $key = "$generation/$node";
        if (defined $weak{$key} && defined &Internals::jperl_owner_trace) {
            diag("$key remains live: " . Internals::jperl_owner_trace($weak{$key}));
        }
        ok(!defined $weak{$key}, "$key is released after its aggregate is dropped");
    }
}

done_testing();
