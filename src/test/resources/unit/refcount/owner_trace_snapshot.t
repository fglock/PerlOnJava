use strict;
use warnings;

use Test::More;

plan skip_all => 'PerlOnJava owner-trace diagnostic' unless defined &Internals::jperl_owner_trace;

my $object = bless {}, 'OwnerTraceSnapshot';
my $snapshot = Internals::jperl_owner_trace($object);

like($snapshot, qr/\Abase=\d+ generation=\d+ refCount=-?\d+ active=\[/,
    'snapshot identifies the selected referent and active owner set');
like($snapshot, qr/ pending=\[\] transient=\[\]\z/,
    'fresh assertion-boundary snapshot has no deferred or transient ownership');

my $nested = bless {}, 'OwnerTraceNested';
Internals::jperl_owner_trace($nested); # enable trace collection before the transient hold
my $scalar_ref = \$nested;
$scalar_ref = undef;
my $nested_snapshot = Internals::jperl_owner_trace($nested);
like($nested_snapshot, qr/ transient=\[\]\z/,
    'scalar-reference contents hold is balanced after release');

done_testing;
