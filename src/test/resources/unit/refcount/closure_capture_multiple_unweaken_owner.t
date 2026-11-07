use strict;
use warnings;

use Scalar::Util qw(weaken unweaken);
use Test::More;

our $DESTROYED = 0;
{
    package MultipleUnweakenCaptureOwner;
    sub DESTROY { ++$main::DESTROYED }
}

my ($weak, $reader, $writer);
{
    my $object = bless {}, 'MultipleUnweakenCaptureOwner';
    my $value = $object;
    $weak = $object;
    weaken($weak);

    $reader = sub { $value };
    $writer = sub { $value = $_[0] if @_; return $value };
    weaken($value);
    unweaken($value);
    undef $object;
}

ok(defined $weak, 'the shared captured pad keeps the unweakened referent alive');
is(ref($reader->()), 'MultipleUnweakenCaptureOwner', 'the reader sees the shared referent');
undef $reader;
ok(defined $weak, 'one remaining closure still owns the captured pad');
is(ref($writer->()), 'MultipleUnweakenCaptureOwner', 'the writer sees the shared referent');
undef $writer;
ok(!defined $weak, 'the weak observer clears after the final closure is released');
is($DESTROYED, 1, 'the final captured referent is destroyed exactly once');

done_testing;
