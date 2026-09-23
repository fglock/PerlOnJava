use strict;
use warnings;
use Test::More tests => 3;

# These stash entries must not rewrite syntax which Perl reserves for the
# builtin I/O operations.  This is distinct from a package-local readpipe
# subroutine, which SystemOperator dispatches at runtime.
$CORE::GLOBAL::{readline} = [];
eval '<STDOUT> if 0';
is($@, '', 'diamond syntax ignores CORE::GLOBAL::readline');

$CORE::GLOBAL::{readpipe} = [];
eval '`` if 0';
is($@, '', 'backticks ignore CORE::GLOBAL::readpipe');

format STDERR =
.

my $format = *STDERR{FORMAT};
eval { &$format };
like($@, qr/Not a CODE reference/, 'a FORMAT slot is not a CODE reference');
