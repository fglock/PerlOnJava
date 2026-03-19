package Package::Stash::Conflicts;
use strict;
use warnings;

our $VERSION = '0.40';

# PerlOnJava stub - conflict checking not needed
# 
# The original module uses Dist::CheckConflicts to verify there are no
# conflicting versions of Package::Stash with old versions of:
# - Class::MOP 1.08
# - MooseX::Method::Signatures 0.36
# - MooseX::Role::WithOverloading 0.08
# - namespace::clean 0.18
#
# In PerlOnJava's environment, these old conflicting versions don't exist,
# so we provide a no-op stub.

sub check_conflicts { }

1;

__END__

=head1 NAME

Package::Stash::Conflicts - PerlOnJava stub for conflict checking

=head1 DESCRIPTION

This is a stub implementation for PerlOnJava. The original module checks
for conflicting versions of Package::Stash, but this is not needed in
PerlOnJava's environment.

=cut
