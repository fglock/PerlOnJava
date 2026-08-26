use strict;
use warnings;
use Test::More tests => 3;

use IO::Handle;

my $stderr_io = *STDERR{IO};
ok($stderr_io->isa('IO::Handle'), 'STDERR IO slot is an IO::Handle object');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, shift };
    eval q{
#line 41 "warning-site.t"
my $x;
$x + 1;
};
}

is(scalar @warnings, 1, 'undefined arithmetic emits one warning');
is($warnings[0], "Use of uninitialized value \$x in addition (+) at warning-site.t line 42.\n",
   'arithmetic warning names the lexical and preserves its source location');
