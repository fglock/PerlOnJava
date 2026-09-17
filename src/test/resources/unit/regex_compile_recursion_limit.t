use strict;
use warnings;
use Test::More;

BEGIN { ${^RE_COMPILE_RECURSION_LIMIT} = 2 }
eval "#line 1 regex_compile_recursion_limit.t\nqr/((a))/";
is($@,
   "Too many nested open parens in regex; marked by <-- HERE in m/(( <-- HERE a))/ at regex_compile_recursion_limit.t line 1.\n",
   'regex compilation observes the nested-parenthesis limit');

done_testing;
