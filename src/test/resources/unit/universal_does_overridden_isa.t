use strict;
use warnings;
use Test::More tests => 4;

{
    package DoesOverrideBase;

    sub new { bless {}, shift }

    package DoesOverrideChild;
    our @ISA = ('DoesOverrideBase');

    sub isa {
        my ($self, $class) = @_;
        return $self->SUPER::isa($class) || $class eq 'SyntheticClass';
    }
}

ok(DoesOverrideChild->DOES('SyntheticClass'),
    'inherited UNIVERSAL::DOES dispatches an overridden isa for a class');
ok(DoesOverrideChild->new->DOES('SyntheticClass'),
    'inherited UNIVERSAL::DOES dispatches an overridden isa for an object');
ok(DoesOverrideChild->DOES('DoesOverrideBase'),
    'overridden isa can delegate to SUPER for a class');
ok(DoesOverrideChild->new->DOES('DoesOverrideBase'),
    'overridden isa can delegate to SUPER for an object');
