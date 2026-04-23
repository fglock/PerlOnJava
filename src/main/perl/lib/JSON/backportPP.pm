# PerlOnJava's JSON::backportPP.
#
# The CPAN `JSON` dispatcher's `PERL_JSON_BACKEND=JSON::backportPP`
# contract says: load JSON::PP's implementation without populating
# `$INC{'JSON/PP.pm'}`, and expose it as the class `JSON::backportPP`
# whose base class is `JSON::PP`.
#
# We achieve this by defining an empty `JSON::backportPP` class that
# inherits from `JSON::PP`, then loading JSON::PP's source via
# open/read/eval.  Reading+eval avoids `require JSON::PP`, which would
# populate `%INC{'JSON/PP.pm'}` — the very thing the backportPP
# contract promises not to do.

package JSON::backportPP;

use strict;
use warnings;

our $VERSION = '4.18';
our @ISA     = ('JSON::PP');

sub is_xs { 0 }
sub is_pp { 1 }

# Load the JSON::PP implementation.  `do FILE` would be more natural,
# but PerlOnJava's `do` doesn't yet honour the `jar:` @INC entries our
# JAR uses; open/read/eval is portable across the JAR and the
# filesystem.
{
    my $loaded;
    sub _ensure_json_pp_loaded {
        return 1 if $loaded++;
        return 1 if defined &JSON::PP::encode_json;

        my $pp_source;
        for my $dir (@INC) {
            my $path = "$dir/JSON/PP.pm";
            if (-f $path) {
                if (open my $fh, '<', $path) {
                    local $/;
                    $pp_source = <$fh>;
                    close $fh;
                    last;
                }
            }
        }
        die "JSON::backportPP: cannot locate JSON/PP.pm in \@INC\n"
            unless defined $pp_source;

        # Evaluate under a #line directive so warnings/errors point at
        # the original JSON::PP source rather than an anonymous eval.
        # JSON::PP.pm declares `package JSON::PP;` at its top, so all
        # its subs land in that package — which is what we want.
        local $@;
        eval "#line 1 \"JSON/PP.pm (via JSON::backportPP)\"\n" . $pp_source;
        die "JSON::backportPP: failed to load JSON::PP source: $@" if $@;
        return 1;
    }
}

__PACKAGE__->_ensure_json_pp_loaded;

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
