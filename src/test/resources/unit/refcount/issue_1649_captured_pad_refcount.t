use strict;
use warnings;
use B qw(svref_2object);
use Test::More;

our $destroyed = 0;
{
    package Issue1649CapturedPadCount;
    sub DESTROY { $main::destroyed++ }
}

sub refcount { svref_2object($_[0])->REFCNT }

sub make_capture {
    my $object = bless {}, 'Issue1649CapturedPadCount';
    my $cell = $object;
    my $closure = sub { $cell };
    return ($object, $closure);
}

my ($external, $closure) = make_capture();
is(refcount($external), 2, 'object has an external owner and captured pad cell');
undef $external;

my $probe = $closure->();
is(refcount($probe), 2, 'captured pad cell and probe are the two referent owners');
undef $probe;
is($destroyed, 0, 'captured pad keeps the object alive after the probe is dropped');

undef $closure;
is($destroyed, 1, 'dropping the closure releases the final pad-cell owner');

done_testing;
