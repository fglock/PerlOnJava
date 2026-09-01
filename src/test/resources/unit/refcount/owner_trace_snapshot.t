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

done_testing;
