package Devel::GlobalDestruction;

use strict;
use warnings;

our $VERSION = '0.14';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(in_global_destruction);
our @EXPORT_OK = qw(in_global_destruction);

# PerlOnJava always has ${^GLOBAL_PHASE} (5.14+ feature)
sub in_global_destruction () { ${^GLOBAL_PHASE} eq 'DESTRUCT' }

1;

__END__

=head1 NAME

Devel::GlobalDestruction - Provides function returning the equivalent of
C<${^GLOBAL_PHASE} eq 'DESTRUCT'> for older perls.

=head1 SYNOPSIS

    package Foo;
    use Devel::GlobalDestruction;

    use namespace::clean; # to avoid having an "in_global_destruction" method

    sub DESTROY {
        return if in_global_destruction;

        do_something_a_little_tricky();
    }

=head1 DESCRIPTION

Perl's global destruction is a little tricky to deal with WRT finalizers
because it's not ordered and objects can sometimes disappear.

Writing defensive destructors is hard and annoying, and usually if global
destruction is happening you only need the destructors that free up non
process local resources to actually execute.

For these constructors you can avoid the mess by simply bailing out if global
destruction is in effect.

=head1 EXPORTS

=over 4

=item in_global_destruction

Returns true if the interpreter is in global destruction. Returns
C<${^GLOBAL_PHASE} eq 'DESTRUCT'>.

=back

=cut
