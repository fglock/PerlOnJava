use strict;
use warnings;
use Test::More;

my $text = '';
open my $fh, '>:scalar:perlio', \$text or die "open: $!";
print {$fh} 'buffered';
is($text, '', 'perlio delays scalar-backed output until flush');
close $fh or die "close: $!";
is($text, 'buffered', 'close flushes scalar-backed perlio output');

done_testing;
