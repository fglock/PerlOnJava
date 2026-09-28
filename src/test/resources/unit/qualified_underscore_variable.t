use strict;
use warnings;
use Test::More;

# `_` is the default variable only when unqualified.  A package segment named
# `_` remains legal, including the spelling used by the Autoload JAPH.
$_::_ = 'qualified underscore';
is($_::_, 'qualified underscore', 'qualified underscore package variable');

done_testing();
