use Test::More;

BEGIN {
    plan skip_all => 'requires Perl 5.44 class syntax' if $] < 5.044;
}

use v5.44;
use experimental 'class';

class GlobAssignmentObject {}

my $ok = eval q{*glob_assignment_object = GlobAssignmentObject->new;};
ok(!$ok, 'a class object cannot be assigned to a typeglob');
like($@, qr/Can't assign reference to OBJECT into a GLOB/, 'reports the Perl-compatible diagnostic');

done_testing;
