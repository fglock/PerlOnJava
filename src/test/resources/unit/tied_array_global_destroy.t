use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($fh, $file) = tempfile();
print {$fh} <<'PERL';
package TiedArrayGlobalDestroy;
sub TIEARRAY { bless [], shift }
sub DESTROY { print "destroyed\n" }
package main;
tie our @items, 'TiedArrayGlobalDestroy';
PERL
close $fh;

my $output = `$^X $file`;
is($output, "destroyed\n", 'global tied array releases its handler at destruction');

done_testing;
