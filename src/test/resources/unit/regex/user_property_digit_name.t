use strict;
use warnings;
use Test::More tests => 8;

sub Is0 { "41\n" }
sub In9x { "42\n" }

my $is_zero = qr/\p{Is0}/;
like('A', $is_zero, 'digit-leading Is suffix is a valid user property name');
unlike('B', $is_zero, 'digit-leading Is property preserves its ranges');

my $in_nine = qr/\p{In9x}/;
like('B', $in_nine, 'digit-leading In suffix is a valid user property name');
unlike('A', $in_nine, 'digit-leading In property preserves its ranges');

for my $name (qw(Is7 In8)) {
    my $ok = eval 'qr/(?[' . "\\P{$name}" . '])/; 1';
    ok(!$ok, "$name is rejected inside an extended set");
    like($@, qr/^Unknown user-defined property name "\Q$name\E"/,
        "$name reports the user-property diagnostic");
}
