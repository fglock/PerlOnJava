use strict;
use warnings;
use Test::More tests => 7;
use Scalar::Util qw(weaken);

our @DESTROYED;

{
    package StaleStashSavedMoveOwner::Probe;

    sub DESTROY {
        push @main::DESTROYED, 'value';
    }
}

my $object = bless {}, 'StaleStashSavedMoveOwner::Probe';
my $weak = $object;
weaken($weak);
my ($source_stash, $copy_stash);
{
    no strict 'refs';
    ${'StaleStashSavedMoveOwnerSource::item'} = $object;
    $source_stash = \%StaleStashSavedMoveOwnerSource::;
    undef $object;
    *StaleStashSavedMoveOwnerSource:: = 'StaleStashSavedMoveOwnerOther::';
    *StaleStashSavedMoveOwnerCopy:: = $source_stash;
    $copy_stash = \%StaleStashSavedMoveOwnerCopy::;
}

ok(defined $weak, 'the copied stale stash retains the moved value');
ok(exists $copy_stash->{item}, 'the copied stale stash exposes the moved symbol');

delete $copy_stash->{item};

is($weak, undef, 'deleting the moved slot releases its final value owner');
is_deeply(\@DESTROYED, ['value'], 'the moved value is destroyed exactly once');

my $abandoned_object = bless {}, 'StaleStashSavedMoveOwner::Probe';
my $abandoned_weak = $abandoned_object;
weaken($abandoned_weak);
my $abandoned_stash;
{
    no strict 'refs';
    ${'StaleStashSavedMoveOwnerAbandoned::item'} = $abandoned_object;
    $abandoned_stash = \%StaleStashSavedMoveOwnerAbandoned::;
    undef $abandoned_object;
    *StaleStashSavedMoveOwnerAbandoned:: = 'StaleStashSavedMoveOwnerOther::';
}

ok(defined $abandoned_weak, 'an unconsumed saved move retains its stale stash value');
undef $abandoned_stash;
is($abandoned_weak, undef, 'final stale-stash release retires its saved move');
is_deeply(\@DESTROYED, ['value', 'value'], 'both moved values are destroyed once');

done_testing;
