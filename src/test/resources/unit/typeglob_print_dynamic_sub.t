use strict;
use warnings;
use Test::More tests => 2;

# The call sites are parsed before this assignment runs, so they must retain
# dynamic named-sub lookup instead of being interpreted as filehandles.
*issue_1163_bar = sub { 'glob-installed' };

my $output = '';
open my $fh, '>', \$output or die "open scalar handle: $!";
{
    local *STDOUT = $fh;
    print issue_1163_bar(), "\n";
}
is $output, "glob-installed\n",
    'unparenthesized print invokes a sub installed through a typeglob';

$output = '';
open $fh, '>', \$output or die "open scalar handle: $!";
{
    local *STDOUT = $fh;
    print(issue_1163_bar(), "\n");
}
is $output, "glob-installed\n",
    'parenthesized print invokes a sub installed through a typeglob';
