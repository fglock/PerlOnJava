use strict;
use warnings;
use Test::More tests => 4;

{
    package TiedISA::Parent;
    sub inherited { 42 }
}

{
    package TiedISA::Array;

    sub TIEARRAY {
        my ($class, $values) = @_;
        return bless [ @{$values} ], $class;
    }

    sub FETCHSIZE { scalar @{$_[0]} }
    sub FETCH     { $_[0]->[$_[1]] }
}

{
    package TiedISA::Child;
    our @ISA = ('TiedISA::Parent');
    my @parents = @ISA;
    tie @ISA, 'TiedISA::Array', \@parents;
}

is_deeply([ @TiedISA::Child::ISA ], ['TiedISA::Parent'], 'tied @ISA exposes its parent');
ok(TiedISA::Child->isa('TiedISA::Parent'), 'isa follows tied @ISA');
my $method = TiedISA::Child->can('inherited');
ok($method, 'can finds a method through tied @ISA');
is($method->(), 42, 'inherited method remains callable');
