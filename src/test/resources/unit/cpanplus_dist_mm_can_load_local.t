#!/usr/bin/env perl

use strict;
use warnings;

use Test::More tests => 1;

BEGIN {
    package CPANPLUS::Dist::MM::CanLoadLocalTestOrig;
    our $VERSION = 1;

    sub eu_mm_stub { return 1 }
}

BEGIN {
    package CPANPLUS::Dist::MM::CanLoadLocalTestMM;
    use strict;
    use warnings;

    BEGIN {
        no warnings 'once';
        *can_load = \&CPANPLUS::Dist::MM::CanLoadLocalTestOrig::eu_mm_stub;
    }

    # Mirrors CPANPLUS::Dist::MM::format_available's bareword call to imported can_load().
    sub format_available_like_mm {
        my $mod = 'ExtUtils::MakeMaker';

        ### Upstream MM.pm:
        ###   unless( can_load( modules => { $mod => 0.0 } ) ) { ... }
        return unless can_load( modules => { $mod => 0.0 } );

        return 1;
    }
}

package main;

{
    no warnings qw(redefine once);
    local *CPANPLUS::Dist::MM::CanLoadLocalTestMM::can_load = sub { return 0 };

    ok(
        !CPANPLUS::Dist::MM::CanLoadLocalTestMM::format_available_like_mm(),
        q{local *Pkg::can_load makes can_load(modules=>...) observe the monkeypatch},
    );
}
