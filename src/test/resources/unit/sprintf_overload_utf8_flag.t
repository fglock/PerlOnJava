use strict;
use warnings;
use Test::More tests => 4;

{
    package SprintfCount;
    use overload '0+' => sub { ++$SprintfCount::count; $_[0]->[0] }, fallback => 1;
    our $count = 0;
}

my $number = bless [42], 'SprintfCount';
$SprintfCount::count = 0;
is(sprintf('%d', $number), '42', 'integer conversion formats the overloaded number');
is($SprintfCount::count, 1, 'numeric overload is called once');

my $precision = '9';
utf8::upgrade($precision);
my $formatted = sprintf("%.*f\n", $precision, 1.1);
ok(!utf8::is_utf8($formatted), 'UTF-8 precision does not upgrade the sprintf result');

my $wide_string = "\x{e9}";
my $string_result = sprintf('%s', $wide_string);
ok(!utf8::is_utf8($string_result), 'UTF-8 string argument does not upgrade the sprintf result');
