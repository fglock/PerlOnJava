use feature 'say';
use strict;
use Test::More;
use warnings;

###################
# `next` Tests with `do ... while ...`

my $next_do_while_count = 0;
eval q{
    do {
        $next_do_while_count++;
        next if $next_do_while_count == 2;  # Skip when count is 2
    } while ($next_do_while_count < 4);
};
my $error_message = $@ // "No error";

# Check if the error message is exactly what we expect
print "not "
  if !($error_message
    && $error_message =~ /Can't "next" outside a loop block/ );
say "ok # error message for `next` outside a loop <" . substr($error_message, 0, 20) . ">";

###################
# `next` Tests with `do ... while ...` with an outer loop

$next_do_while_count = 0;
{
    do {
        $next_do_while_count++;
        next if $next_do_while_count == 2;    # Skip when count is 2
    } while ( $next_do_while_count < 4 );
}
ok(!($next_do_while_count != 2), '`next` outside a loop');

###################
# `next` Tests with `for` modifier

my $next_for_count = 0;
$next_for_count++ for 1 .. 3;
$next_for_count == 2 && next for 1 .. 3;    # Skip when count is 2
ok(!($next_for_count != 3), '`next` in `for` modifier');

###################
# `while` loop

my $while_mod_count = 0;
$while_mod_count++ while 0;     # never executes
ok(!($while_mod_count != 0), '`while` loop with statement modifier');

###################
# `do-while` loop

$while_mod_count = 0;
do { $while_mod_count++ } while 0;  # executes once
ok(!($while_mod_count != 1), '`do-while` loop with statement modifier executes at least once');

done_testing();
