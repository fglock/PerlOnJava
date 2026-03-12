package Java::System;

#
# Java::System - PerlOnJava-specific module for Java system access
#
# This module is specific to PerlOnJava and provides access to
# Java System properties and environment variables.
#
# Author: Flavio S. Glock
# The implementation is in: src/main/java/org/perlonjava/perlmodule/JavaSystem.java
#

XSLoader::load( 'Java::System' );

1;

__END__

=head1 NAME

Java::System - Access to Java system properties and environment

=head1 SYNOPSIS

    use Java::System qw(getProperty getenv);
    
    my $java_version = getProperty('java.version');
    my $home = getenv('HOME');

=head1 DESCRIPTION

This is a PerlOnJava-specific module that provides access to Java system
properties via Java's System.getProperty() and environment variables via
System.getenv().

=head1 AUTHOR

Flavio S. Glock

=cut

