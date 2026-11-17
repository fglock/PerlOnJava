use strict;
use warnings;
use Test::More tests => 3;
use autodie::exception;

my $exception;
{
    # Make the last Perl frame look like an internal autodie frame. The
    # exception constructor must stop cleanly when caller() runs out of frames.
    local @main::ISA = ('Fatal');
    $exception = eval {
        autodie::exception->new(
            function => 'CORE::fork', errno => 11, args => [],
            context => 'scalar', return => undef,
        );
    };
}

is($@, '', 'caller stack exhaustion does not replace exception construction');
isa_ok($exception, 'autodie::exception', 'exception construction completes');
is($exception && $exception->function, 'CORE::fork', 'exception retains the failed operation');
