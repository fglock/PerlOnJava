use strict;
use warnings;

print "1..4\n";

sub escaped_error (&) {
    my ($code) = @_;
    my $error = '';
    eval { $code->() };
    $error = $@;
    return $error;
}

my $last = escaped_error { 'a' =~ /(?{ last })a/ };
print $last =~ /Can't "last" outside a loop block/
    ? "ok 1 - last cannot escape a regex pseudo block\n"
    : "not ok 1 - last cannot escape a regex pseudo block\n";

my $next = escaped_error { 'a' =~ /(?{ next })a/ };
print $next =~ /Can't "next" outside a loop block/
    ? "ok 2 - next cannot escape a regex pseudo block\n"
    : "not ok 2 - next cannot escape a regex pseudo block\n";

my $goto = '';
eval q{'a' =~ /(?{ goto OUT })a/; OUT: 1};
$goto = $@;
print $goto =~ /Can't "goto" out of a pseudo block/
    ? "ok 3 - goto cannot escape a regex pseudo block\n"
    : "not ok 3 - goto cannot escape a regex pseudo block\n";

my $inner = escaped_error {
    'a' =~ /(?{ for (1) { last } })a/;
};
print $inner eq ''
    ? "ok 4 - callback-local loop consumes its own control flow\n"
    : "not ok 4 - callback-local loop consumes its own control flow\n";
