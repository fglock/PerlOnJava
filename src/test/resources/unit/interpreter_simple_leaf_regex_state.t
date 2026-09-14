use strict;
use warnings;
use Test::More;

# This eval-created sub runs through InterpretedCode.  It deliberately has no
# regex, eval, closure, local, or user call, so its frame may elide the
# RegexState snapshot without changing the caller's dynamically-scoped $1.
eval q{sub interpreter_simple_leaf_regex_state { 7 }};
is($@, '', 'simple eval-created leaf compiles');

'before' =~ /(bef)(ore)/;
is($1, 'bef', 'outer match state is established');
is(interpreter_simple_leaf_regex_state(), 7, 'simple interpreted leaf returns');
is($1, 'bef', 'simple interpreted leaf preserves caller match state');

done_testing;
