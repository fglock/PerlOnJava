use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our @WEAK_REFERENTS;
our $DESTROYED = 0;

{
    package Issue1649TransferNode;
    sub DESTROY { ++$main::DESTROYED }
}

sub make_transfer_cell {
    my $value = shift;
    my $replace = sub { $value = shift };
    my $read = sub { $value };
    return ($replace, $read);
}

my $old = bless {}, 'Issue1649TransferNode';
push @WEAK_REFERENTS, $old;
weaken($WEAK_REFERENTS[-1]);
my ($replace, $read) = make_transfer_cell($old);
$old = undef;
ok(defined $WEAK_REFERENTS[0], 'a captured pad keeps its current referent alive');

my $new = bless {}, 'Issue1649TransferNode';
push @WEAK_REFERENTS, $new;
weaken($WEAK_REFERENTS[-1]);
$replace->($new);
ok(!defined $WEAK_REFERENTS[0], 'replacing a captured pad releases its former referent');
is(ref($read->()), 'Issue1649TransferNode',
    'all closures sharing the pad observe the transferred referent');

$new = undef;
undef $replace;
ok(defined $WEAK_REFERENTS[1], 'the remaining closure still owns the pad referent');
undef $read;
ok(!defined $WEAK_REFERENTS[1], 'releasing the last closure releases the pad referent');
is($DESTROYED, 2, 'each transferred pad referent is destroyed once');

done_testing;
