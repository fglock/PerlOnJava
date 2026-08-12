use strict;
use warnings;
use Test::More tests => 6;

ok('A' !~ /\p{^Latin}/, '\\p caret excludes the named property');
ok("\x{2603}" =~ /\p{^Latin}/, '\\p caret includes other properties');
ok('A' =~ /\P{^Latin}/, '\\P caret double negation includes the property');
ok("\x{2603}" !~ /\P{^Latin}/, '\\P caret double negation excludes others');
ok('A' !~ /[\p{^Latin}]/, 'caret property works inside a character class');
ok("\x{2603}" =~ /[\p{^Latin}]/, 'negated property class matches other scripts');
