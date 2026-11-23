use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our $destroyed = 0;
our ($weak_schema, $weak_storage);

{
    package WeakReturnHandoff::Storage;

    sub DESTROY {
        $main::destroyed++;
    }
}

{
    package WeakReturnHandoff::Schema;

    sub storage {
        return $_[0]->{storage};
    }
}

sub make_schema {
    my $schema = bless {
        storage => bless({ active => 1 }, 'WeakReturnHandoff::Storage'),
    }, 'WeakReturnHandoff::Schema';

    weaken($weak_schema  = $schema);
    weaken($weak_storage = $schema->{storage});

    return $schema;
}

my $schema;
$schema = make_schema();

ok(defined $weak_schema, 'returned object weak ref survives assignment handoff');
ok(defined $weak_storage, 'nested weak ref survives assignment handoff');
is($destroyed, 0, 'nested object is not destroyed during assignment handoff');
is($schema->storage->{active}, 1, 'returned object graph remains usable');

undef $schema;
Internals::jperl_gc() if defined &Internals::jperl_gc;

ok(!defined $weak_schema, 'weak object ref clears after caller releases value');
ok(!defined $weak_storage, 'weak nested ref clears after caller releases value');
is($destroyed, 1, 'nested object is destroyed after caller releases value');

done_testing();
