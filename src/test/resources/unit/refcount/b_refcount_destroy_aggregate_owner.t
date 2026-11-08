use strict;
use warnings;
use Test::More;
use B qw(svref_2object);

our @observed_refcounts;
our @retained_b_wrappers;

{
    package Issue1649::BRefcountDestroyOwner;

    sub DESTROY {
        push @main::observed_refcounts,
            B::svref_2object($_[0]{child})->REFCNT;
    }
}

{
    my $owner = bless {
        child => bless({}, 'Issue1649::BRefcountDestroyChild'),
    }, 'Issue1649::BRefcountDestroyOwner';
    undef $owner;
}
is(shift @observed_refcounts, 1,
    'B::SV::REFCNT reports one hash-element owner during DESTROY');

{
    my $child = bless({}, 'Issue1649::BRefcountDestroyChild');
    my $owner = bless { child => $child }, 'Issue1649::BRefcountDestroyOwner';
    undef $owner;
}
is(shift @observed_refcounts, 2,
    'B::SV::REFCNT preserves an independent lexical owner during DESTROY');

{
    my $owner = bless {
        child => bless({}, 'Issue1649::BRefcountDestroyChild'),
    }, 'Issue1649::BRefcountDestroyOwner';
    push @retained_b_wrappers,
        B::svref_2object($owner->{child}),
        B::svref_2object($owner->{child});
    undef $owner;
}
is(shift @observed_refcounts, 1,
    'overlapping B::SV wrappers do not become extra Perl owners during DESTROY');
@retained_b_wrappers = ();

done_testing;
