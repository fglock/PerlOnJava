use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More tests => 11;

{
    package Local::Condition;

    sub result_source {
        my ($self, $value) = @_;
        $self->{source} = $value if @_ > 1;
        return $self->{source};
    }

    # DBIx resultsets participate in DESTROY tracking even though the
    # diagnostic cycle intentionally keeps this instance alive.
    our $destroyed = 0;
    sub DESTROY { $destroyed++ }
}

{
    package Local::RescuedSchema;

    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $self->{source} = { schema => $self };
        Scalar::Util::weaken($self->{source}{schema});
        return $self;
    }

    sub DESTROY {
        my $self = shift;
        return if $self->{rescued}++;
        $self->{source}{schema} = $self if $self->{source};
    }

    package DBICTest::Util::LeakTracer;

    sub registry_slot_is_weak {
        return Scalar::Util::isweak($_[0]);
    }
}

# DBIx's leak checker can have a DESTROY-rescued schema pending while it
# recursively checks weak slots belonging to an unrelated strong cycle.
my %rescued_registry;
{
    my $rescued = Local::RescuedSchema->new;
    $rescued_registry{schema} = $rescued;
    weaken($rescued_registry{schema});
}
ok defined($rescued_registry{schema}),
    'DESTROY rescue remains pending during leak-registry diagnostics';

# DBIx::Class t/52leaks.t diagnoses this shape: condition -> source -> schema
# -> storage -> database handle -> cached condition. The cycle is deliberately
# strong, while its leak registry and diagnostic variable are weak references.
my $condition = bless {}, 'Local::Condition';
my $source = { schema => { storage => { dbh => { cached => $condition } } } };
$condition->{source} = $source;

my $registry_probe = $condition;
weaken($registry_probe);
my @circreffed = ($condition);
undef $condition;
undef $source;

ok defined($registry_probe), 'deep cycle keeps registry weak reference alive';
weaken(my $diagnostic = shift @circreffed);
ok isweak($diagnostic), 'inline shifted diagnostic reference is weak';
ok defined($diagnostic), 'deep cycle keeps shifted weak diagnostic alive';

my $mini_registry_slot = $diagnostic;
weaken($mini_registry_slot);
ok DBICTest::Util::LeakTracer::registry_slot_is_weak($mini_registry_slot),
    'recursive leak-registry slot remains weak';
ok defined($diagnostic),
    'rescued-object cleanup preserves unrelated strong-cycle diagnostic';
is $Local::Condition::destroyed, 0,
    'strong-cycle object is not destroyed during recursive diagnostics';
ok defined($rescued_registry{schema}),
    'observing a strong-cycle weak slot does not consume pending rescues';

# Avoid leaving the deliberately resurrected fixture alive until global
# destruction on standard Perl.
if (defined $rescued_registry{schema}) {
    $rescued_registry{schema}{source}{schema} = undef;
}
%rescued_registry = ();

SKIP: {
    skip 'shifted weak diagnostic was cleared prematurely', 3
        unless defined $diagnostic;
    ok defined($diagnostic->result_source), 'deep cycle remains traversable';
    $diagnostic->result_source(undef);
    ok !defined($diagnostic), 'breaking cycle clears shifted weak reference';
    ok !defined($registry_probe), 'breaking cycle clears registry weak reference';
}
