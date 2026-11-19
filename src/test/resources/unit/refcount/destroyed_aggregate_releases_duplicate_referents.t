use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);

our @destroyed;

{
    package Issue2043::DuplicateAggregateChild;
    sub DESTROY { push @main::destroyed, ref $_[0] }

    package Issue2043::DuplicateHashOwner;
    sub DESTROY { }

    package Issue2043::DuplicateArrayOwner;
    sub DESTROY { }
}

{
    my $child = bless {}, 'Issue2043::DuplicateAggregateChild';
    my $weak_child = $child;
    weaken($weak_child);
    my $owner = bless { left => $child, right => $child },
        'Issue2043::DuplicateHashOwner';
    undef $child;
    undef $owner;
    ok(!defined $weak_child, 'hash cleanup releases both slots to one child');
    is_deeply(\@destroyed, ['Issue2043::DuplicateAggregateChild'],
        'hash cleanup destroys the duplicate child exactly once');
}

@destroyed = ();
{
    my $child = bless {}, 'Issue2043::DuplicateAggregateChild';
    my $weak_child = $child;
    weaken($weak_child);
    my $owner = bless [ $child, $child ], 'Issue2043::DuplicateArrayOwner';
    undef $child;
    undef $owner;
    ok(!defined $weak_child, 'array cleanup releases both slots to one child');
    is_deeply(\@destroyed, ['Issue2043::DuplicateAggregateChild'],
        'array cleanup destroys the duplicate child exactly once');
}
