use strict;
use warnings;
use Test::More;

sub scalarref (\$) { }

my $ok = eval 'scalarref(sub {}); 1';
ok(!$ok, 'an anonymous subroutine is rejected by a scalar-reference prototype');
like($@, qr/anonymous subroutine/, 'diagnostic identifies the anonymous subroutine');
like($@, qr/scalarref/, 'diagnostic identifies the called subroutine');

done_testing;
