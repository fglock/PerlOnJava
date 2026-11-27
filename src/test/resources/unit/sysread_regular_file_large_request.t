use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my $payload = 'x' x 131072;
my ($writer, $path) = tempfile(UNLINK => 1);
binmode $writer;
print {$writer} $payload or die "write test payload: $!";
close $writer or die "close test payload: $!";

open my $reader, '<:raw', $path or die "open test payload: $!";
my $buffer = '';
my $count = sysread($reader, $buffer, length $payload);
is $count, length($payload), 'one sysread fills a large regular-file request';
is $buffer, $payload, 'large regular-file read returns the complete bytes';
close $reader or die "close test payload reader: $!";

done_testing;
