use strict;
use warnings;
use Test::More tests => 1;

use feature 'signatures';
no warnings 'experimental::signatures';

sub identity($value) { $value }
is(identity(42), 42, 'legacy signatures warning category remains accepted');
