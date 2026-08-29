use v5.10;
use Test::More;

sub IO::Handle::perlonjava_io_can_regression { }

ok UNIVERSAL::can(*STDOUT, 'perlonjava_io_can_regression'),
    'a bare glob with IO can find an IO::Handle method';
ok UNIVERSAL::can(\*STDOUT, 'perlonjava_io_can_regression'),
    'a glob reference with IO can find an IO::Handle method';
ok UNIVERSAL::can('STDOUT', 'perlonjava_io_can_regression'),
    'an IO bareword can find an IO::Handle method';

done_testing;
