use Test::More tests => 7;
BEGIN { use_ok('Taint::Runtime') }

Taint::Runtime->import(qw($TAINT taint_enabled));

ok(!$TAINT, 'Not on');
ok(!taint_enabled(), 'Taint is not on yet');
$TAINT = 1;
ok(taint_enabled(), 'Taint is on');
$TAINT = 0;
ok(!taint_enabled(), 'Taint disabled');
{
    local $TAINT = 1;
    ok(taint_enabled(), 'Taint is on while localized');
}
ok(!taint_enabled(), 'Taint disabled after localization');
