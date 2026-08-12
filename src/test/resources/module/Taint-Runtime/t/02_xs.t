use Test::More tests => 8;
BEGIN { use_ok('Taint::Runtime') }

Taint::Runtime->import(qw(taint_start taint_enabled taint untaint is_tainted));

ok(!taint_enabled(), 'Taint is not on yet');
taint_start();
ok(taint_enabled(), 'Taint is on');

my $data = 'foo';
ok(!is_tainted($data), 'No false positive on is_tainted');
my $copy = taint($data);
ok(is_tainted($copy), 'Made a tainted copy');
taint(\$data);
ok(is_tainted($data), 'Tainted it directly');
$copy = untaint($data);
ok(!is_tainted($copy), 'Made a clean copy');
untaint(\$data);
ok(!is_tainted($data), 'Cleaned it directly');
