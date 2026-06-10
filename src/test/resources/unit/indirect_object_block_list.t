use strict;
use warnings;
use Test::More tests => 2;

package IndirectBlock::Receiver;

sub collect {
    return join '|', @_;
}

package main;

is(
    collect { 'IndirectBlock::Receiver' => 1, 2 },
    'IndirectBlock::Receiver|1|2',
    'indirect-object block list passes first item as invocant',
);

eval { missing_method { 'IndirectBlock::Receiver' => 1, 2 } };
like(
    $@,
    qr/Can't locate object method "missing_method" via package "IndirectBlock::Receiver"/,
    'missing indirect-object method reports invocant package',
);
