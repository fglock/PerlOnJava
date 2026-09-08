use strict;
use warnings;
use Test::More;

# Growing a parser token one character at a time must remain linear.  This is
# intentionally large enough to catch a Java String copy on every .=.
my $text = '';
$text .= chr(65 + ($_ % 26)) for 1 .. 100_000;

is length $text, 100_000, 'large repeated concat-assignment retains every character';
is substr($text, 0, 4), 'BCDE', 'large concat-assignment preserves ordering';
is substr($text, -4), 'BCDE', 'large concat-assignment preserves the suffix';

my $bytes = pack 'C', 0xA5;
$bytes .= pack('C', 0x5A) for 1 .. 100_000;

is length $bytes, 100_001, 'large byte-string concat-assignment retains every byte';
ok !utf8::is_utf8($bytes), 'large byte-string concat-assignment preserves the byte flag';
is substr($bytes, -4), 'ZZZZ', 'large byte-string concat-assignment preserves the suffix';

my $truth = '';
$truth .= 'x';
ok $truth, 'truthiness sees a deferred append immediately';

my $number = '';
$number .= '42';
is 0 + $number, 42, 'numeric conversion sees a deferred append immediately';

my $original = '';
$original .= 'abc';
my $copy = $original;
$original .= 'd';
is $copy, 'abc', 'ordinary assignment materializes rather than sharing an append buffer';

done_testing;
