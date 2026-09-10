use strict;
use warnings;
use Test::More;

our $stored;

sub TIESCALAR { bless [pop] }
sub FETCH     { $_[0][0] }
sub STORE     { $stored = pop }

tie my $value, '', 'a';
$value = 'b';
utf8::encode $value;
is $stored, 'a', 'utf8::encode fetches and stores through tied scalar magic';

tie $value, '', "\xC4\x80";
utf8::decode $value;
is $stored, "\x{100}", 'utf8::decode stores through tied scalar magic';

done_testing;
