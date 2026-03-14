package ExtUtils::MakeMaker::Config;
use strict;
use warnings;

our $VERSION = '7.70_perlonjava';

# This module provides a Config hash that MakeMaker uses.
# It's a wrapper around the Config module.

use Config;

# Re-export %Config
our %Config = %Config::Config;

# Add some PerlOnJava-specific values
$Config{perlonjava} = 1;
$Config{usedl} = 0;  # No dynamic loading of C code

sub import {
    my $class = shift;
    my $caller = caller;
    
    no strict 'refs';
    *{"${caller}::Config"} = \%Config;
}

1;

__END__

=head1 NAME

ExtUtils::MakeMaker::Config - Config wrapper for PerlOnJava

=head1 DESCRIPTION

Provides access to %Config for MakeMaker scripts.

=cut
