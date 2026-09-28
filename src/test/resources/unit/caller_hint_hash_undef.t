use strict;
use warnings;
use Test::More tests => 2;

sub caller_hint { (caller 0)[10]{caller_undef_hint} }
sub caller_hint_exists { my $h = (caller 0)[10]; exists $h->{caller_undef_hint} }

BEGIN { $^H{caller_undef_hint} = undef }
ok(!defined caller_hint(), 'caller hint hash preserves an undef value');
BEGIN { delete $^H{caller_undef_hint} }
ok(!caller_hint_exists(), 'caller hint hash preserves deletion');
