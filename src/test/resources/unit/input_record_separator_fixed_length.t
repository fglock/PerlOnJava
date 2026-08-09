use strict;
use warnings;
use Test::More tests => 8;

my $data = "abcdef";
open my $fh, '<', \$data or die $!;

{
    local $/ = \1;
    is scalar($fh->getline), 'a', 'localized fixed-length separator reads one character';
    is tell($fh), 1, 'fixed-length getline advances by one character';
}

{
    local $/ = \2;
    is scalar($fh->getline), 'bc', 'localized fixed-length separator accepts larger records';
    is tell($fh), 3, 'larger fixed-length getline advances by the record size';
    is scalar($fh->getline), 'de', 'fixed-length mode remains active for repeated reads';
    is scalar($fh->getline), 'f', 'final short record is returned';
    is scalar($fh->getline), undef, 'read after the final record returns undef';
}

is $/, "\n", 'localized input record separator is restored';
