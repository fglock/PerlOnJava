use feature 'say';
use strict;
use warnings;

###################
# Perl `next` Tests with `do ... while ...`

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
say "ok # Correct error message for `next` outside a loop";

###################
# Perl `next` Tests with `do ... while ...` with an outer loop

$next_do_while_count = 0;
{
    do {
        $next_do_while_count++;
        next if $next_do_while_count == 2;    # Skip when count is 2
    } while ( $next_do_while_count < 4 );
}
print "not " if $next_do_while_count != 2;
say "ok # Correct `next` outside a loop";

###################
# Perl `next` Tests with `for` modifier

my $next_for_count = 0;
$next_for_count++ for 1 .. 3;
$next_for_count == 2 && next for 1 .. 3;    # Skip when count is 2
print "not " if $next_for_count != 3;
say "ok # `next` in `for` modifier";

