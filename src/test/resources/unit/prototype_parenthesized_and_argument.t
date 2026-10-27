use strict;
use warnings;
use Test::More tests => 4;

our @captured;
sub capture ($;$$) { @captured = @_ }

my $object = bless {}, 'PrototypeParenthesizedAndArgument';
capture (defined($object) and (ref $object), 'new() is ok');
is(scalar @captured, 1, 'parenthesized and expression is one prototype argument');
is($captured[0], 'new() is ok', 'comma inside and expression is parsed by Perl precedence');

my $size = 9.5367431640625;
my $unit = 'Mbytes';
capture (($size == 9.5367431640625) and ($unit eq 'Mbytes'), 'getsize() is ok');
is(scalar @captured, 1, 'nested parenthesized and expression is one prototype argument');
is($captured[0], 'getsize() is ok', 'nested and expression keeps the trailing argument inside its expression');
