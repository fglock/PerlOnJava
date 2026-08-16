use strict;
use warnings;
use re 'eval';

print "1..3\n";

my $plain = '';
open my $plain_out, '>', \$plain or die $!;
{
    local *STDOUT = $plain_out;
    print scalar "abcabc" =~ /(abc){2}/;
}
print $plain eq '1'
    ? "ok 1 - named unary scalar includes the following match\n"
    : "not ok 1 - named unary scalar includes the following match\n";

our $value = 1;
my $callback = '';
open my $callback_out, '>', \$callback or die $!;
{
    local *STDOUT = $callback_out;
    print scalar "abcabc" =~
        /(a(?{ local $value = $value + 1 })
          b(?{ local $value = $value + 1 })
          c(?{ local $value = $value + 1 })){2}/x;
}
print $callback eq '1'
    ? "ok 2 - callback match remains in scalar context\n"
    : "not ok 2 - callback match remains in scalar context\n";
print $value == 1
    ? "ok 3 - callback locals unwind after scalar match\n"
    : "not ok 3 - callback locals unwind after scalar match\n";
