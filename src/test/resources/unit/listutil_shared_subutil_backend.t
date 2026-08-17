use strict;
use warnings;

use Test::More tests => 2;

require List::Util;

ok(defined &Sub::Util::set_subname,
    'loading List::Util initializes the shared Sub::Util backend');

my $code = Sub::Util::set_subname('Shared::Backend::named', sub { 42 });
is($code->(), 42, 'shared-backend set_subname returns a usable coderef');
