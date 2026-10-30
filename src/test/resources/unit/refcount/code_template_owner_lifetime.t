use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
my $plain = sub { 42 };
my $weak = $plain;
weaken($weak);
$plain->();
undef $plain;
ok(defined $weak, 'noncapturing anonymous CODE remains owned by its optree');
my $constant = sub () { 42 };
$weak = $constant;
weaken($weak);
$constant->();
undef $constant;
ok(defined $weak, 'constant anonymous CODE remains owned by its optree');
sub make_counter {
    my $value = 0;
    return sub { ++$value };
}
my $capturing = make_counter();
$weak = $capturing;
weaken($weak);
is($capturing->(), 1, 'capturing callback remains callable');
undef $capturing;
ok(!defined $weak, 'last counted owner releases an inactive capturing callback');
done_testing();
