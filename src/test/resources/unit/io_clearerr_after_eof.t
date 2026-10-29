use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempfile);
use IO::File;

my ($writer, $path) = tempfile();
print {$writer} "first\n" or die "write initial line: $!";
close $writer or die "close initial writer: $!";

my $reader = IO::File->new($path, '<') or die "open reader: $!";
is(scalar <$reader>, "first\n", 'read initial line');
is(scalar <$reader>, undef, 'read reaches EOF');

open $writer, '>>', $path or die "open append writer: $!";
print {$writer} "second\n" or die "append line: $!";
close $writer or die "close append writer: $!";

is($reader->clearerr, 0, 'clearerr succeeds');
is(scalar <$reader>, "second\n", 'read data appended after EOF');

$reader->close;
