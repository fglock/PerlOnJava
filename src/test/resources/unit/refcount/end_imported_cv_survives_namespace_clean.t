use strict;
use warnings;
use Test::More tests => 1;

package Issue1649::ImportedHelper;
use Exporter 'import';
BEGIN { our @EXPORT_OK = qw(assert_end_scope_reachable) }

sub assert_end_scope_reachable {
    Test::More::pass('imported helper remains callable after namespace cleanup');
}

package Issue1649::EndScope;
BEGIN { Issue1649::ImportedHelper->import('assert_end_scope_reachable') }
use namespace::clean;
my $weak_registry = {};

END {
    # namespace::clean removes the imported stash entry, but Perl has already
    # compiled this direct call and retains its CV for the END block.
    assert_end_scope_reachable($weak_registry, 'quiet');
}
