use strict;
use warnings;
use utf8;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval "use utf8;\nmy \$bad = q《unterminated》;\n";
    ok(!$ok, 'a mirrored non-Latin-1 delimiter is not paired without the feature');
}

like($warnings[0], qr{\AUse of '《' is deprecated as a string delimiter},
     'the future paired delimiter emits the deprecation warning');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval "use utf8; no warnings 'deprecated';\nmy \$bad = q《unterminated》;\n";
    ok(!$ok, 'the legacy delimiter remains unterminated when warnings are disabled');
}
is_deeply(\@warnings, [], 'no warnings deprecated suppresses the compatibility notice');

done_testing;
