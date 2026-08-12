use strict;
use warnings;
use Test::More tests => 3;

package OurStashCompileTime;

our $VERSION = '1.0';
our @items = (1, 2);
our %options = (enabled => 1);

BEGIN {
    no strict 'refs';
    Test::More::ok(exists $OurStashCompileTime::{VERSION},
        'our scalar glob exists during compilation');
    Test::More::ok(exists $OurStashCompileTime::{items},
        'our array glob exists during compilation');
    Test::More::ok(exists $OurStashCompileTime::{options},
        'our hash glob exists during compilation');
}
