use lib 'src/test/resources/unit/lib';
use Test::More tests => 2;

BEGIN { use_ok('Local::StrictVarsImport') }

Local::StrictVarsImport->import(qw($exported));
is($exported, 'available', 'module strict vars do not leak into caller compilation');
