use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($empty_fh, $path) = tempfile(UNLINK => 1);
close $empty_fh or die "close empty file: $!";

open my $reader, '<:raw', $path or die "open empty file: $!";
my $buffer = 'stale contents';
my $count = sysread($reader, $buffer, 0x7fffffff);
is $count, 0, 'a very large sysread on an empty file returns EOF';
is $buffer, '', 'a zero-byte read clears the destination buffer';
close $reader or die "close reader: $!";

open my $writer, '>:raw', $path or die "reopen test file: $!";
print {$writer} 'small payload' or die "write test file: $!";
close $writer or die "close writer: $!";
open $reader, '<:raw', $path or die "reopen test file for reading: $!";
$buffer = '';
$count = sysread($reader, $buffer, 0x7fffffff);
is $count, length('small payload'),
    'a very large sysread returns the available byte count for a nonempty file';
is $buffer, 'small payload', 'a very large sysread returns the file contents';
close $reader or die "close reader: $!";

done_testing;
