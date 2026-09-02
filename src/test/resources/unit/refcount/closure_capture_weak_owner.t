use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More;

our @destroyed;

{
    package ClosureCaptureWeakOwner;
    sub DESTROY { push @main::destroyed, $_[0]{id} }
}

sub object { bless { id => $_[0] }, 'ClosureCaptureWeakOwner' }

# A callback stored in a live object keeps its captured lexical alive after the
# lexical's declaring block exits, as in Future::PP callback records.
my $holder = bless {}, 'ClosureCaptureWeakOwner';
my $weak_sequence;
{
    my $sequence = object('sequence');
    $weak_sequence = $sequence;
    weaken($weak_sequence);
    $holder->{on_cancel} = sub { $sequence };
}

ok(defined($weak_sequence),
    'reachable callback keeps its captured referent alive');
is($holder->{on_cancel}->(){id}, 'sequence',
    'retained callback still observes the captured object');

$holder = undef;
ok(!defined($weak_sequence),
    'releasing the callback clears the weak reference');
is(scalar(grep { defined($_) && $_ eq 'sequence' } @destroyed), 1,
    'captured object is destroyed exactly once');

# A weak capture must not turn into a strong capture when the lexical is
# weakened after the closure is created.
@destroyed = ();
my ($weak_capture, $weak_callback);
{
    my $value = object('weakened');
    $weak_capture = $value;
    weaken($weak_capture);
    my $callback = sub { $value };
    weaken($value);
    $weak_callback = $callback;
}
ok(!defined($weak_capture), 'weakening a captured scalar releases ownership');
undef $weak_callback;
is(scalar(grep { $_ eq 'weakened' } @destroyed), 1,
    'weakened capture does not retain the referent');

# Reassignment of a captured scalar drops the old referent and keeps the new
# one until the closure goes away.
@destroyed = ();
my ($old_weak, $new_weak, $reassign);
{
    my $value = object('old');
    $old_weak = $value;
    weaken($old_weak);
    $reassign = sub { $value };
    $value = object('new');
    $new_weak = $value;
    weaken($new_weak);
}
ok(!defined($old_weak), 'reassigning a capture releases the old referent');
ok(defined($new_weak), 'reassigned capture retains the new referent');
is($reassign->(){id}, 'new', 'reassigned callback observes the new referent');
undef $reassign;
ok(!defined($new_weak), 'releasing reassigned callback clears the new weak ref');
is_deeply([sort @destroyed], [qw(new old)],
    'reassignment destroys both referents exactly once');

done_testing;
