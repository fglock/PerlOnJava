use strict;
use warnings;
use Test::More;

my $ok;
{
    $ok = eval q{
        use warnings;
        use v5.12;
        use v5.20;
        1;
    };
}
ok(!$ok, 'a different in-scope version is rejected');
like($@, qr/Changing use VERSION while another use VERSION is in scope is not permitted/,
    'modern version changes use the blead fatal diagnostic');

{
    $ok = eval q{
        no warnings 'deprecated::subsequent_use_version';
        use v5.12;
        use v5.20;
        1;
    };
}
ok(!$ok, 'disabling warnings does not permit a different in-scope version');
like($@, qr/Changing use VERSION while another use VERSION is in scope is not permitted/,
    'the fatal version diagnostic is independent of warning settings');

{
    $ok = eval q{
        use v5.20;
        use v5.20;
        1;
    };
}
ok($ok, 'repeating the same in-scope version is allowed');

{
    $ok = eval q{
        use 5.006;
        use v5.20;
        1;
    };
}
ok($ok, 'legacy decimal use VERSION can advance to a later feature bundle');

{
    $ok = eval q{
        use 5.006;
        use v5.20;
        use v5.22;
        1;
    };
}
ok(!$ok, 'a legacy minimum does not permit a second modern version change');
like($@, qr/Changing use VERSION while another use VERSION is in scope is not permitted/,
    'the modern version becomes the prevailing in-scope declaration');

done_testing;
