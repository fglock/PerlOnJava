use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More tests => 6;

{
    package Local::Condition;

    sub result_source {
        my ($self, $value) = @_;
        $self->{source} = $value if @_ > 1;
        return $self->{source};
    }
}

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

SKIP: {
    skip 'shifted weak diagnostic was cleared prematurely', 3
        unless defined $diagnostic;
    ok defined($diagnostic->result_source), 'deep cycle remains traversable';
    $diagnostic->result_source(undef);
    ok !defined($diagnostic), 'breaking cycle clears shifted weak reference';
    ok !defined($registry_probe), 'breaking cycle clears registry weak reference';
}
