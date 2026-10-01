use strict;
use warnings;
use Test::More;
use Config;

plan skip_all => 'Windows does not expose POSIX fchmod/fchdir capabilities'
    if $^O eq 'MSWin32';

plan tests => 2;
is($Config{d_fchmod}, 'define', 'Config advertises filehandle chmod support');
is($Config{d_fchdir}, 'define', 'Config advertises filehandle chdir support');
