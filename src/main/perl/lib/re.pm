package re;

# Stub for re pragma - actual implementation is in Java (Re.java)
# This file exists for version detection by MM->parse_version()

use strict;
use warnings;

our $VERSION = "0.48";

# The Java implementation (Re.java) handles:
# - use re '/a', '/aa', '/u' - regex character class modifiers
# - use re 'strict' - enable experimental regex warnings
# - re::is_regexp($ref) - check if reference is compiled regex

1;

__END__

=head1 NAME

re - Perl pragma to alter regular expression behaviour

=head1 SYNOPSIS

    use re '/a';      # ASCII-restrict \w, \d, \s, \b
    use re '/aa';     # ASCII-restrict including case folding
    use re '/u';      # Unicode semantics
    use re 'strict';  # Enable experimental regex warnings

    if (re::is_regexp($ref)) { ... }

=head1 DESCRIPTION

This is the PerlOnJava implementation of the C<re> pragma.
See L<perldoc re> for the full Perl documentation.

=cut
