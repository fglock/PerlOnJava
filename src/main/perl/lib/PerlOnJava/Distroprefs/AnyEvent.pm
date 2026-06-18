package PerlOnJava::Distroprefs::AnyEvent;

use strict;
use warnings;

sub pl_phase {
    local $ENV{PERL_CANARY_STABILITY_NOPROMPT} = 1;
    my $ok = do './Makefile.PL';
    die $@ if $@;
    die "Could not run Makefile.PL: $!" unless defined $ok;
    die "Makefile.PL returned false\n" unless $ok;
    return 1;
}

1;
