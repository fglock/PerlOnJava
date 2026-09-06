use strict;
use warnings;
use Test::More;

sub TIESCALAR { bless [] }
sub FETCH { goto MISSING_LABEL }

tie my $value, 'main';
my $ok = eval { my $copy = "$value"; 1 };

ok(!$ok, 'a goto from tied FETCH cannot escape the magic method');
like($@, qr/Can't find label MISSING_LABEL/,
    'tied FETCH reports the missing goto label');

done_testing;
