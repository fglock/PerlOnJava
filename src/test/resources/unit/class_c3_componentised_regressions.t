use strict;
use warnings;
use Test::More tests => 3;
use mro 'c3';

{
    package C3CompoundBase;
    sub new { bless {}, shift }

    package C3CompoundFallbackFalse;
    our @ISA = ('C3CompoundBase');
    use overload '+' => sub { die "called fallback false plus" },
                 fallback => 0;

    package C3CompoundFallbackFalseChild;
    our @ISA = ('C3CompoundFallbackFalse');

    package C3CompoundFallbackTrue;
    our @ISA = ('C3CompoundBase');
    use overload '+' => sub { die "called fallback true plus" },
                 fallback => 1;

    package C3CompoundFallbackTrueChild;
    our @ISA = ('C3CompoundFallbackTrue');
}

my $false = C3CompoundFallbackFalseChild->new;
eval { $false += 1 };
like($@, qr/no method found/, '+= does not autogenerate from + when fallback is false');

my $true = C3CompoundFallbackTrueChild->new;
eval { $true += 1 };
like($@, qr/called fallback true plus/, '+= still autogenerates from + when fallback is true');

{
    package C3NextCanProxy;
    sub can_proxy { goto &next::can }

    package C3NextCanBase;
    our @ISA = qw();
    sub quux { 242 }

    package C3NextCanTop;
    our @ISA = qw(C3NextCanBase);
    sub quux { shift->C3NextCanProxy::can_proxy()->() }
}

is(C3NextCanTop->quux, 242, 'goto &next::can preserves caller method context');
