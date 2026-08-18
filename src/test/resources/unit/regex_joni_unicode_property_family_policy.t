use strict;
use warnings;
use utf8;
use Test::More tests => 12;

ok("\x{0301}" =~ /\p{Canonical_Combining_Class=Above}/,
    'canonical combining class assignment');
ok("\x{0300}" !~ /\p{Canonical_Combining_Class=Below}/,
    'canonical combining class exclusion');
ok("\x{05D0}" =~ /\p{Bidi_Class=Right_To_Left}/,
    'bidi class assignment');
ok('A' !~ /\p{Bidi_Class=Right_To_Left}/,
    'bidi class exclusion');
ok("\x{00C0}" =~ /\p{Decomposition_Type=Canonical}/,
    'decomposition type assignment');
ok('k' !~ /\p{Decomposition_Type=Canonical}/i,
    'decomposition type does not gain fold members');
ok("\x{3000}" =~ /\p{East_Asian_Width=Fullwidth}/,
    'east Asian width assignment');
ok('k' !~ /\p{East_Asian_Width=Ambiguous}/i,
    'east Asian width does not gain fold members');
ok("\x{00BD}" =~ m{\p{Numeric_Value=1/2}},
    'numeric value rational assignment');
ok('A' !~ m{\p{Numeric_Value=1/2}}i,
    'numeric value does not gain fold members');
ok("\x{0627}" =~ /\p{Joining_Group=Alef}/,
    'joining group assignment');
ok('A' !~ /\p{Joining_Group=Alef}/i,
    'joining group does not gain fold members');
