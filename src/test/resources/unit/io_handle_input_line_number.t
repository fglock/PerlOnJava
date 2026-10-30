use strict;
use warnings;
use Test::More;
use IO::Handle;

my $input = "first\nsecond\n";
open my $fh, '<', \$input or die "open scalar handle: $!";

is($fh->getline, "first\n", 'read the first line from a scalar handle');
is($., 1, 'the global input line number follows getline');
my $read_line_number = sub { $. };
is($read_line_number->(), 1,
    'the global input line number is visible inside a subroutine');
is($fh->input_line_number, 1,
    'input_line_number reports the current line after getline');

is($fh->getline, "second\n", 'read the second line from a scalar handle');
is($fh->input_line_number, 2,
    'input_line_number advances with subsequent getline calls');

done_testing;
