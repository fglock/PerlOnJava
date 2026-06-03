use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(isweak weaken);

{
    package IWSDR_Source;

    sub new {
        my ($class, $schema) = @_;
        my $self = bless { schema => $schema }, $class;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    package IWSDR_Schema;

    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $self->{source} = IWSDR_Source->new($self);
        return $self;
    }

    sub DESTROY {
        my $self = shift;
        $self->{source}{schema} = $self if $self->{source};
    }
}

my %registry;

{
    my $schema = IWSDR_Schema->new;
    $registry{schema} = $schema;
    weaken($registry{schema});
}

ok defined $registry{schema},
    "DESTROY-rescued object leaves weak registry slot temporarily defined";

ok isweak($registry{schema}),
    "isweak reports pre-sweep weak status for a rescued weak slot";

ok defined $registry{schema},
    "isweak does not clear a rescued weak slot";

$registry{schema}{source}{schema} = undef;
$registry{schema}{source} = undef;
delete $registry{schema};
