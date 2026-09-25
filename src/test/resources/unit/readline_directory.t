use strict;
use warnings;
use Test::More tests => 2;

open my $fh, '<', '.' or die "open directory: $!";
$! = 0;
my $line = <$fh>;
ok !defined($line) && $! == 21, 'readline from a directory returns undef and EISDIR';

close $fh;
{
    local $/;
    open $fh, '<', '.' or die "open directory: $!";
    $! = 0;
    $line = <$fh>;
    ok !defined($line) && $! == 21, 'slurp readline from a directory returns undef and EISDIR';
    close $fh;
}
