use strict;
use warnings;
use Test::More;

my $ascii = 'PerlOnJava';
is(substr($ascii . ':' . 42, -8), 'nJava:42',
    'concat followed by negative-offset substr keeps the requested suffix');

my $unicode = "a\x{20ac}b";
is(substr($unicode . ':z', -3), 'b:z',
    'concat-substr offsets use Perl characters for Unicode strings');

my $bytes = pack('C*', 0x80, 0x81);
is(unpack('H*', substr($bytes . pack('C', 0x82), -2)), '8182',
    'concat-substr preserves byte-string octets');

{
    package SubstrConcatTied;

    sub TIESCALAR { bless { value => $_[1], fetches => $_[2] }, $_[0] }
    sub FETCH { ++${$_[0]{fetches}}; return $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1] }
}

my $fetches = 0;
tie my $tied, 'SubstrConcatTied', 'A', \$fetches;
is(substr($tied . ':x', -2), ':x',
    'tied concat operand retains its result');
is($fetches, 1, 'tied concat operand is fetched exactly once');

{
    package SubstrConcatOverload;
    use overload '""' => sub { ++$main::substr_concat_stringifies; $_[0]{value} }, fallback => 1;
}

our $substr_concat_stringifies = 0;
my $overloaded = bless { value => 'O' }, 'SubstrConcatOverload';
is(substr($overloaded . ':x', -2), ':x',
    'overloaded concat operand retains its result');
is($substr_concat_stringifies, 1,
    'overloaded concat operand is stringified exactly once');

done_testing;
