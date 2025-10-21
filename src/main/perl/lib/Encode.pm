package Encode;
use strict;
use warnings;
use XSLoader;

our $VERSION = '3.21';

# Load the Java implementation
XSLoader::load('Encode', $VERSION);

# Load Encode::XS for encoding object methods
XSLoader::load('Encode::XS');

# NOTE: The core implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/Encode.java
# Encoding objects are implemented in:
#       src/main/java/org/perlonjava/perlmodule/EncodeXS.java

1;

__END__

=head1 NAME

Encode - character encodings in Perl

=head1 SYNOPSIS

    use Encode qw(decode encode);
    
    $characters = decode('UTF-8', $octets);
    $octets = encode('UTF-8', $characters);
    
    # Object interface
    $enc = Encode::find_encoding('UTF-8');
    $characters = $enc->decode($octets);
    $octets = $enc->encode($characters);

=head1 DESCRIPTION

The Encode module provides interfaces for encoding and decoding strings
between Perl's internal format and various character encodings.

=cut

