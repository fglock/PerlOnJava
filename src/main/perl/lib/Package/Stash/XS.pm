package Package::Stash::XS;

use strict;
use warnings;
use Package::Stash::PP ();

our $VERSION = '0.30';
our @ISA = qw(Package::Stash::PP);

# Module::Implementation installs direct references to the selected backend's
# symbols. Keep real entries in this stash as well as @ISA inheritance so the
# facade works with both normal Perl lookup and PerlOnJava's symbol-table path.
for my $symbol (qw(
    new name namespace add_symbol remove_glob has_symbol get_symbol
    get_or_add_symbol remove_symbol list_all_symbols get_all_symbols
)) {
    no strict 'refs';
    *{$symbol} = \&{"Package::Stash::PP::$symbol"};
}

1;

__END__

=head1 NAME

Package::Stash::XS - PerlOnJava compatibility provider

=head1 DESCRIPTION

PerlOnJava cannot load the native acceleration layer. This provider exposes
the same class API through the bundled C<Package::Stash::PP> implementation.

=cut
