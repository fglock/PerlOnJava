use strict;
use warnings;
use Test::More tests => 1;

sub UNIVERSAL::AUTOLOAD {}
Errno::foo() if 0;
%!;

ok($INC{'Errno.pm'}, '%! in void context loads Errno');
