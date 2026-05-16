#!/usr/bin/env perl

use strict;
use warnings;

use Test::More tests => 1;

SKIP: {

    skip 'root-style path "/" not useful for filetest portability', 1 unless -e '/';

    my $ok = eval q{
      use strict;
      use warnings;
      use constant BUILD_PL => sub { '/' };

      (-e BUILD_PL->()) ? 1 : 0;
    };

    diag $@ if $@;

    cmp_ok(
        $ok, '==', 1,
        '-e CONSTANT->(...) treats CONSTANT as invocable sub, not ALLCAPS filehandle slot'
    );
}
