use strict;
use warnings;

use Safe;
use Test::More tests => 5;

my $default = Safe->new;
$default->permit_only(':default');
ok($default->reval('sub { 1 }'), 'default mask permits a constant closure');

ok(!$default->reval('sub { unlink "not-created" }'),
    'default mask rejects filesystem mutation');
like($@, qr/'unlink' trapped by operation mask/,
    'rejected operation is reported through eval error');

ok(!$default->reval('sub { stat($0) }'),
    'default mask rejects filesystem metadata access');

my $browse = Safe->new;
$browse->permit_only(':browse');
ok($browse->reval('sub { stat($0) }'),
    'browse mask permits filesystem metadata access');
