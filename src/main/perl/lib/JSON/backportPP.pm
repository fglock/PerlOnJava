# PerlOnJava's JSON::backportPP.
#
# The CPAN `JSON` dispatcher's `PERL_JSON_BACKEND=JSON::backportPP`
# contract says: load JSON::PP's implementation without populating
# `$INC{'JSON/PP.pm'}`, and expose it as the class `JSON::backportPP`
# whose base class is `JSON::PP`.
#
# We achieve this by defining an empty `JSON::backportPP` class that
# inherits from `JSON::PP`, then `require`-ing JSON::PP and removing
# `JSON/PP.pm` from `%INC` so the contract is satisfied.

package JSON::backportPP;

use strict;
use warnings;

our $VERSION = '4.18';
our @ISA     = ('JSON::PP');

sub is_xs { 0 }
sub is_pp { 1 }

BEGIN {
    require JSON::PP;
    # Backend-identification contract: callers distinguish
    # "backportPP backend was loaded" from "JSON::PP was loaded
    # directly" by checking `$INC{'JSON/PP.pm'}`.  Hide our
    # `require JSON::PP` by removing that entry after load so tests
    # and CPAN dispatcher logic see only `JSON/backportPP.pm` in
    # `%INC`.  The actual JSON::PP code stays loaded (all subs are
    # defined in-place in the interpreter).
    delete $INC{'JSON/PP.pm'};
}

1;

__END__

=head1 NAME

JSON::backportPP - PerlOnJava shim providing the JSON::PP API without
populating C<$INC{'JSON/PP.pm'}>

=head1 DESCRIPTION

Loaded in place of C<JSON::PP> when the environment variable
C<PERL_JSON_BACKEND=JSON::backportPP> is set (see L<JSON>).  The
package C<JSON::backportPP> is a thin subclass of C<JSON::PP>;
calling any JSON::PP method through C<JSON::backportPP-E<gt>method>
dispatches to the same implementation as if C<JSON::PP> had been
loaded directly.  C<%INC> distinguishes the two — C<JSON/backportPP.pm>
is set, C<JSON/PP.pm> is not.

=cut
