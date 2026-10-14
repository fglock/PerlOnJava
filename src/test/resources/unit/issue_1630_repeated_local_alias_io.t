use strict;
use warnings;
use Test::More;

{
    package Issue1630RepeatedLocal;
    use Tie::Handle;

    sub TIEHANDLE { bless {}, shift }
    sub TELL { tell Issue1630RepeatedLocal::ORIGINAL_STDERR }
    sub PRINT { }

    *ORIGINAL_STDERR = *STDERR;
}

close *STDERR;
my $buffer = q{};
open *STDERR, '>', \$buffer or die "open STDERR: $!";
tie *CAPTURE, 'Issue1630RepeatedLocal';

for my $iteration (1 .. 3) {
    {
        local *STDERR = *CAPTURE;
        is(tell STDERR, 0,
            "saved glob alias keeps its IO through localization $iteration");
        {
            local *STDERR = *CAPTURE;
            isnt(tell ORIGINAL, 123,
                "saved glob alias does not dispatch to the replacement through nested localization $iteration");
        }
        isnt(tell ORIGINAL, 123,
            "saved glob alias still avoids the replacement after nested localization $iteration");
    }
}

is(tell Issue1630RepeatedLocal::ORIGINAL_STDERR, 0,
    'saved glob alias remains bound to the original IO after local scopes');

done_testing;
