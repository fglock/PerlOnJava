use strict;
use warnings;
use Test::More;

my $value = 'a';
my $post = $value++;
is($post, 'a', 'postincrement returns the original alphabetic string');
is($value, 'b', 'postincrement advances an alphabetic string');

$value = 'az';
++ $value;
is($value, 'ba', 'preincrement carries an alphabetic string');

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= $_[0] };
    sub temporary_value {
        my $temporary = 'a';
        return $temporary . $temporary++ . $temporary++;
    }
    my $packed = pack('p', temporary_value());
}
unlike($warning, qr/isn't numeric/, 'pack argument postincrement does not issue a numeric warning');

done_testing();
