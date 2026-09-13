use strict;
use warnings;
use re 'eval';

our $dynamic_leaf = 'x';

my @definitions;
for my $number (0 .. 17) {
    my $next = $number + 1;
    push @definitions, "(?<rule$number>(?&rule$next)(?&rule$next)?)";
}
push @definitions, '(?<rule18>(??{ $dynamic_leaf }))';

my $grammar = '\\A(?&rule0)\\z(?(DEFINE)' . join('', @definitions) . ')';
my $compiled = qr/$grammar/;

print "1..2\n";
print "ok 1 - compiles a large named-subexpression grammar\n";
print "not " unless 'x' =~ $compiled;
print "ok 2 - compiled grammar matches its leaf\n";
