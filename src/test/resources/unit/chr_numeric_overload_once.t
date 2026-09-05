use strict;
use warnings;
use Test::More;

{
    package ChrNumber;
    our $calls = 0;
    use overload
        '0+' => sub { ++$calls; 65 },
        '""' => sub { die 'chr must use numeric overload' },
        fallback => 1;
}

my $value = bless {}, 'ChrNumber';
is(chr($value), 'A', 'chr uses numeric overload result');
is($ChrNumber::calls, 1, 'chr invokes numeric overload once');

my $nan;
{
    no warnings 'numeric';
    $nan = 'NaN' + 0;
}
my $nan_result = eval { chr($nan) };
ok(!defined($nan_result), 'chr rejects NaN kept in an arithmetic dual value');
like($@, qr/Cannot chr NaN/, 'chr reports the NaN diagnostic');

my $string_nan_result = eval { chr('NaN') };
ok(!defined($string_nan_result), 'chr rejects a stringified NaN');
like($@, qr/Cannot chr NaN/, 'chr reports the stringified NaN diagnostic');

done_testing();
