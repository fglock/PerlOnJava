package HTTP::Tiny;

use warnings;
use strict;

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/HttpTiny.java

our $VERSION = "0.090";

sub new {
    my ($class, %args) = @_;
    
    $args{agent} //= "HTTP-Tiny/$VERSION";
    $args{agent} .= "HTTP-Tiny/$VERSION" if substr($args{agent}, -1, 1) eq " ";

    $args{timeout} ||= 60;

    $args{verify_SSL} //= 1;

    $args{default_headers} //= {};

    return bless \%args, $class;
}

1;

