#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 1;

# Image::BMP and similar modules define `sub close ()` and call CORE::close($fh).
# The compiler must use the builtin prototype for CORE::..., not the package sub's ().
eval q{
    package JpctCoreClose;
    sub close () { return 'package' }
    sub use_builtin_close {
        my $fh;
        CORE::close($fh);
    }
    1;
};
is $@, q{}, 'CORE::close($fh) parses when package defines sub close ()';
