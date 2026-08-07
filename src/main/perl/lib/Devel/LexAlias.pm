package Devel::LexAlias;

use strict;
use warnings;
use Exporter 'import';
use Devel::Caller qw(caller_cv);
use XSLoader;

our $VERSION = '0.05';
our @EXPORT_OK = qw(lexalias);

XSLoader::load(__PACKAGE__, $VERSION);

sub lexalias {
    my $cv = shift;
    unless (ref $cv eq 'CODE') {
        $cv = caller_cv($cv + 1);
    }
    return _lexalias($cv, @_);
}

1;

__END__

=head1 NAME

Devel::LexAlias - alias lexical variables

=head1 DESCRIPTION

PerlOnJava-compatible port of Richard Clamp's Devel::LexAlias. The original
module's XS pad rebinding is provided by the PerlOnJava runtime.

=head1 AUTHOR

Richard Clamp E<lt>richardc@unixbeard.netE<gt>

=head1 COPYRIGHT

Copyright (c) 2002, 2013, Richard Clamp. All Rights Reserved. This module is
free software; it may be used, redistributed and/or modified under the same
terms as Perl itself.

=cut
