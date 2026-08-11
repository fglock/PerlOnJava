package Proc::ProcessTable;

use strict;
use warnings;

our $VERSION = '0.637';

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);
require Proc::ProcessTable::Process;

sub new {
    my ($class, %args) = @_;
    return bless { %args }, ref($class) || $class;
}

1;

__END__

=head1 NAME

Proc::ProcessTable - portable process table access for PerlOnJava

=head1 DESCRIPTION

This compatibility facade preserves the CPAN object interface while the table
enumeration is supplied by Java's ProcessHandle API.

=cut
