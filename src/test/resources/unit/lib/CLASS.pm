package CLASS;

use 5.004;

our $VERSION = 'v1.1.8';

BEGIN {
    *PERL_VERSION = eval qq{ sub () { $] } };
}

sub import {
    my $caller = caller;
    *{$caller . '::CLASS'} = \$caller;

    if (PERL_VERSION >= 5.008) {
        *{$caller . '::CLASS'} = sub () { $caller };
    }
}

1;
