use strict;
use warnings;
use B qw(svref_2object);
our @observed_refcounts;

sub refcount ($) {
    return svref_2object($_[0])->REFCNT;
}

{
    package Issue2043::NestedRefcountOwner;

    sub DESTROY {
        my $self = shift;
        my $sources = $self->{sources};
        push @main::observed_refcounts, main::refcount($sources->{source});
    }

    package Issue2043::NestedRefcountSource;
    sub DESTROY { }
}

{
    my $holder = bless {
        sources => { source => bless({}, 'Issue2043::NestedRefcountSource') },
    }, 'Issue2043::NestedRefcountOwner';
}

{
    package Issue2043::NestedRefcountOwnerWithoutChildDestroy;

    sub DESTROY {
        my $self = shift;
        my $sources = $self->{sources};
        push @main::observed_refcounts, main::refcount($sources->{source});
    }
}

{
    my $holder = bless {
        sources => { source => bless({}, 'Issue2043::NestedRefcountSourceWithoutDestroy') },
    }, 'Issue2043::NestedRefcountOwnerWithoutChildDestroy';
}

# Loading Test::More before the probe changes the runtime's count of objects
# created while loading its assertion stack. Keep the observed destructor
# scenario minimal, then load the assertion helper after the measurement.
require Test::More;
Test::More::plan(tests => 1);
Test::More::is_deeply(\@observed_refcounts, [2, 2],
    'DESTROY-time B::SV::REFCNT includes the nested aggregate owner');
