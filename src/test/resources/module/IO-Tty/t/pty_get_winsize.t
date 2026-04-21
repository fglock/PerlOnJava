#!/usr/bin/env perl -w

use strict;
use warnings;

use Test::More;

if ( $^O =~ m!^(solaris|nto|aix)$! ) {
    plan skip_all => 'Problems on Solaris, QNX and AIX with this test';
}
else {
    plan tests => 1;
}

use IO::Pty ();

my @warnings;

{
    local $^W = 1;

    local $SIG{'__WARN__'} = sub { push @warnings, @_ };

    my $pty = IO::Pty->new();
    () = $pty->get_winsize();
}

is_deeply( \@warnings, [], 'get_winsize() doesn\'t warn' );
