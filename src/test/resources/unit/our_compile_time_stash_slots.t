use strict;
use warnings;
use Test::More tests => 6;

package OurCompileTimeSlots;

our $VERSION = '1.0';
our @items;
our %items;

BEGIN {
    no strict 'refs';

    main::ok(exists $OurCompileTimeSlots::{VERSION},
        'our scalar glob exists during compilation');
    main::ok(defined *OurCompileTimeSlots::VERSION{SCALAR},
        'our scalar slot exists during compilation');
    main::ok(exists $OurCompileTimeSlots::{items},
        'our aggregate glob exists during compilation');
    main::ok(defined *OurCompileTimeSlots::items{SCALAR},
        'our aggregate glob has its scalar slot');
    main::ok(defined *OurCompileTimeSlots::items{ARRAY},
        'our array slot exists during compilation');
    main::ok(defined *OurCompileTimeSlots::items{HASH},
        'our hash slot exists during compilation');
}
