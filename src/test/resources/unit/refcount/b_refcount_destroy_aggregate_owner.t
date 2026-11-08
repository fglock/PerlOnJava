use strict;
use warnings;
use Test::More;
use B qw(svref_2object);
use Scalar::Util qw(weaken isweak);

our @observed_refcounts;
our @retained_b_wrappers;
our @nested_refcounts;

sub issue1649_nested_refcount {
    return B::svref_2object($_[0])->REFCNT;
}

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

{
    package Issue1649::RescuedSource;
    sub DESTROY {
        if (defined $_[0]{schema} && !Scalar::Util::isweak($_[0]{schema})) {
            Scalar::Util::weaken($_[0]{schema});
        }
    }

    package Issue1649::RescuedSchema;
    sub DESTROY {
        my $self = $_[0];
        my $source = $self->{source_registrations}{Artist};
        return unless ref $source;
        my $count = main::issue1649_nested_refcount($source);
        push @main::nested_refcounts, $count;
        if ($count > 1) {
            $source->{schema} = $self;
            Scalar::Util::weaken($self->{source_registrations}{Artist})
                unless Scalar::Util::isweak($self->{source_registrations}{Artist});
        }
    }
}

{
    my $schema = bless { source_registrations => {} }, 'Issue1649::RescuedSchema';
    my $source = bless { schema => $schema }, 'Issue1649::RescuedSource';
    weaken($source->{schema});
    $schema->{source_registrations}{Artist} = $source;
    my $row = $source;
    undef $source;
    undef $schema;
    undef $row;
}
is_deeply(\@nested_refcounts, [3, 2],
    'nested Schema and source destruction ignore overlapping argument-frame owners');

done_testing;
