use strict;
use warnings;
use Test::More tests => 8;

is sprintf("%.17g", 0.26436351706036748),
   "0.26436351706036748",
   "high precision decimal literal keeps 17 significant digits";

is sprintf("%.17g", 0.80197353455818021),
   "0.80197353455818021",
   "high precision decimal literal keeps trailing significant digit";

is sprintf("%.17g", 0.80197353455818043),
   "0.80197353455818043",
   "adjacent high precision decimal literal stays distinct";

is sprintf("%.17g", 0.85090259550889469),
   "0.85090259550889469",
   "high precision decimal literal rounds like Perl NV";

my $assigned = 0.42354155730533688;
is sprintf("%.17g", $assigned),
   "0.42354155730533688",
   "high precision decimal literal survives assignment path";

is sprintf("%.17g", "0.26436351706036748"),
   "0.26436351706036748",
   "high precision decimal string formats like Perl NV";

is sprintf("%.17g", 0.30000000000000004),
   "0.30000000000000004",
   "17 digit decimal literal is preserved";

is sprintf("%g", "0.000012345"),
   "1.2345e-05",
   "ordinary numeric strings keep existing exponent behavior";
